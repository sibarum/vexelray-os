#!/usr/bin/env bash
# Quick local boot for iteration. Needs qemu-system-x86 + ovmf.
set -euo pipefail
cd "$(dirname "$0")/.."
exec qemu-system-x86_64 -enable-kvm -m 4G -smp 4 \
  -bios /usr/share/ovmf/OVMF.fd \
  -drive file=mkosi.output/vexelray.raw,format=raw \
  -device virtio-vga-gl -display gtk,gl=on \
  -nic user,hostfwd=tcp::2222-:22
