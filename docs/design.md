# vexelray-os design

Status: **design**. Revised 2026-10-23. The first draft planned a Wayland compositor on wlroots; this replaces it. What
it decided about the base system (Ubuntu, mkosi, stock kernel and drivers) still stands and is kept below. Why each
part is the way it is, and what it was chosen over, is in [decisions.md](decisions.md); this document says what the
design *is*.

## Goal

An operating system for proprietary applications, built around a compositor that takes **shadelets and streams of
per-instance data** instead of pixels, composes SPIR-V procedurally at run time, and retains the whole desktop as
one composite. Every program is drawn as a VexelRay application draws itself: SDF primitives through one family of
ubershaders, with any built-in or custom look, stitched together across processes.

Wayland and X11 programs are an afterthought, served by a bridge, never the model the system is built around.

This is the **reference implementation** of that idea. A finding that some part of it is wrong is a result, and is
recorded as carefully as one that it works.

## The base system (unchanged)

| Area | Decision | Why |
|---|---|---|
| Base | Ubuntu 24.04 LTS (noble) | Driver packaging (signed NVIDIA modules) is already solved |
| Image builder | mkosi, declarative, profiles `vm` / `nvidia` / `dev` | Rebuildable from scratch, outputs raw disk |
| Kernel / init | Stock Ubuntu kernel, systemd | Non-goal to replace; driver support is the hard part |
| Updates | Plain apt from our own repo (v1); atomic updates maybe v2 | Keep v1 simple |

GPU tiers: **A** is the NVIDIA laptop on bare metal (proprietary driver, real Vulkan; profile `nvidia`), **B** is
VMware (Mesa SVGA3D or lavapipe; profile `vm`). NVIDIA laptops are usually hybrid, so buffers cross GPUs; that needs
an early spike whatever the compositor is built on.

Non-goals: own kernel, own init, own package manager, own driver stack.

---

## 1. The rules

**Nothing re-implemented.** The stack's own rule (vexelray-gui architecture §0), raised one level: the compositor
draws through `vexelray-engine`, takes input through tactroller, moves messages on atchung and elektro-Q, encodes
them with intrabuf, times everything with Kronometer and composes shaders through SupirVast. Where one of those
cannot do what the compositor needs, the gap is closed **in that library** and listed in §11. A shim in this repo is
the wrong answer by default.

**Nothing can overload the compositor.** Every input a program can send has a bound the compositor enforces, and
the bound is a property of the protocol, not of the program's good behaviour. A program that exceeds it is the one
that suffers.

**The compositor talks to a program only when it must.** A still desktop exchanges no messages. Every wake a program
receives is the operating system saying it is mandatory, and says why.

**The compositor never waits on a program.** Anything the desktop needs to know *now* the program has declared in
advance (§6). A program that is slow, late or dead costs its own window and nothing else.

## 2. What a program sends: shadelets and instances

A program's window is one or more **layers**. A layer is:

- a **composition**: the shadelets its instances may use, declared as `core` IR (§7);
- an **instance set**: SDF primitives (a rounded box, a glyph run, an icon, an image quad), each a fixed header
  (instance id, look, z, bounds, offset to its data) and the per-instance fields its look declares. These are the
  `@PerInstance` fields of shadelet `@Style` records (`vexelray-shadelets`), not fat vertices; the compositor builds
  each quad in its vertex shader;
- an **image table**: the textures its instances sample (§2.2).

The program changes a layer by sending **batches** (§3). The compositor applies them to its retained copy of the
instance set and draws from that. There is no per-program pixel buffer anywhere (§4).

This keeps vexelray-gui's own model intact inside the program: the tree is retained, mutated by messages, laid out
and emitted on the GUI thread. What changes is where the emit lands. `TreeRenderer` emits instances into the batch
being built for the next frame, rather than vertices into a `Canvas` it presents itself.

### 2.1 Retained, immediate on demand

- **Retained by default.** The compositor keeps every layer's instance data indefinitely. A still program costs
  nothing; moving its window is a transform, with no message to the program.
- **Immediate inside the program.** The program's own frame loop is unchanged and still render-on-demand: it builds
  a batch when its state changed and sends nothing when it did not.
