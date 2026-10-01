# vexelray-os design

## Goal
A personal Wayland desktop OS for a Java + native developer workstation. Primary target: a dedicated NVIDIA laptop (bare metal). Secondary target: VMware Workstation on Windows (paravirtual GPU).

## Decisions
| Area | Decision | Why |
|---|---|---|
| Base | Ubuntu 24.04 LTS (noble) | Driver packaging (signed NVIDIA modules) is already solved |
| Image builder | mkosi, declarative, profiles `vm` / `nvidia` / `dev` | Rebuildable from scratch, outputs raw disk |
| Kernel / init | Stock Ubuntu kernel, systemd | Non-goal to replace; driver support is the hard part |
| Desktop | Own Wayland compositor on wlroots (`compositor/`) | "Heavily modified" is cheaper to reach by owning the compositor than by patching KWin or Mutter |
| Updates | Plain apt from our own repo (v1); atomic updates maybe v2 | Keep v1 simple |

## GPU tiers
- **A (laptop, bare metal):** proprietary NVIDIA driver, real Vulkan and CUDA. Profile `nvidia`.
- **B (VMware):** Mesa + SVGA3D / lavapipe fallback. Vulkan is translated or software. Profile `vm`.
- Same image; the NVIDIA modules load only when NVIDIA hardware is present.
- NVIDIA laptops are usually hybrid (iGPU + dGPU). The compositor must handle multi-GPU buffer sharing; this is a known pain point with wlroots + NVIDIA and needs an early spike.

## Open design questions (the compositor is the real project)
1. Compositor language: C (wlroots native), Rust (smithay), or Java via bindings (fits the stack, but risky).
2. What "heavily modified" means concretely: tiling model, window/workspace paradigm, shell UI, input model.
3. Shell UI (bar, launcher, notifications): in-compositor or separate layer-shell clients.

## Non-goals
Own kernel, own init, own package manager, own driver stack.

## Milestones
- M0: scaffold, `mkosi build` produces a bootable image
- M1: boots in VMware with Mesa Vulkan (`tests/smoke.sh` passes)
- M2: Java + JNI toolchain verified in-image
- M3: NVIDIA on the laptop, stock Wayland compositor first (de-risk drivers before custom compositor)
- M4: custom compositor v0 on top of a working driver stack
- M5: installer, apt repo, public release
