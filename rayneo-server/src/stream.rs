//! Frame capture and encoding pipeline.
//!
//! Capture flow:
//!   Desktop compositor (X11/Wayland/virtual framebuffer)
//!     → raw RGBA frames
//!     → H.264/H.265 encoder (ffmpeg or gstreamer)
//!     → WebRTC video track
//!
//! For the XR virtual desktop use case, we capture the entire desktop
//! (or a virtual display) and stream it to the glasses.  The phone
//! acts purely as a display + IMU relay.

use anyhow::Result;
use bytes::Bytes;
use tokio::sync::broadcast;
use tracing::info;

/// An encoded video frame ready to send over WebRTC.
#[derive(Debug, Clone)]
pub struct EncodedFrame {
    pub data: Bytes,
    pub is_keyframe: bool,
    pub timestamp_us: u64,
}

/// Handle to the encoding pipeline.  Clone freely — each WebRTC track
/// can subscribe to the same broadcast channel.
#[derive(Clone)]
pub struct FrameStream {
    tx: broadcast::Sender<EncodedFrame>,
}

impl FrameStream {
    pub fn new(channel_capacity: usize) -> Self {
        let (tx, _) = broadcast::channel(channel_capacity);
        Self { tx }
    }

    pub fn subscribe(&self) -> broadcast::Receiver<EncodedFrame> {
        self.tx.subscribe()
    }

    /// Push an encoded frame to all subscribers.
    pub fn push(&self, frame: EncodedFrame) {
        // Ignore send errors — no subscribers is fine.
        let _ = self.tx.send(frame);
    }
}

/// Screen capture source.  Currently a stub; will call into
/// X11 (XShm), PipeWire, or a virtual framebuffer depending on the server setup.
pub struct ScreenCapture {
    width: u32,
    height: u32,
    fps: u32,
}

impl ScreenCapture {
    pub fn new(width: u32, height: u32, fps: u32) -> Self {
        Self { width, height, fps }
    }

    /// Start capture loop.  Sends raw RGBA frames via the returned channel.
    /// Each frame is `width * height * 4` bytes.
    pub fn start(self) -> tokio::sync::mpsc::Receiver<Vec<u8>> {
        let (tx, rx) = tokio::sync::mpsc::channel(4);
        let frame_size = (self.width * self.height * 4) as usize;
        let interval = std::time::Duration::from_micros(1_000_000 / self.fps as u64);

        tokio::spawn(async move {
            info!(
                "screen capture started {}x{} @ {}fps",
                self.width, self.height, self.fps
            );
            let mut ticker = tokio::time::interval(interval);
            loop {
                ticker.tick().await;
                // TODO: replace with real X11/PipeWire capture
                // For now emit a blank frame so the pipeline compiles and runs.
                let frame = vec![0u8; frame_size];
                if tx.send(frame).await.is_err() {
                    break;
                }
            }
        });

        rx
    }
}
