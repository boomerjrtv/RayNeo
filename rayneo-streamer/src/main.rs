//! rayneo-streamer — main entry point

use std::sync::Arc;

use anyhow::Result;
use tracing::info;
use tracing_subscriber::EnvFilter;

use rayneo_server::{
    CaptureConfig, CapturePipeline, Encoder,
    SessionManager, SignalingServer, VirtualDisplay,
};
use rayneo_server::session::StreamConfig;
use rayneo_server::stream::FrameStream;
use rayneo_xr_runtime::PoseBuffer;

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("info".parse()?))
        .init();

    info!("RayNeo XR Streaming Server starting");

    // 1. Virtual display — apps run here, we stream this
    let vdisp = VirtualDisplay::start(2, 1920, 1080).await?;
    info!("virtual display on {}", vdisp.display);

    // 2. Session config
    let stream_config = StreamConfig::default(); // 1920x1080 @ 120fps H264
    let sessions = Arc::new(SessionManager::new(stream_config.clone()));
    let poses = PoseBuffer::new();
    let frame_stream = FrameStream::new(16);

    // 3. Capture + encode pipeline (ximagesrc → x264enc → RTP → FrameStream)
    let capture_config = CaptureConfig {
        display: vdisp.display.clone(),
        width: stream_config.width,
        height: stream_config.height,
        fps: stream_config.fps,
        bitrate_kbps: 20_000,
        encoder: Encoder::X264,
        rtp_port: 5004,
    };
    CapturePipeline::new(capture_config, frame_stream.clone()).start();

    // 4. Launch a browser on the virtual display so there's something to see
    //    (chromium in kiosk mode — replace with whatever you want to run)
    if let Ok(_) = vdisp.launch_app("chromium-browser", &[
        "--no-sandbox",
        "--disable-gpu",
        "--window-size=1920,1080",
        "https://www.youtube.com",
    ]) {
        info!("launched Chromium on virtual display");
    } else {
        info!("chromium not found — desktop is blank (launch apps manually with DISPLAY=:2)");
    }

    // 5. Signaling server — phones connect here
    info!("signaling server on :7777");
    SignalingServer::new(Arc::clone(&sessions), frame_stream.clone(), 7777)
        .run()
        .await?;

    Ok(())
}