- **Multithreaded by construction.** Each program pays for its own layout and emit on its own threads, in its own
  process. The compositor's cost per frame is bounded by the damage it draws, not by the number of programs.

### 2.2 Images

Instances that sample name an image in their layer's image table. Images enter the table three ways:

- **Uploaded once:** icon sheets and `GuiApp.texture(...)` pixels, written into shared memory and copied to device
  memory by the compositor. Immutable once uploaded, exactly as `GuiApp.texture` is today (vexelray-gui §6.9).
- **Shared:** system font atlases belong to the compositor and every program refers to them. A font only one program
  ships is uploaded like any other image. Atlases carry **mip levels** for text drawn smaller than they were baked
  for (thumbnails, zoomed-out contexts, §5), where an MSDF's distance range falls below a few screen pixels and glyphs
  shimmer. Averaging an MSDF's three channels destroys the corners they exist to keep, so the coarse levels are a
  single-channel distance field, and coverage at the smallest. The shader picks the level from `pixelsPerUnit`,
  which the shadelet hosts already take per instance.
- **Rendered by the program:** the escape hatch for full graphics. A program that needs its own pipeline (a marched
  scene, a game, a simulation, video) renders into an exportable `VkImage` with a timeline semaphore exported beside
  it, exactly as a vexelray-gui viewport renders into a target and shows it with `Node.image(...)`. It is still an
  instance (a box that samples), so its corners, border, clip, opacity and transforms apply. These images are the
  one place a program holds pixels.

### 2.3 Three rates of change

Most of what changes on a desktop is not structure. A program does not rebuild anything to animate, and a rebuild
is reserved for what is added, removed or replaced on screen. Three tiers, from cheapest:

| Tier | Owned by | Changes | Rate | Wakes the program |
|---|---|---|---|---|
| **Instance animation** | the compositor | per-instance fields only, never layout | any | no |
| **Choreographer** | the program's GUI thread | per-instance fields computed by the layout engine | per frame, while live | yes |
| **Structure** | the program | instances added, removed, replaced | about once a second | yes |

**Instance animations** are the CSS rule, not the script callback. A program declares them on its instances: a
trigger (hover, press, focus, a presentation context, a time), the fields they touch, and a curve, either built-in or
`core` IR. The compositor runs them, sampled at the predicted present time of each frame (§3.1), with no message to
the program. Hover and press therefore cost no round trip.

An instance animation **never writes a field the program writes.** It writes an overlay on the retained base value:
an offset, a multiplier, or a blend toward a declared target. This is vexelray-gui §7's rule for its transform layer
(a property belongs there only if it has an identity value that can be restored without being told) carried across
a process. So a batch that changes a base value mid-animation composes with it rather than fighting it, removing the
animation restores the base exactly, and an interruption reverses from the current value under vexelray-gui's
settle-first rule. The program hears about the trigger only if it declared that it cares.

The **choreographer** is the program's GUI thread when something that moves layout animates: a panel opening, a list
re-flowing, a window being resized by hand. It runs the layout engine each frame over only the part of the tree the
animation can move, and emits the changed instance fields as that frame's batch. Nothing is destroyed or rebuilt;
instance ids derive from vexelray-gui's node ids, which are already stable for life.

When the animation's inputs are predictable (Kronometer's horizon says how far ahead), the choreographer computes the
whole sequence up front and sends it as per-field curves stamped with frame times, and the compositor plays it as an
instance animation. The choreographer runs per frame only while an input is live.

The name is vexelray-gui's: its architecture §7 plans "semantic-transaction choreography" (a FLIP diff on stable
ids, ghosts moving between places) as a later layer, and lists choreographer interruption semantics as open in §13.
That is this layer's job, so this is where it is built, and its open question is now this design's too.

**Structure** is a tab changing, a field being disabled, a row arriving. Only the affected subtree is rebuilt.

### 2.4 Scrolling

Scrolling is the exception to "structure about once a second": a long list or file adds and removes rows at frame
rate. It stays in budget in three ways.

- **A margin.** A scroll is an instance animation, an offset of a clipped container run by the compositor, over
  content materialised beyond the visible edge, more of it in the direction of travel. Small local scrolls never
  reach the choreographer; rows arrive in chunks as the margin runs low, not one per frame.
