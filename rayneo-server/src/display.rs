//! Virtual display management.
//!
//! Starts Xvfb on :2 with a minimal Openbox window manager.
//! Apps launched with DISPLAY=:2 will appear in the XR stream.

use anyhow::{Context, Result};
use std::process::{Child, Command, Stdio};
use std::time::Duration;
use tokio::time::sleep;
use tracing::info;

pub struct VirtualDisplay {
    xvfb: Child,
    openbox: Child,
    pub display: String,
}

impl VirtualDisplay {
    /// Start a virtual display on the given number (e.g. 2 → ":2").
    pub async fn start(display_num: u32, width: u32, height: u32) -> Result<Self> {
        let disp = format!(":{display_num}");

        // Kill any existing Xvfb/X server on this display and clean up its files
        let _ = Command::new("pkill").args(["-f", &format!("Xvfb :{display_num}")]).status();
        let _ = Command::new("pkill").args(["-f", &format!("Xvfb .*:{display_num} ")]).status();
        tokio::time::sleep(Duration::from_millis(300)).await;
        let _ = std::fs::remove_file(format!("/tmp/.X{display_num}-lock"));
        let _ = std::fs::remove_file(format!("/tmp/.X11-unix/X{display_num}"));

        info!("starting Xvfb on {} at {}x{}", disp, width, height);
        let xvfb = Command::new("Xvfb")
            .args([
                disp.as_str(),
                "-screen", "0", &format!("{width}x{height}x24"),
                "-ac",
                "-noreset",
                "-maxclients", "512",
            ])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .context("failed to start Xvfb — is xvfb installed?")?;

        sleep(Duration::from_millis(800)).await;

        info!("starting Openbox on {}", disp);
        let openbox = Command::new("openbox")
            .env("DISPLAY", &disp)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .context("failed to start openbox — is openbox installed?")?;

        sleep(Duration::from_millis(400)).await;
        info!("virtual display ready on {}", disp);

        Ok(Self { xvfb, openbox, display: disp })
    }

    /// Launch an application on the virtual display.
    pub fn launch_app(&self, program: &str, args: &[&str]) -> Result<Child> {
        info!("launching {} on {}", program, self.display);
        Command::new(program)
            .args(args)
            .env("DISPLAY", &self.display)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .with_context(|| format!("failed to launch {program}"))
    }
}

impl Drop for VirtualDisplay {
    fn drop(&mut self) {
        let _ = self.openbox.kill();
        let _ = self.xvfb.kill();
    }
}
