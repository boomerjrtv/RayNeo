//! Server-side compositor.
//!
//! Responsibilities:
//!   1. Maintain a virtual display / framebuffer for apps to render into.
//!   2. Accept submitted frames from OpenXR apps.
//!   3. Apply reprojection using the latest head pose from the phone.
//!   4. Hand composited frames to the stream encoder.
//!
//! Initially this is a passthrough: no 3D reprojection, just route
//! the desktop framebuffer to the stream.  Reprojection gets added once
//! the full OpenXR runtime layer is wired in.

use anyhow::Result;
use bytes::Bytes;
use tracing::info;

use crate::pose::PoseBuffer;

pub struct Compositor {
    width: u32,
    height: u32,
    poses: PoseBuffer,
    /// Called with each composited RGBA frame.
    on_frame: Box<dyn Fn(Bytes) + Send + Sync>,
}

impl Compositor {
    pub fn new(
        width: u32,
        height: u32,
        poses: PoseBuffer,
        on_frame: impl Fn(Bytes) + Send + Sync + 'static,
    ) -> Self {
        Self {
            width,
            height,
            poses,
            on_frame: Box::new(on_frame),
        }
    }

    /// Submit a raw RGBA frame from the screen capture / app renderer.
    pub fn submit_frame(&self, rgba: &[u8]) {
        // Phase 1: passthrough — no reprojection.
        // Phase 2: use self.poses.latest() to apply ATW (asynchronous timewarp).
        let frame = Bytes::copy_from_slice(rgba);
        (self.on_frame)(frame);
    }

    pub fn width(&self) -> u32 { self.width }
    pub fn height(&self) -> u32 { self.height }
}
