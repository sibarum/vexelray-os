# vexelray-os

Custom Wayland desktop OS built on Ubuntu 24.04. See [docs/design.md](docs/design.md).

**Start here: [playground/](playground/README.md)**, a stock Ubuntu Desktop VM to experiment in. The mkosi image build below is the later, reproducible stage.

## Build (in WSL2 Ubuntu or any Linux)
```bash
sudo apt install qemu-utils qemu-system-x86 ovmf systemd-container pipx
pipx install git+https://github.com/systemd/mkosi.git
scripts/build.sh vm,dev        # VMware / VM image
scripts/build.sh nvidia,dev    # laptop image
```

## Run
- VMware: `scripts/to-vmdk.sh`, then create a VM (Ubuntu 64-bit, UEFI, 3D acceleration on) using the VMDK.
- QEMU: `scripts/run-qemu.sh`
- Verify inside the guest: `tests/smoke.sh`