- **Detail by velocity.** A fast scroll is not expensive on the GPU: MSDF text is a sample and a median per pixel, and
  scrolling does not change glyph size. It is expensive in the program, which must break lines, position glyphs and
  send an instance per glyph faster than anyone can read them. The compositor runs the scroll, so it knows the
  velocity and puts it in the wake: *materialise this range at this detail*. Above a threshold the program sends rows
  as one rounded box per word or line, shaded to approximate ink coverage, with no glyph instances; for fixed-height
  rows this needs almost no layout. As the scroll slows, visible rows are upgraded in chunks, through a box-to-box
  morph rather than a pop.
- **A fling is predictable.** A fling is a decay curve, so the compositor knows where it lands when the finger lifts,
  and wakes the program with the landing range at once. The rows it stops on are at full detail before they
  arrive. A dragged scrollbar thumb is live input and has only detail by velocity.

## 3. The frame protocol

### 3.1 Wake and TTF

The compositor sends a program a **wake** when the program must act: input for it, a reveal or resize it must lay
out for, a presentation context it is asked to render (§5), a frame it asked to be woken for. A wake carries its
reason, the reason's parameters, and a **TTF** (time-to-frame): *the frame this program will make it into if it
starts now*, as a frame number and that frame's predicted present time. The prediction is the compositor's own
extrapolation and is dynamic.

What the program does with it:

- **Answers.** Either one batch, or an acknowledgement that there is nothing to change. Either way its retained
  layers stand. A wake that is not answered is the definition of *not responding* (§8).
- **Samples at the TTF.** The program's Kronometer `dynamic()` rate is driven by the TTF, and the present time it
  carries is `now` for sampling. Motion therefore lands on screen where its curve says it should, not a frame early.
- **May stay awake.** A program may ask to be woken every frame (to v-sync, even while minimised, where it has a
  reason to) or keep schedules of its own in the background. The compositor decides wakes; it never forbids work.

A program that has not been woken and has nothing scheduled hears nothing. There is no roll call for a still desktop.

### 3.2 One batch per frame

A **batch** is all of a program's changes for one frame: instance inserts, removals and field changes across its
layers, image table changes, declared-state updates (§6). It is atomic, and **only one is accepted per frame**. A
second batch for the same frame is ignored. That is the overload bound: the compositor's work per program per frame
is one batch, whatever the program sends.

Ignored is quiet to the program but never invisible: every ignored batch is counted on the compositor's `Probe`. A
program that loses state this way is a program not tracking its state, and Kronometer is how a program tracks it.

The **deadline** for a frame is the TTF's present time minus what the compositor estimates it will cost to draw that
program's damage. A heavy layer gets an earlier deadline than a light one. A batch that misses its deadline lands in
the next frame: two frames late instead of the baseline one, never lost.

### 3.3 Apply, then rasterize

A batch is handled in two steps with different costs:

1. **Apply**, on arrival, in order: the delta is written into the layer's retained instance data. Small, always done.
2. **Rasterize**: the regions the batch damaged are drawn into the composite. This is the expensive step, and it
   starts as soon as the batch is applied rather than waiting for a frame boundary, so the GPU's work is spread
   across the frame and by the deadline most of it is done.

Rasterizing a damaged region draws **every** instance intersecting it, from every window, in z order (§4.1). Blending
cannot be reordered, so a region is redrawn whole, from retained data, including the instances of windows that never
learn it happened. Batches arriving close together are merged into one submission. The render thread records the
apply and the draw into one command stream, so a pipeline barrier is the only synchronisation between programs.

### 3.4 A frame predicted to miss

The moment the compositor predicts it will not finish the next frame:

1. **Cut off.** It stops submitting raster work for this frame and records the copy of the composite to the
   swapchain. Queue order guarantees the copy sees every draw submitted before the cut-off and none after: every
   region is whole, each from its own frame. (Consistency across windows was never promised; a batch is atomic for
   its own layer.)
2. **Re-predict.** It sends an updated TTF to every program involved: woken for this frame and not yet answered, or
   answered and not yet drawn. Updates are sent only when the predicted **frame number** changes, never when the time
   estimate merely moves, so a predictor wavering at the threshold produces no storm.
