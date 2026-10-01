#!/usr/bin/env bash
# Run once inside a fresh Ubuntu 24.04 Desktop install (VM or laptop).
# Idempotent: safe to re-run. Installs a dev toolchain and clones this repo.
set -euo pipefail

sudo apt update
sudo apt install -y \
  build-essential git curl wget vim openssh-server \
  openjdk-21-jdk maven clang cmake meson ninja-build pkg-config \
  vulkan-tools mesa-utils wayland-utils wayland-protocols \
  libwayland-dev libwlroots-dev libxkbcommon-dev libinput-dev libdrm-dev \
  weston sway \
  htop tmux \
  open-vm-tools-desktop     # no-op on bare metal; gives VMware clipboard/resize

sudo systemctl enable --now ssh

# Where experiments live. Everything custom goes in this repo so it is reproducible.
mkdir -p ~/src
[ -d ~/src/vexelray-os ] || git clone https://github.com/sibarum/vexelray-os ~/src/vexelray-os 2>/dev/null \
  || echo "clone failed (repo not pushed yet?): copy it in via shared folder or scp"

echo
echo "Session type: ${XDG_SESSION_TYPE:-unknown}   (want: wayland)"
echo "GPU:"; lspci | grep -iE 'vga|3d' || true
vulkaninfo --summary 2>/dev/null | grep -E 'deviceName|deviceType' || echo "no Vulkan device"
~/src/vexelray-os/playground/jdk.sh 25 || echo "JDK upgrade failed; rerun playground/jdk.sh"
echo "Done. Take a VMware snapshot now: 'baseline'."
