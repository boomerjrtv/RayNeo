use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};
use tracing::info;

/// A connected client (Pixel phone acting as XR thin client).
#[derive(Debug, Clone)]
pub struct Client {
    pub id: ClientId,
    pub state: ClientState,
}

pub type ClientId = uuid::Uuid;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ClientState {
    /// WebRTC signaling in progress.
    Connecting,
    /// WebRTC peer connection established, streaming active.
    Streaming,
    /// Client disconnected.
    Disconnected,
}

/// Pose data received from the phone / glasses IMU.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HeadPose {
    /// Quaternion [x, y, z, w]
    pub orientation: [f32; 4],
    /// Position [x, y, z] in meters
    pub position: [f32; 3],
    /// Timestamp in microseconds (monotonic)
    pub timestamp_us: u64,
}

/// Any message the phone can send up to the server.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientMessage {
    HeadPose(HeadPose),
    Keepalive,
    RequestKeyframe,
}

/// Messages server sends down to the phone.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerMessage {
    /// Acknowledge connection.
    Connected { client_id: String },
    /// Configuration the client should apply.
    StreamConfig(StreamConfig),
    Error { message: String },
}

/// Stream parameters negotiated at session start.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StreamConfig {
    /// Target display width (per-eye for stereoscopic, or full width for mono).
    pub width: u32,
    pub height: u32,
    /// Target framerate.
    pub fps: u32,
    /// Codec preference.
    pub codec: VideoCodec,
    /// Whether to render stereo side-by-side.
    pub stereo: bool,
}

impl Default for StreamConfig {
    fn default() -> Self {
        Self {
            // RayNeo Air 2 display: 1920x1080 per eye; for initial mono streaming use full HD.
            width: 1920,
            height: 1080,
            fps: 120,
            codec: VideoCodec::H264,
            stereo: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum VideoCodec {
    H264,
    H265,
    Av1,
}

pub struct SessionManager {
    clients: Arc<RwLock<HashMap<ClientId, Client>>>,
    config: StreamConfig,
}

impl SessionManager {
    pub fn new(config: StreamConfig) -> Self {
        Self {
            clients: Arc::new(RwLock::new(HashMap::new())),
            config,
        }
    }

    pub async fn add_client(&self) -> ClientId {
        let id = uuid::Uuid::new_v4();
        let client = Client { id, state: ClientState::Connecting };
        self.clients.write().await.insert(id, client);
        info!("client connected: {id}");
        id
    }

    pub async fn set_streaming(&self, id: ClientId) {
        if let Some(c) = self.clients.write().await.get_mut(&id) {
            c.state = ClientState::Streaming;
            info!("client streaming: {id}");
        }
    }

    pub async fn remove_client(&self, id: ClientId) {
        self.clients.write().await.remove(&id);
        info!("client disconnected: {id}");
    }

    pub fn stream_config(&self) -> &StreamConfig {
        &self.config
    }
}
