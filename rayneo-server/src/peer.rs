//! WebRTC peer connection — one per connected phone client.

use anyhow::Result;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::broadcast;
use tracing::{error, info, warn};
use webrtc::{
    api::{
        interceptor_registry::register_default_interceptors,
        media_engine::{MediaEngine, MIME_TYPE_H264},
        APIBuilder,
    },
    ice_transport::ice_server::RTCIceServer,
    interceptor::registry::Registry,
    media::Sample,
    peer_connection::{
        configuration::RTCConfiguration,
        peer_connection_state::RTCPeerConnectionState,
        sdp::session_description::RTCSessionDescription,
        RTCPeerConnection,
    },
    rtp_transceiver::rtp_codec::{RTCRtpCodecCapability, RTCRtpCodecParameters, RTPCodecType},
    track::track_local::{
        track_local_static_sample::TrackLocalStaticSample, TrackLocal,
    },
};

use crate::stream::FrameStream;

pub struct PeerHandle {
    pub pc: Arc<RTCPeerConnection>,
    video_track: Arc<TrackLocalStaticSample>,
}

impl PeerHandle {
    pub async fn new(frame_stream: FrameStream) -> Result<Self> {
        // Media engine: H.264 only
        let mut media_engine = MediaEngine::default();
        media_engine.register_codec(
            RTCRtpCodecParameters {
                capability: RTCRtpCodecCapability {
                    mime_type: MIME_TYPE_H264.to_owned(),
                    clock_rate: 90000,
                    channels: 0,
                    sdp_fmtp_line: "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f".into(),
                    rtcp_feedback: vec![],
                },
                payload_type: 96,
                ..Default::default()
            },
            RTPCodecType::Video,
        )?;

        let mut registry = Registry::new();
        registry = register_default_interceptors(registry, &mut media_engine)?;

        let api = APIBuilder::new()
            .with_media_engine(media_engine)
            .with_interceptor_registry(registry)
            .build();

        let config = RTCConfiguration {
            ice_servers: vec![RTCIceServer {
                urls: vec!["stun:stun.l.google.com:19302".to_owned()],
                ..Default::default()
            }],
            ..Default::default()
        };

        let pc = Arc::new(api.new_peer_connection(config).await?);

        // H.264 video track
        let video_track = Arc::new(TrackLocalStaticSample::new(
            RTCRtpCodecCapability {
                mime_type: MIME_TYPE_H264.to_owned(),
                ..Default::default()
            },
            "video".to_owned(),
            "rayneo-stream".to_owned(),
        ));

        pc.add_track(Arc::clone(&video_track) as Arc<dyn TrackLocal + Send + Sync>).await?;

        pc.on_peer_connection_state_change(Box::new(|state| {
            info!("WebRTC state: {state}");
            Box::pin(async {})
        }));

        // Pump encoded frames into the video track
        let track = Arc::clone(&video_track);
        let mut rx = frame_stream.subscribe();
        let frame_duration = Duration::from_nanos(1_000_000_000 / 120);
        tokio::spawn(async move {
            loop {
                match rx.recv().await {
                    Ok(frame) => {
                        let sample = Sample {
                            data: frame.data,
                            duration: frame_duration,
                            ..Default::default()
                        };
                        if track.write_sample(&sample).await.is_err() {
                            break;
                        }
                    }
                    Err(broadcast::error::RecvError::Lagged(n)) => {
                        warn!("dropped {n} frames (receiver too slow)");
                    }
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
        });

        Ok(Self { pc, video_track })
    }

    /// Accept SDP offer, return SDP answer.
    pub async fn handle_offer(&self, offer_sdp: String) -> Result<String> {
        self.pc.set_remote_description(RTCSessionDescription::offer(offer_sdp)?).await?;
        let answer = self.pc.create_answer(None).await?;
        let mut gather_done = self.pc.gathering_complete_promise().await;
        self.pc.set_local_description(answer).await?;
        let _ = gather_done.recv().await;
        let desc = self.pc.local_description().await
            .ok_or_else(|| anyhow::anyhow!("no local description"))?;
        Ok(desc.sdp)
    }

    pub async fn add_ice_candidate(
        &self,
        candidate: String,
        mid: Option<String>,
        mline_index: Option<u16>,
    ) -> Result<()> {
        use webrtc::ice_transport::ice_candidate::RTCIceCandidateInit;
        self.pc.add_ice_candidate(RTCIceCandidateInit {
            candidate,
            sdp_mid: mid,
            sdp_mline_index: mline_index,
            ..Default::default()
        }).await?;
        Ok(())
    }

    pub fn on_local_ice_candidate<F>(&self, callback: F)
    where
        F: Fn(String, Option<String>, Option<u16>) + Send + Sync + 'static,
    {
        let cb = Arc::new(callback);
        self.pc.on_ice_candidate(Box::new(move |c| {
            let cb = Arc::clone(&cb);
            Box::pin(async move {
                if let Some(c) = c {
                    if let Ok(init) = c.to_json() {
                        cb(init.candidate, init.sdp_mid, init.sdp_mline_index);
                    }
                }
            })
        }));
    }
}
