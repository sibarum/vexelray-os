# vexelray-os vision

## Goal

An operating system where every piece people touch is our own: the compositor, how programs talk to
each other, the type system for data, the app model, the toolkit, and the shell. We build our own
pieces because that is the only way to get the polish we want, not for the sake of it. Polish
that stops at an app's edge is not polish. The hardest moments are the ones between programs:
dragging something from one into another, switching between them, one starting while another
animates. No single app can make those smooth. The OS has to.

We judge every design choice by one question: does it make moving between programs feel like
using one program?

## The flagship: drag-and-drop

Drag-and-drop is the first thing we build, and the thing we measure the rest against. It touches
every layer at once: data types, talking between processes, permissions, compositing, and
animation. If drag-and-drop is right, the layers under it are right.

Done means:

- **Typed.** What is dragged is a value of a known type, not a bag of bytes with a MIME string.
- **Easy to adopt.** A program can offer or accept data in a few lines and get reasonable
  behaviour for every other program for free.
- **Extendable.** Programs and packages can add new types and new conversions.
- **Compatible by negotiation.** If the target cannot take what the source has, the OS converts
  it, and picks the conversion that loses the least.
- **Continuous.** From the moment it is picked up until it lands, the dragged item never visibly
  jumps, flickers, or waits on a slow program.

### 1. Types

One registry of data types for the whole OS. Types have structure (a contact is a record with
fields) and subtyping (`Png ⊂ Raster ⊂ Visual`), so a target that accepts `Raster` takes a PNG
without anyone writing code for that pair.

Programs and packages can register new types, but only by saying how each one relates to types
that already exist: what it is a subtype of, and which conversions lead into and out of it. A type
cut off from the rest is useless to drag, so the registry refuses it.

### 2. Negotiation

A **converter** turns one type into another. It runs sandboxed and declares two things up front:
its *cost* (time, memory) and its *fidelity* (lossless, lossy, or flattens to a picture).

- **Offers.** The source offers a set of types it can produce. It produces nothing yet.
- **Accepts.** Each target declares the types it accepts, and may weight them by preference.
- **Path.** While the item hovers over a target, the OS searches the converter graph from what
  is offered to what is accepted. It ranks paths by fidelity first, then cost. The target is told
  what it would receive, so it can show that before the drop ("will paste as plain text").
- **Lazy.** Nothing is produced or converted until the drop. Big data streams in after the drop,
  so a 4 GB video drags exactly as smoothly as a word.
- **No path, no landing.** A target with no path refuses visibly while the item hovers, never
  after the drop.

The same negotiation serves copy and paste, sharing, and "open with". Drag-and-drop is just the
first use.

### 3. The drag overlay

The dragged item is drawn by a separate process the OS manages, always above every window. It owns
the drag from pickup to landing:

- **Pickup.** The source hands over a *description* of how the item looks, not a bitmap, so the
  overlay can reshape it on the way.
- **Flight.** While the item hovers, a target can suggest a *landing shape*, such as "you'd become
  a thumbnail in this grid slot." The overlay animates toward that shape, so the item already looks
  like what it will become.
- **Landing.** The target reports where the item goes. The overlay flies it there, and only then
  hands the item over, timed to the frame, so the target's real content appears exactly where the
  overlay's copy disappears.
- **Survival.** If the source crashes mid-drag, the drag carries on. If the target crashes,
  the item flies back. The person never loses what is in their hand.

### 4. Permissions

The drop is the person's consent, and it is the only consent needed. What the target receives is a
handle to exactly that data, granted by the OS, not a copy of the source's access. No program can
read what is being dragged until it is dropped on that program.

## What the drag-and-drop goal requires of the rest of the OS

- **The compositor runs animations.** A transition that waits for a program to draw each frame
  stutters whenever that program is busy. Programs describe what they show and how it should move.
  The compositor plays the motion on time even when the program stalls.
- **One clock.** The overlay, the compositor, and every program agree on when the next frame is
  shown, so a handoff between processes lands on a single frame.
- **Talking between processes is ours.** It carries typed values and handles, not byte streams,
  and the type registry and converter graph are built on it.

## Open questions

1. **How deep "our own" goes.** Proposed: everything above the kernel is ours, and the kernel and
   drivers are borrowed. Writing our own kernel and drivers takes years and buys none of the polish
   described here. Not yet confirmed.
2. **Running inside Windows.** Earlier (2026-10-24) the goal included running as a native app
   on Windows, like a desktop within the desktop. If that still holds, talking between processes
   must work on both Windows and Linux from the start.
3. **Language and toolkit.** Either programs are written on the VexelRay framework (Java) as the
   native toolkit, or the OS defines its own app model and VexelRay is one client of it.

## History

An earlier design (a shadelet compositor on Ubuntu, decisions D1–D14, and an M0 spike) was dropped
on 2026-10-31 in favour of this one. It is in git history before that date. Its one idea carried
forward is that the compositor, not each program, plays animations.
