//! Screen capture + H.264 encoding via GStreamer.
//!
//! Pipeline:
//!   ximagesrc (X11 XShm) → videoconvert → x264enc (ultrafast/zerolatency) → rtph264pay → appsink
//!
//! Encoded RTP packets come out of the appsink and are pushed into the FrameStream
//! broadcast channel, where WebRTC tracks subscribe to them.

use anyhow::{Context, Result};
use bytes::Bytes;
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::{error, info};

use crate::stream::{EncodedFrame, FrameStream};

/// Configuration for the capture+encode pipeline.
#[derive(Debug, Clone)]
pub struct CaptureConfig {
    pub display: String,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    /// kbps
    pub bitrate_kbps: u32,
    pub encoder: Encoder,
    /// Localhost UDP port for RTP output — each datagram = one RTP packet
    pub rtp_port: u16,
}

#[derive(Debug, Clone)]
pub enum Encoder {
    /// Software x264 — always works, uses CPU.
    X264,
    /// AMD VAAPI hardware — faster, lower CPU, requires Mesa VAAPI.
    VaapiH264,
}

impl Default for CaptureConfig {
    fn default() -> Self {
        Self {
            display: ":2".into(),
            width: 1920,
            height: 1080,
            fps: 72,
            bitrate_kbps: 10_000,
            encoder: Encoder::X264,
            rtp_port: 5004,
        }
    }
}

/// Runs the GStreamer pipeline as a subprocess using `gst-launch-1.0`,
/// reads encoded RTP H.264 packets from stdout, and pushes them to `FrameStream`.
///
/// This is a pragmatic bootstrap approach — a future iteration can use the
/// GStreamer Rust bindings (gstreamer crate) for tighter integration.
pub struct CapturePipeline {
    config: CaptureConfig,
    frame_stream: FrameStream,
}

impl CapturePipeline {
    pub fn new(config: CaptureConfig, frame_stream: FrameStream) -> Self {
        Self { config, frame_stream }
    }

    pub fn start(self) -> tokio::task::JoinHandle<()> {
        tokio::task::spawn_blocking(move || {
            if let Err(e) = self.run() {
                error!("capture pipeline error: {e:#}");
            }
        })
    }

    fn run(self) -> Result<()> {
        let cfg = &self.config;
        let encoder_element = match cfg.encoder {
            Encoder::X264 => format!(
                "x264enc speed-preset=ultrafast tune=zerolatency bitrate={} key-int-max=60",
                cfg.bitrate_kbps
            ),
            Encoder::VaapiH264 => format!(
                "vaapih264enc bitrate={} keyframe-period=60",
                cfg.bitrate_kbps
            ),
        };

        // Build pipeline as individual tokens — caps must be single comma-joined args
        let pipeline_args: Vec<String> = vec![
            "ximagesrc".into(),
            format!("display-name={}", cfg.display),
            "use-damage=false".into(),
            "!".into(),
            format!("video/x-raw,framerate={}/1", cfg.fps), // single arg, comma-joined
            "!".into(),
            "videoscale".into(),
            "!".into(),
            format!("video/x-raw,width={},height={}", cfg.width, cfg.height),
            "!".into(),
            "videoconvert".into(),
            "!".into(),
        ];

        // Encoder tokens
        let encoder_tokens: Vec<String> = encoder_element
            .split_whitespace()
            .map(str::to_owned)
            .collect();

        let tail_args: Vec<String> = vec![
            "!".into(),
            "rtph264pay".into(),
            "config-interval=1".into(),
            "pt=96".into(),
            "!".into(),
            "udpsink".into(),
            "host=127.0.0.1".into(),
            format!("port={}", cfg.rtp_port),
        ];

        let all_args: Vec<String> = pipeline_args
            .into_iter()
            .chain(encoder_tokens)
            .chain(tail_args)
            .collect();

        info!("starting capture pipeline: gst-launch-1.0 -q {}", all_args.join(" "));

        let mut child = Command::new("gst-launch-1.0")
            .arg("-q")
            .args(&all_args)
            .env("DISPLAY", &cfg.display)
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()
            .context("failed to spawn gst-launch-1.0")?;

        // Bind UDP socket — each recv() returns exactly one RTP packet (datagram boundary preserved)
        let socket = std::net::UdpSocket::bind(format!("127.0.0.1:{}", cfg.rtp_port))
            .context("failed to bind RTP UDP socket")?;
        socket.set_read_timeout(Some(std::time::Duration::from_millis(500)))?;

        let stream = self.frame_stream.clone();
        let mut buf = vec![0u8; 65535];
        let mut timestamp: u64 = 0;
        let frame_us = 1_000_000 / self.config.fps as u64;

        info!("capture pipeline running, receiving RTP on 127.0.0.1:{}", cfg.rtp_port);
        loop {
            match socket.recv(&mut buf) {
                Ok(n) => {
                    let is_keyframe = is_h264_keyframe(&buf[..n]);
                    stream.push(EncodedFrame {
                        data: Bytes::copy_from_slice(&buf[..n]),
                        is_keyframe,
                        timestamp_us: timestamp,
                    });
                    if is_keyframe { timestamp += frame_us; }
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock
                       || e.kind() == std::io::ErrorKind::TimedOut => {
                    // Check if gst-launch died
                    match child.try_wait() {
                        Ok(Some(status)) => {
                            info!("gst-launch exited: {status}");
                            break;
                        }
                        Ok(None) => continue, // still running, just no data yet
                        Err(e) => { error!("child wait error: {e}"); break; }
                    }
                }
                Err(e) => { error!("RTP recv error: {e}"); break; }
            }
        }

        let _ = child.kill();
        Ok(())
    }
}

/// Heuristic: check if an RTP packet carries an H.264 IDR (keyframe).
/// RTP header is 12 bytes; H.264 NAL type is in the low 5 bits of the first payload byte.
fn is_h264_keyframe(rtp: &[u8]) -> bool {
    if rtp.len() < 13 { return false; }
    let nal_type = rtp[12] & 0x1F;
    nal_type == 5  // IDR slice
}
