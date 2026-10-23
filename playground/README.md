# Playground

Goal: a stable place to break things. Stock Ubuntu 26.04 LTS Desktop, then customize.

## 1. Create the VM (VMware Workstation)
1. Download the **Ubuntu 26.04 LTS Desktop** ISO from ubuntu.com/download/desktop (the main download button serves the latest LTS; check the filename says `26.04`).
2. New VM, Typical, point at the ISO, guest OS "Ubuntu 64-bit".
3. Disk 60 GB (single file, thin), 8 GB RAM, 4+ CPUs.
4. Before first boot: VM Settings, Display, enable **Accelerate 3D graphics**, 2 GB graphics memory.
5. Install Ubuntu (normal install, default options).
6. Log in on the **Ubuntu** (Wayland) session, which is the default.

## 2. Bootstrap
```bash
sudo apt install -y git && git clone https://github.com/sibarum/vexelray-os ~/src/vexelray-os
~/src/vexelray-os/playground/setup.sh
```
(If the repo is not pushed yet, drag the folder in or `scp` it.)

## 3. Snapshot
VMware, VM, Snapshot, Take Snapshot, name it `baseline`. Revert whenever an experiment bricks the desktop.

## 4. Work from Windows
- SSH: `ssh <user>@<vm-ip>` (`ip a` in the VM). Use VS Code Remote-SSH to edit in the VM.
- Keep the repo in git and push often. The VM is disposable; the repo is not.

## The laptop
Install the same ISO on the laptop, choose **Install third-party software** (pulls the NVIDIA driver), and run the same `setup.sh`. VMware's Vulkan is translated, so anything GPU-specific gets tested there.

## First experiments (all reversible)
- Run a second compositor nested inside your session: `weston` or `sway` open as a window.
- Swap sessions at the login screen (GDM gear icon): install `sway` and pick it.
- `WAYLAND_DEBUG=1 <app>` to watch the protocol; `wayland-info` lists compositor globals.
- Build a wlroots example (`tinywl`) and run it nested.
