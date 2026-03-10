use glam::{Quat, Vec3};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, RwLock};

/// A head pose in world space.
#[derive(Debug, Clone, Copy)]
pub struct Pose {
    pub position: Vec3,
    pub orientation: Quat,
    pub timestamp_us: u64,
}

impl Default for Pose {
    fn default() -> Self {
        Self {
            position: Vec3::ZERO,
            orientation: Quat::IDENTITY,
            timestamp_us: 0,
        }
    }
}

/// Thread-safe ring buffer of recent poses for reprojection.
#[derive(Clone)]
pub struct PoseBuffer {
    inner: Arc<RwLock<[Pose; 16]>>,
    head: Arc<RwLock<usize>>,
}

impl PoseBuffer {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(RwLock::new([Pose::default(); 16])),
            head: Arc::new(RwLock::new(0)),
        }
    }

    pub fn push(&self, pose: Pose) {
        let mut buf = self.inner.write().unwrap();
        let mut head = self.head.write().unwrap();
        *head = (*head + 1) % 16;
        buf[*head] = pose;
    }

    /// Most recent pose.
    pub fn latest(&self) -> Pose {
        let buf = self.inner.read().unwrap();
        let head = self.head.read().unwrap();
        buf[*head]
    }
}

impl Default for PoseBuffer {
    fn default() -> Self {
        Self::new()
    }
}