3. **Carry the damage.** Batches that missed are already applied; their damage joins the next frame's and is drawn
   once. A program that receives the updated TTF may send a fresh batch sampled for the new present time. It applies
   over the undrawn one field by field, so only the newer values reach the screen.

The desktop's frame rate does not drop. Only the work that could not finish slips.

## 4. The composite

**The composite is the only retained image.** No program has a buffer of its own window, and neither does the
compositor. Moving or resizing a window damages the union of where it was and where it is (its shadow included), and
that region is rasterized again from the retained instances of whatever is there, without the programs underneath
knowing.

- **Instance data is retained for occluded parts; pixels are not.** Instance data is small. A reveal is a raster from
  data already held, never a wake.
- **Occlusion is conservative.** An instance is occluded only under opaque interiors. Anti-aliased rims and shadows
  never occlude.
- **The swapchain is downstream.** The composite is an image of its own; each swapchain image is brought up to date
  from it by its own damage history (its age), and `VK_KHR_incremental_present` passes the damage to the display
  where the driver supports it.
- **VRAM.** A program costs its instance data and its images. On Wayland the same window costs two or three buffers
  of its pixels in the program, before the compositor's copy (about 33 MB per buffer at 4K).

### 4.1 Order

Depth testing alone cannot order an SDF desktop: every rounded corner, glyph and shadow is translucent at its edge,
and a depth-tested translucent pixel hides what is behind it. Instead:

- **Instances are drawn in z order within one draw.** Vulkan blends in primitive order inside a draw, so a sorted
  instance range blends correctly with no depth buffer. The order is retained and re-sorted only where it changed;
  each window's range is contiguous, so restacking windows reorders ranges, not instances.
- **Depth is an optimisation.** Opaque interiors are drawn first, front to back, writing depth, so hidden pixels are
  rejected early; then rims, in order. The SDF says exactly where each primitive's interior ends.
- **Z is a boundary.** The compositor assigns each window a z range, and a program's z values are relative to it. No
  program can draw itself above another window, the shell or the lock screen.

The aim is one draw per frame for the whole desktop. That needs one pipeline for every live composition (§7) and
bindless images. The fallback is one draw per composition generation.

### 4.2 Wake from hibernate, and from a crash

The desktop's whole state is the composite, the retained instance data, the compositions and the pipeline cache. The
compositor can present the exact last frame as soon as the display is up, before any program is scheduled, and
redraw anything after that without asking a program. The same property lets the compositor itself restart without
losing the desktop. Program-rendered images (§2.2) die with the device and show a placeholder until their program
draws again.

## 5. Presentation contexts

The compositor can show a window other than as itself: scaled into a thumbnail, arranged for a drag, zoomed. Two
kinds of request:

- **Transform**, the compositor's alone, with no wake. Instances are resolution independent, and the shadelet hosts
  already take screen pixels per canvas unit (`pixelsPerUnit`) as an input, which is how the panel host keeps MSDF
  text sharp at any distance. A thumbnail is the retained instances under a transform, crisp, not a scaled texture.
- **Re-present**, a wake with resolution, DPI and context as parameters, when the program must *lay out*
  differently: reflow at another size, drop detail when small, show drop affordances.

A re-presented rendering is a **separate layer** for that context. Leaving the context switches back with no wake,
and a program that dies mid-context leaves its normal layer intact.

Example: a click-and-drag a program has declared global. The compositor may coalesce every window that declared the
drag context, waking them together with one TTF. Each draws its retained instances, transformed, until its
re-presented layer arrives, then switches to it. Nothing is blank and nothing waits on the slowest program.

## 6. Declared state

The compositor never asks a program a question and waits for the answer. Everything the desktop needs to know at
once, the program declares in advance, as state carried in its batches; the compositor resolves against the latest
version. This is vexelray-gui's resolved decision 9 (claims, not `preventDefault`) applied to the whole desktop, for
the same reason: an asynchronous bus cannot give a synchronous answer, and a desktop that waits on a program freezes
with it.

