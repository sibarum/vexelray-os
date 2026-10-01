#!/usr/bin/env bash
# Convert the raw disk image to VMDK for VMware Workstation.
set -euo pipefail
cd "$(dirname "$0")/.."
qemu-img convert -f raw -O vmdk -o subformat=streamOptimized mkosi.output/vexelray.raw mkosi.output/vexelray.vmdk
echo "wrote mkosi.output/vexelray.vmdk (new VMware VM: Ubuntu 64-bit, UEFI firmware, 3D acceleration on)"
