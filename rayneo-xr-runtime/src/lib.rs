//! Minimal OpenXR session wrapper for the server-side compositor.
//!
//! This wraps the openxr crate and exposes the pieces we need:
//! - creating an XR instance (headless / "stream" system)
//! - receiving pose updates
//! - submitting rendered layers
//!
//! The idea: normal desktop apps think they're running on an OpenXR headset.
//! We intercept their frame submissions and route them to the WebRTC stream.

pub mod pose;
pub mod compositor;

pub use pose::{Pose, PoseBuffer};
pub use compositor::Compositor;