| Question | Declared as | Already exists as |
|---|---|---|
| Which part of the window moves, resizes, closes it? | hit regions | `HitRegions`, `Decorations` in `vexelray-os-api` |
| Does this region accept this drop, with what effect? | drop zones: rect, formats, effect | new; tactroller's `DropHandler.effectFor` design already forbids I/O there |
| Which chords does the program take before the desktop? | claims at scope | `gui.claim(...)`, `ClaimScope` |
| Which wakes and presentation contexts does it handle? | capabilities, at connect | new |
| What is in the window, for automation and accessibility? | the layout read-model | `LayoutSnapshot` |

What a program has not declared gets the safe default: no drop, no claim, no wake for that reason, transform only.

## 7. Shadelets across the desktop

Shadelets are built (`vexelray-canvas`'s `dev.vexelray.canvas.shadelet`; `supirvast/docs/shadelets.md` is the design):
roles (source → modifier → painter → composite → clip) composed into one ubershader per application. A `Composition`
already reads everything a look says from one float buffer: a retained look table (`LookTable`, written when a theme
changes, never per frame) and per-frame element records of `[look | per-instance floats]`. That record is most of an
instance already (§11, G1). Here a program declares its composition at connect, as `core`
IR, and the compositor composes the pipeline at run time: SPIR-V procedural and parametric, built off the render
thread, keyed by the composition's hash and cached on disk. A program whose composition is still building draws with
built-in looks until it is ready.

A single draw for the desktop needs one pipeline holding every live composition: a fixed instance header naming the
look, and each look's fields in one bindless storage buffer. A new look rebuilds that pipeline off the render thread.
Neighbouring pixels of different looks take different branches; they are usually the same primitive, so the cost is
expected to be small, and M5 measures it.

The compositor runs code a program wrote on the GPU every window shares, and a shader that never terminates is a
lost device for the whole desktop. Because a shadelet arrives as `core` IR and not as SPIR-V, the compositor can
check it before lowering it (§11, S1). Until that check exists, only the compositor's own shadelets are accepted.

## 8. The title bar is the task manager

A window's title bar is retained instances like the rest of it, and its controls are declared hit regions the
compositor resolves itself. So it is drawn, moved and resized while the program is busy, hung or dead. A program
that has not answered a wake by its deadline, and the frames after, is **not responding**, measured on the
compositor's own clock with no timeout guessed; the title bar says so and offers to end it.

## 8.1 Security: user consent

The system runs third-party proprietary programs, so every defence in this design is against an adversary, not only
a bug. Security is by **user consent**: running for the first time, reading the filesystem, making a web request,
opening a socket or a port each need the user's approval. A program may bundle the permissions it needs into one
consent. Consent is remembered and can be revoked. It is deliberately a little annoying for a program that has not
been approved, and never a hard stop.

**The program chooses the scope; the system keeps it honest.** A request can be as narrow or as broad as its program
wants: one file it was handed, a folder, the user's home, everything the user can reach without `sudo`, or root.
Trust is between the user and the program. The system does not rank requests or decide which are reasonable. It makes
sure the interaction is honest:

- **The system describes the scope, never the program.** A request is structured (a file reference, a path, "all of
  the user's access", root), and the prompt states what it resolves to in the system's own words: "your entire home
  folder (14,203 files)", not the program's "your settings". A program may give a reason; it is shown as the
  program's words, apart from the system's.
- **What is granted is exactly what was shown.** Consent is to the resolved structure. Nothing widens later; a
  folder and the folder's current contents are different scopes, and the prompt says which.
- **Who is asking is stated truthfully:** signed by whom, or unsigned and identified by its content hash.
- **Broad scopes look broad.** One file, a folder, all of the user's access and root are visibly different prompts,
  not one prompt with different words. Root asks for the user's credential, as `sudo` does, because it is the same
  act.
- **Honest afterwards, too.** Each program's grants (what, when, whether used) can be seen and revoked in one place,
  reached from its title bar (§8).

**The kernel enforces it.** Programs are native code: a native-image binary can make any system call, and Java's
`SecurityManager` no longer exists (removed in JDK 24), so nothing inside a program can enforce anything. Every
program starts in a sandbox (namespaces, Landlock, seccomp, a cgroup for its quotas) with its own directory and
nothing else: no wider filesystem, no network. Programs are started only by the system's launcher, from the shell as
from a terminal, so there is no way to run one outside the sandbox.

**A grant is a file descriptor.** A sandbox can be tightened but not loosened while its program runs, so access is
not opened by changing it. A **broker** outside the sandbox asks the user and hands the program an fd: an opened file,
a directory, a connected socket. A program with network permission still has no network of its own; it has sockets
the broker opened for it.

Not every scope is an fd. "All of the user's access" is a launch without a sandbox, and root is a privileged process;
those are launch modes, applied by the launcher. Remembered grants of every kind are applied by the launcher when the
program starts (Landlock path rules, launch mode), before it runs, with no prompt and no broker round trip.

**Where the kernel cannot express a policy, BPF before modules.** Landlock filters TCP only by port; per-destination
network policy is cgroup socket-address BPF hooks on the program's cgroup. Policy Landlock has no words for is
BPF-LSM, which Ubuntu's kernel is built with but which must be named in the `lsm=` boot parameter. Both keep the
"no own kernel" non-goal intact. A kernel module would reopen it (and needs signing under secure boot). Some things
neither reaches: Linux has no `revoke()`, so revocation stays as below.

**An action is consent.** Where the user has already expressed intent, no prompt is shown: a file picked in the
system's chooser is granted, a paste grants the clipboard once, a drop grants its payload. Prompts are for what no
gesture expresses (broad filesystem access, the network, ports, running in the background), which is what keeps
them rare enough to be read.

**The compositor is the trusted path.** Consent is asked by the compositor itself, in a z range above every program
(§4.1); programs cannot synthesise input, which comes only from devices. A fresh consent prompt ignores input for a
few hundred milliseconds, against clickjacking and against the click-through habit, and carries a mark only the
compositor can draw, so a program cannot train its users on a prompt of its own.

**The compositor's own permissions** go through the same consent: `GLOBAL` key claims (a keylogger otherwise),
images of another window's content (screen capture, §2.2), the clipboard without a paste gesture, and staying awake in
the background.

**What a consent is bound to.** An unsigned program's consents bind to its content hash, so an update asks again;
that is the honest cost of unsigned code. A signature is optional and gives **identity across updates**: the same key
is the same program, and its consents carry over. Skipping consent altogether is a separate and stronger step: the
key must be in a trust store the system or the user controls, and only for permissions the signed manifest declares.

**Revocation does not affect a running process.** A revoked program is granted nothing new, and the launcher
leaves the grant out the next time it starts; what the running process already holds, it keeps. This is decided,
not a gap waiting for a fix: Linux has no `revoke()`, and pretending otherwise would make the interaction dishonest.
The revocation view says so, and offers ending the program for a user who wants the access gone now.

## 9. Processes and threads

```
 program                                              compositor
 ──────────────────────────────────                   ─────────────────────────────────────────────
 Kronometer, driven by the TTF                        predictor: frame cost, TTFs, deadlines
 Gui: drain → reconcile → layout → emit               ingest: one receive thread per program
   └ batch ─────────────► shared memory ─────────────►  (elektro-Q), applying batches in order
 wake + TTF ◄───────────── uds ──────────────────────   render thread, the only one touching Vulkan:
 input: a tactroller backend fed by the compositor      apply · rasterize damage · cut off · copy
                                                      offload: shader composition, image import
```

- **The compositor is a VexelRay application.** It composes a `RenderPipeline` with one technique,
  `CompositorTechnique`, through the `RenderTechnique` SPI, and keeps `vexelray-engine`'s rule that Vulkan, the
  window and present stay on one thread.
- **Nested first.** Its `Target` is a window on the desktop it runs under (X11 through `vexelray-os-linux`, or
  Windows) until the protocol is proven. Running on a display is a new `Target` kind (§11), not a second compositor.
- **A program connects through its platform.** A new platform module, `vexelray-os-vexel`, makes `createWindow` a
  protocol request. An application built on vexelray-framework runs here with **no source change**; that is the
  acceptance test for the seam.
- **Input.** The compositor owns the devices: tactroller's Linux backend on its own window while nested, libinput on
  a display. It hit-tests windows, resolves desktop-wide claims, and forwards each event in window coordinates. In
  the program the events arrive through a tactroller backend fed by the compositor, so vexelray-gui's rule (every
  device event flows through tactroller) holds unchanged.

## 10. Wayland and X11, later

A bridge process that is a Wayland server to legacy clients and a program to the compositor: each `wl_surface` is a
layer of one image instance over the client's buffer (§2.2). XWayland sits behind it. Nothing in the protocol exists
for the bridge's sake.

## 11. Gaps, by the library they belong to

Each is closed in its own repo, with that repo's tests, before anything here depends on it.

| # | Gap | Repo | Shape |
|---|---|---|---|
| G1 | **Instanced SDF primitives** | vexelray (`-canvas`) | The look half is instance-shaped already: element records `[look \| per-instance floats]` in `Composition`'s table. Geometry, colour and clip still ride a fat vertex (`CanvasVertex`, 27 floats, six per primitive: 648 bytes). Move them into the element record and build the quad in the vertex shader from the instance index. |
| G2 | **An instance set as data** | vexelray (`-canvas`), vexelray-gui (`TreeRenderer`) | Emit into a caller-owned instance set with stable ids and deltas, not a per-frame array. `TreeRenderer` re-emits the whole tree every frame today because `Canvas` is immediate-mode; it becomes incremental, keyed by node id. This is what a batch carries. |
| G8 | **Scoped relayout** | vexelray-gui (`-core`) | `layoutDirty` re-runs the flex pass over the whole tree; the choreographer (§2.3) relayouts only what an animated length can move. |
| G3 | **Bindless images** | vexelray (`-vulkan`, `-canvas`) | Descriptor indexing, so an image is an index in the instance and `Canvas.runs()` no longer splits a draw. |
| G4 | **External memory** | vexelray (`-vulkan`, `-engine`) | Import host memory for shared batches, with a copy fallback (expect tier B to need it); import and export images and timeline semaphores by fd. |
| G5 | **A display target** | vexelray (`-core`, `-engine`) | `Target.Kind.DISPLAY` over `VK_KHR_display` / `VK_EXT_acquire_drm_display`, with DRM access from logind. |
| G6 | **Robust access** | vexelray (`-engine`) | `robustness2` on the compositor's device, so a bad index from a program draws nothing instead of reading out of bounds. |
| G7 | **Damage-scoped raster** | vexelray (`-engine`) | Draw a region of a retained target: scissor, cull by bounds, present by swapchain-image age, incremental present. |
| P1 | **A compositor platform** | vexelray (`-os`) | `vexelray-os-vexel`: `NativePlatform` and `NativeWindow` over the protocol. |
| P2 | **A batch presenter** | vexelray-gui (`-core`) | `GuiApp` ends a frame by sending its batch, answering the wake, rather than presenting; chosen by the platform. |
| K1 | **A rate driven by a predicted clock** | kronometer | A `dynamic()` domain stepped by TTF messages, whose `now` for a step is the predicted present time, re-targeted when an updated TTF arrives. |
| M1 | **File descriptors over a socket** | atchung (`elektroq-transport-uds`) | `SCM_RIGHTS` through Panama `sendmsg`/`recvmsg`; Java's `SocketChannel` cannot pass one. memfds, dma-bufs, eventfds and sync files all cross this way. |
| M2 | **A checked read at a trust boundary** | intrabuf | intrabuf assumes both ends are yours and calls a broken promise undefined behaviour. A compositor's peers are other programs: decoding must check every claim and fail the connection, never the compositor. |
| M3 | **A wire that outlives a build** | intrabuf, vexelray-gui | vexelray-gui §13.14 assumes the stack updates in unison. Programs and the compositor are built at different times, the trigger it named. Versioned with intrabuf's BACKWARD compatibility from the first message. |
| T1 | **A backend fed by a peer** | tactroller | An `InputBackend` whose state comes from the compositor's events, not a device. |
| T2 | **libinput** | tactroller (`-linux`) | A Panama libinput backend, through the fds logind hands out. |
| T3 | **One input channel per loss class** | tactroller (`-atchung`) | vexelray-gui §13 records that `"tactroller.input"` mixes droppable motion with keys under `DROP_OLDEST`. Across a process the stall that sheds keys is likelier, so this comes first. |
| C1 | **The launcher and sandbox** | vexelray-os (new) | Start every program in namespaces, Landlock, seccomp and a cgroup; the only way a program runs. |
| C2 | **The consent broker** | vexelray-os (new) | Ask, remember, revoke; hand out fds over M1. Consent records keyed by content hash or signing key. |
| C3 | **The trusted prompt** | vexelray-os (compositor) | Consent drawn by the compositor above every program, with an input delay and a mark no program can draw. |
| S1 | **A checked shadelet** | supirvast, vexelray (`CoreCheck`) | Admission control for `core` IR from a program: termination (bounded loops only), a cost bound, nothing it was not granted. |

## 12. Milestones

| | Milestone | What it proves |
|---|---|---|
| **M0** | Nested compositor. Two programs connect over UDS and send instance batches through shared memory (copy path); the compositor applies, rasterizes damage into a retained composite and presents. Wakes and TTFs from a first predictor. | §2–§4. Measured: frame time against window and instance count, batch size for a text-heavy window, wake-to-present latency, how often the predictor is wrong. |
| M1 | `vexelray-os-vexel` (P1, P2, K1): an unmodified vexelray-framework application (the template's) runs as a program. | §9's seam |
| M2 | Input through the compositor (T1, T3); hit regions, claims, the title bar kept alive (§8). | §6, §8 |
| M3 | Images: uploaded, shared fonts, then program-rendered (G3, G4, M1). A viewport-bearing application runs. | §2.2 |
| M4 | Presentation contexts and drag and drop between programs over declared drop zones. | §5, §6 |
| M5 | Compositions from programs, one pipeline for the desktop; S1. | §7 |
| M6 | On a display (G5, T2): the VMware VM first, then the laptop. Wake from hibernate. | that the nested phase hid nothing; §4.2 |
| M6.5 | Launcher, sandbox, broker and trusted prompt (C1–C3), with S1 and quotas. Gates the first third-party program. | §8.1 |
| M7 | Wayland bridge. | §10 |

Each milestone ends with its entries in [decisions.md](decisions.md), including the ones that failed.

## 13. Open questions

Not yet thought through. Each becomes a decision or a section here.

1. **The vocabulary of instance animations.** Which triggers, which overlay operations, and which curves are
   built in, before a program may send its own as `core` IR (which needs S1).
2. **Instance identity and structure.** Who allocates ids, how inserts and removals keep each window's range sorted,
   and how large a batch may be before the bound in §1 needs a second number (instances per batch, bytes per layer).
3. **Text.** Glyphs will be most of the instances on screen. Shaping and line breaking stay in the program (they are
   layout); what crosses is positioned glyph instances against shared atlases. §2.4 bounds the scrolling case; is a
   glyph run (one instance per run, glyphs in a buffer) better than one instance per glyph for everything else?
   Where are the velocity thresholds, and does the word-box level read as text or as noise?
4. **Several outputs.** One frame clock per output, with different rates, VRR and densities. Which TTF does a window
   spanning two outputs get, and at which DPI does it lay out?
5. **Resource quotas.** Instance memory, image memory and composition count per program, and what happens at the
   limit. Retaining everything indefinitely needs a ceiling.
6. **The request vocabulary.** Granularity is the program's choice (§8.1), but requests are structured, so the
   structure needs defining: which scopes exist (file, folder, folder-as-it-grows, destination, port, launch mode),
   how they compose into one bundled consent, and how each is described honestly. Which need BPF, and is any beyond
   BPF?
7. **Effects that need pixels.** Backdrop blur samples what is behind a window, which the composite has only until it
   is drawn over. A temporary target per frame, a retained one per effect, or no backdrop effects?
8. **Window management itself.** Stacking, tiling, workspaces, the shell: the policy this compositor applies has not
   been discussed at all.
9. **Clipboard and drop payloads.** Formats, and large payloads through memfds, between programs that need not both be
    awake.
10. **Persisted state.** Hibernate and crash recovery write the desktop's state to disk; that is the moment a wire
    format outlives a build (decisions D4).
