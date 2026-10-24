# Decisions

The record of what was decided, what it was decided against, and the evidence. A decision that turns out wrong is
not deleted: it is marked **reversed**, with what reversed it, because "what not to do" is half of what this
reference implementation is for.

Each entry: the decision; the alternatives and why they lost; the evidence (a test, a measurement, or "reasoning
only" when that is all there is yet); and the **trigger** that would reopen it. Section numbers refer to
[design.md](design.md).

---

## D1 — Wayland is not the model (2026-10-23)

**Decided.** The compositor is not built on wlroots and does not speak Wayland natively. Wayland and X11 programs
reach it through a bridge (§10).

**Against.** wlroots, as in the first draft of design.md. It makes the pixel buffer the unit between a program and
the compositor, which rules out the design's goals by construction.

**Evidence.** Reasoning only.

**Trigger.** The bridge cannot carry a program the user needs at acceptable latency.

## D2 — Programs send shadelets and per-instance data (2026-10-23)

**Decided.** A layer is a shadelet composition, a retained instance set and an image table; programs change it with
batches (§2).

**Against.**
- *Pixels.* No shadelets, effects or node-level integration across programs; a GPU device in every program; a
  buffer of every window's pixels.
- *The node tree.* The compositor would pay every program's layout and emit on its own threads, and widget logic
  that runs on vexelray-gui's drain would cross a round trip.
- *The `Canvas` vertex batch.* Proposed and dropped the same day, before anything was built. Six fat vertices per
  primitive, against one instance whose fields are exactly a shadelet style's `@PerInstance` fields.

**Evidence.** Reasoning only. M0 measures instance volume for a text-heavy window and the frame time against
instance count.

*M0 spike, tier B on llvmpipe ([m0-spike.md](m0-spike.md)):* holds. An instance is 128 bytes against 648 as fat
vertices. A 780x620 editor is 1,060 instances, 136 KB retained against 1.93 MB for one buffer of its pixels; its first
batch is 134 KB. A keystroke's batch is 404 bytes and an animation frame's 1 KB, applied in under 0.05 ms. No
realistic phase missed a frame. The limit on llvmpipe is raster, about 1.4 ms per thousand glyph instances for the
whole screen, not volume. The trigger was not reached.

**Trigger.** Instance volume makes the copy path (tier B) miss frames for a realistic window, or a look turns out to
need data no instance can carry.

## D3 — Questions are declared, never asked (2026-10-23)

**Decided.** The compositor never waits on a program. Hit regions, drop zones, claims, capabilities and the
read-model are declared in advance and resolved against the latest version (§6).

**Against.** The drag-and-drop model of Windows, macOS and X11, where the system asks and blocks and the failure
mode is a hung source (tactroller `docs/dnd-design.md`).

**Evidence.** Follows vexelray-gui resolved decision 9.

**Trigger.** A desktop interaction whose answer cannot be known before the question is asked.

## D4 — The wire is versioned from its first message (2026-10-23)

**Decided.** The protocol uses intrabuf BACKWARD compatibility, and the compositor's decoders check every claim
(§11, M2 and M3).

**Against.** vexelray-gui §13.14's "the stack updates in unison". True inside one application, false between a
compositor and programs built at different times, which is the trigger that decision named.

**Evidence.** Reasoning only.

**Trigger.** None foreseen: this is the boundary of a system with independent peers. Persisting the desktop's state
(§4.2) adds a second reader, last month's compositor reading this month's file, and the same rule covers it.

## D5 — One batch per program per frame; later ones are ignored (2026-10-23)

**Decided.** A program sends all its changes for a frame as one atomic batch by its deadline. A second batch for
the same frame is ignored, quietly to the program and counted on the probe (§3.2).

**Against.**
- *Per-field first-value-wins.* Considered first. Tears related fields (an `x` from one update, a `y` from the next)
  and leaves the commit unit undefined.
- *Deferring the latest late value to the next frame,* proposed so that a program's final value could never be lost.
  Rejected: it moves the job of tracking state from the program, which has Kronometer for it, onto the compositor,
  and nothing should be able to make the compositor do more work by sending more. A program sampling on the
  compositor's frame clock sends one batch per frame by construction, so only a misbehaving program is ever ignored.
- *Latest-wins per frame* (atchung's `COALESCE_LATEST`). Free for shared-memory slots, but it makes a batch's
  arrival meaningless as a signal, and §3.3 starts rasterizing on arrival.

**Evidence.** *M0 spike:* ignored exactly the 23 duplicates a program sent on purpose, and nothing else across 833
batches. It also showed the compositor can provoke this rule itself, by naming a frame the program already answered;
D14 rules that out.

**Trigger.** A correctly timed program loses state to this rule, which would mean the TTF is not a clock a program
can rely on.

## D6 — The compositor decides every wake, and only wakes when it must (2026-10-23)

**Decided.** A wake is mandatory, carries a reason and a TTF, and is sent only when a program must act. A still
desktop exchanges nothing. A program may stay awake or keep its own schedules (§3.1).

**Against.** A roll call of every program every frame. 50 programs at 144 Hz is about 7,200 wakes a second on a still
desktop, and keeps every core out of deep sleep.

**Evidence.** *M0 spike:* a still desktop exchanged zero messages and cost 0.01 ms a frame; dragging a window
exchanged zero as well. Typing cost 0.4 messages a frame, an animating window 2.

**Trigger.** Missing a deadline proves common for programs woken only on demand, because the wake itself costs too
much of the frame.

## D7 — TTF: the frame you will make if you start now (2026-10-23)

**Decided.** A wake carries the frame number a program will land in if it starts at once, and that frame's predicted
present time, from the compositor's own extrapolation. The present time is `now` for the program's Kronometer.
Missing the deadline costs one frame (two late instead of one), never the batch (§3.1, §3.2).

**Against.** A fixed deadline per frame, the same for everyone. It ignores that drawing a heavy layer's damage costs
more, and gives the program no way to sample motion for when it will be seen.

**Evidence.** Reasoning only. M0 publishes how often each program misses, the cheapest test of whether the predictor
is honest.

*M0 spike:* 832 of 833 batches landed in their TTF frame, and the present time was predicted to within 0.1 to 0.3 ms
at p50 (5.3 ms at p95). Not a stress test: programs answered in under a millisecond against about 15 ms of slack.
Wake to present was two frames, almost all of it waiting for frame boundaries (wakes sent at a frame's end, raster at
the next frame's start), not the protocol.

**Trigger.** The predictor's error is larger than a frame often enough that the TTF misleads more than it helps.

## D8 — Rasterize on arrival; cut off when a miss is predicted (2026-10-23)

**Decided.** A batch is applied on arrival and its damage rasterized at once, not at a frame boundary. When the
compositor predicts it will not finish, it stops submitting, presents the composite as of that cut-off, sends
updated TTFs to the programs involved when the predicted frame number changes, and carries the remaining damage into
one raster for the next frame (§3.3, §3.4).

**Against.** One raster of the whole frame at a latch. All the GPU work lands at the end of the frame, and a miss
drops the whole desktop's frame rather than the work that could not finish.

**Evidence.** Reasoning only. *Not tested by the M0 spike*, which rasterizes once per frame at its start.

**Trigger.** Overlapping damage from programs answering at different times redraws the same regions so often that
one raster at a latch costs less.

## D9 — The composite is the only retained image (2026-10-23)

**Decided.** No program and no window has a pixel buffer. Instance data is retained for everything, occluded or not;
pixels are retained only as the one composite, and a reveal is a raster from retained data (§4).

**Against.** A buffer per window (Wayland, and every compositor that animates windows as textures). Two or three
buffers of each window's pixels, and window effects that scale a texture rather than redraw resolution-independent
shapes.

**Evidence.** *M0 spike:* window moves and reveals redrawn from retained instances with no program told, 167,000
damaged px a frame in 4.9 ms on llvmpipe. Damage-scoped raster needs culling as well as a scissor: one 220x18 rect
costs 0.56 ms over 500 instances and 4.19 ms over 16,000.

**Trigger.** An effect the desktop needs that can only be had from a window's pixels (§13, question 8, is the
nearest).

## D10 — Order by sorted instances, not by a depth buffer (2026-10-23)

**Decided.** Z is the order of instances within a draw; depth is only an early-rejection optimisation for opaque
interiors; the compositor assigns each window a z range (§4.1).

**Against.** Z as the depth mask alone, considered first. Every SDF primitive is translucent at its edge, and a
depth-tested translucent pixel hides what is behind it, leaving halos.

**Evidence.** Vulkan's guarantee that blending happens in primitive order within a draw.

**Trigger.** Keeping the order sorted costs more than an order-independent transparency method would.

## D11 — The title bar survives the program (2026-10-23)

**Decided.** The title bar is retained instances with declared controls, so it works while its program is hung or
dead, and *not responding* is a wake left unanswered on the compositor's clock (§8).

**Against.** A timeout on a request, which guesses, and decorations drawn by the program alone, which die with it.

**Evidence.** Reasoning only.

**Trigger.** None foreseen.

## D12 — Three rates of change: instance animation, choreographer, structure (2026-10-23)

**Decided.** The compositor runs declared instance animations that touch only per-instance fields, as overlays on
retained base values. The program's GUI thread, as the choreographer, animates layout by recomputing instance fields
each frame, and sends predictable sequences ahead as instance animations. Rebuilding is only for structure (§2.3).

**Against.**
- *Immediate mode everywhere:* the program re-emits its whole window every frame something moves, which is what
  `TreeRenderer` over `Canvas` does today. 60 rebuilds a second for a change that happens once.
- *Hover and press as program logic,* the script callback: a wake, a batch and a frame for the commonest visual
  change on a desktop.
- *Compositor animations that write the program's fields:* two writers of one slot, and no value to come back to.

**Evidence.** vexelray-gui §7: its transform layer already animates without relayout, and the identity-value rule
already decides what may join it.

**Trigger.** A common interaction that is neither a style nor a layout animation, and has to be decided by the
program within the frame.

**Named** choreographer, not presenter: `WindowedPresenter`, `WindowPresenter` and `PresenterProvider` in
vexelray-vulkan already mean putting a frame on a surface, and vexelray-gui's CLAUDE.md records what such a collision
costs (`Ramp`). "Choreography" was already vexelray-gui's planned name for multi-node layout transitions (its
architecture §7), which is this layer's job. Android's `Choreographer` (a v-sync frame callback) overlaps rather than
conflicts: this one is also stepped by the frame clock, through the TTF.

## D13 — Security by user consent, enforced by the kernel, granted as fds (2026-10-23)

**Decided.** Programs are third-party and proprietary. First run, filesystem, network, sockets and ports each need
the user's consent, bundled per program if it likes, remembered and revocable. Every program starts sandboxed; a
broker grants access by handing it fds; a gesture that expresses intent (choosing a file, pasting, dropping) is
itself consent; prompts are drawn by the compositor as the trusted path. Signatures are optional: they give identity
across updates, and skip consent only for keys in a trust store and permissions their manifest declares (§8.1).

**Against.**
- *Enforcement inside the program* (a Java permission layer). Native code makes system calls directly, and
  `SecurityManager` was removed in JDK 24.
- *Changing a running program's sandbox when consent is given.* Landlock and seccomp can only be tightened.
- *A prompt for every access,* including ones the user just performed by hand. It trains "accept all", which is what
  consent exists to prevent.
- *Signatures as the only gate* (mandatory signing). Proprietary programs from small publishers would need a
  signing authority before they could run at all; consent keeps unsigned programs possible but visible.

**Scope is the program's choice.** A request may be anything from one file to root; the system does not rank
requests, it guarantees the interaction is honest: the system describes the resolved scope in its own words, grants
exactly that, states who is asking, makes broad scopes look broad, and shows every grant afterwards. Where Landlock
cannot express a policy, BPF (cgroup socket hooks, BPF-LSM) comes before a kernel module.

*Against:* a fixed permission taxonomy decided by the system. It would put the system between the user and the
program, which is where the trust does not live.

**Revocation applies to new grants and the next start, never to a running process.** *Against:* revocable proxies
(a FUSE layer for files) that would claw access back mid-run; a cost on every access, for a guarantee the kernel
does not offer anywhere else.

**Evidence.** Reasoning only. The fd-grant model follows Flatpak portals and capability systems.

**Trigger.** Users approving prompts without reading them anyway, measured by how fast prompts are answered.

## D14 — A batch early for its TTF frame is held; a TTF never repeats a frame (2026-10-23)

**Decided.** A batch that arrives before the frame its TTF named is held, unapplied, until that frame, and the wake it
answers stays outstanding meanwhile, so the program cannot rewrite the memory the batch is in. A wake never names a
frame earlier than the one after the last batch this program had accepted (§3.1, §3.2).

**Against.** *Drawing every batch on arrival,* which §3.3 implied and the M0 spike first did. A batch sampled for a
present time a frame away is shown a frame early. With the second rule in place this ratchets: once one TTF is a frame
ahead, every later one is too, and the window's motion stays a frame early for good while every batch counts as on
time. In the spike, a dashboard ran with a 17 ms prediction error at p50 until this was found.

*No floor on the TTF:* a fast answer to a later wake names the same frame as a slow answer to an earlier one, and D5
ignores it. The compositor would be provoking the rule meant for misbehaving programs.

**Evidence.** M0 spike: holding brought the prediction error from 17 ms to 0.1 ms at p50. One to two batches in each
six-second phase were early, all of them after a wake predicted to need two frames.

**Trigger.** Raster on arrival (D8) makes holding cost a frame's worth of spread GPU work often enough to matter.
