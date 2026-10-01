#!/usr/bin/env bash
# Build the image. Run inside WSL2 Ubuntu (or any Linux). Usage: scripts/build.sh [profiles]
# Default profiles: vm,dev. For the laptop: scripts/build.sh nvidia,dev
set -euo pipefail
cd "$(dirname "$0")/.."
PROFILES="${1:-vm,dev}"
command -v mkosi >/dev/null || { echo "install mkosi: pipx install git+https://github.com/systemd/mkosi.git"; exit 1; }
sudo mkosi --force --profile "$PROFILES" build
