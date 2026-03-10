//! WebSocket signaling + WebRTC session per client.

use anyhow::Result;
use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    response::IntoResponse,
    routing::get,
    Router,
};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::mpsc;
use tower_http::cors::CorsLayer;
use tracing::{error, info, warn};

use crate::peer::PeerHandle;
use crate::session::{ClientMessage, ServerMessage, SessionManager, StreamConfig};
use crate::stream::FrameStream;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum SignalMessage {
    Offer { sdp: String },
    Answer { sdp: String },
    IceCandidate { candidate: String, sdp_mid: Option<String>, sdp_mline_index: Option<u16> },
    Config(StreamConfig),
    Ping,
    Pong,
}

#[derive(Clone)]
struct AppState {
    sessions: Arc<SessionManager>,
    frames: FrameStream,
}

pub struct SignalingServer {
    session_manager: Arc<SessionManager>,
    frame_stream: FrameStream,
    port: u16,
}

impl SignalingServer {
    pub fn new(session_manager: Arc<SessionManager>, frame_stream: FrameStream, port: u16) -> Self {
        Self { session_manager, frame_stream, port }
    }

    pub async fn run(self) -> Result<()> {
        let state = AppState {
            sessions: Arc::clone(&self.session_manager),
            frames: self.frame_stream,
        };
        let app = Router::new()
            .route("/signal", get(ws_handler))
            .route("/health", get(|| async { "ok" }))
            .layer(CorsLayer::permissive())
            .with_state(state);

        let addr = format!("0.0.0.0:{}", self.port);
        info!("signaling server listening on {addr}");
        let listener = tokio::net::TcpListener::bind(&addr).await?;
        axum::serve(listener, app).await?;
        Ok(())
    }
}

async fn ws_handler(ws: WebSocketUpgrade, State(state): State<AppState>) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_socket(socket, state))
}

async fn handle_socket(mut socket: WebSocket, state: AppState) {
    let client_id = state.sessions.add_client().await;
    info!("client connected: {client_id}");

    // Send welcome + config
    for msg in [
        ServerMessage::Connected { client_id: client_id.to_string() },
        ServerMessage::StreamConfig(state.sessions.stream_config().clone()),
    ] {
        if let Ok(json) = serde_json::to_string(&msg) {
            let _ = socket.send(Message::Text(json.into())).await;
        }
    }

    // Create WebRTC peer
    let peer = match PeerHandle::new(state.frames.clone()).await {
        Ok(p) => Arc::new(p),
        Err(e) => { error!("peer creation failed: {e}"); return; }
    };

    // All outbound WS messages go through this channel so the select loop owns the socket
    let (out_tx, mut out_rx) = mpsc::unbounded_channel::<String>();

    // Forward local ICE candidates → phone
    let ice_tx = out_tx.clone();
    peer.on_local_ice_candidate(move |candidate, sdp_mid, sdp_mline_index| {
        if let Ok(json) = serde_json::to_string(&SignalMessage::IceCandidate {
            candidate, sdp_mid, sdp_mline_index,
        }) {
            let _ = ice_tx.send(json);
        }
    });

    loop {
        tokio::select! {
            // Outbound messages → phone
            Some(json) = out_rx.recv() => {
                if socket.send(Message::Text(json.into())).await.is_err() { break; }
            }

            // Inbound messages ← phone
            msg = socket.recv() => {
                match msg {
                    Some(Ok(Message::Text(text))) => {
                        let out = out_tx.clone();
                        let peer = Arc::clone(&peer);
                        // Spawn so we don't block the select loop during offer processing
                        tokio::spawn(async move {
                            if let Err(e) = handle_inbound(text.as_str(), &peer, out).await {
                                warn!("inbound error: {e}");
                            }
                        });
                    }
                    Some(Ok(Message::Close(_))) | None => break,
                    _ => {}
                }
            }
        }
    }

    let _ = peer.pc.close().await;
    state.sessions.remove_client(client_id).await;
    info!("client disconnected: {client_id}");
}

async fn handle_inbound(
    text: &str,
    peer: &PeerHandle,
    out: mpsc::UnboundedSender<String>,
) -> Result<()> {
    if let Ok(signal) = serde_json::from_str::<SignalMessage>(text) {
        match signal {
            SignalMessage::Offer { sdp } => {
                info!("SDP offer received");
                let answer_sdp = peer.handle_offer(sdp).await?;
                let json = serde_json::to_string(&SignalMessage::Answer { sdp: answer_sdp })?;
                let _ = out.send(json);
            }
            SignalMessage::IceCandidate { candidate, sdp_mid, sdp_mline_index } => {
                peer.add_ice_candidate(candidate, sdp_mid, sdp_mline_index).await?;
            }
            SignalMessage::Ping => {
                let _ = out.send(serde_json::to_string(&SignalMessage::Pong)?);
            }
            _ => {}
        }
        return Ok(());
    }

    if let Ok(msg) = serde_json::from_str::<ClientMessage>(text) {
        match msg {
            ClientMessage::HeadPose(pose) => {
                tracing::trace!("pose {:?}", pose.orientation);
            }
            ClientMessage::RequestKeyframe => info!("keyframe requested"),
            ClientMessage::Keepalive => {}
        }
    }

    Ok(())
}
