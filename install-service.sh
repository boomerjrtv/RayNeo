#!/usr/bin/env bash
# Run this on lifelog-server to install and start the rayneo systemd service
# Usage: bash install-service.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "==> Installing rayneo-streamer systemd service..."
sudo cp "$SCRIPT_DIR/rayneo-streamer.service" /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable rayneo-streamer
sudo systemctl restart rayneo-streamer

echo ""
echo "==> Service status:"
systemctl status rayneo-streamer --no-pager

echo ""
echo "==> Live logs (Ctrl+C to exit):"
journalctl -u rayneo-streamer -f
