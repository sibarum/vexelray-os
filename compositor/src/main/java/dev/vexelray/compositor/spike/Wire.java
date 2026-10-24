package dev.vexelray.compositor.spike;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Everything both ends of the M0 protocol agree on: the instance record, the shared-memory batch layout, and the
 * fixed-size control messages that cross the Unix socket.
 *
 * <p>Spike shim. design.md section 1 puts messages on atchung/elektro-Q, encoded with intrabuf and versioned from the
 * first message (D4). This is a hand-rolled fixed layout with no version and no checked decode (gaps M2, M3).
 *
 * <h2>The instance (gap G1 in miniature)</h2>
 *
 * <p>One primitive of {@code vexelray-canvas} is six fat vertices of {@link #CANVAS_FLOATS_PER_VERTEX} floats. All six
 * share colour, shape, clip, filter and element; position, uv and local vary linearly across an axis-aligned quad.
 * So one instance is the three rects plus the shared fields, and the vertex shader rebuilds the six vertices:
 *
 * <pre>
 *   0  window slot (written by the compositor; on the wire, unused)
 *   1  element    the record in the shadelet tables that names the look
 *   2  clipR      clip corner radius      3  clipAa
 *   4  pos rect   x0 y0 x1 y1, px in the window
 *   8  uv rect    u0 v0 u1 v1, at (x0,y0) and (x1,y1)
 *  12  local rect l0x l0y l1x l1y, likewise
 *  16  colour     r g b a
 *  20  shape      as CanvasVertex loc4
 *  24  clipBox    cx cy hw hh, px in the window
 *  28  filter     as CanvasVertex loc7
 * </pre>
 */
public final class Wire {

    private Wire() {
    }

    // ---- the instance ----

    public static final int CANVAS_FLOATS_PER_VERTEX = 27;
    public static final int INSTANCE_FLOATS = 32;
    public static final int INSTANCE_BYTES = INSTANCE_FLOATS * Float.BYTES;

    public static final int I_WINDOW = 0;
    public static final int I_ELEMENT = 1;
    public static final int I_CLIP_R = 2;
    public static final int I_CLIP_AA = 3;
    public static final int I_POS = 4;
    public static final int I_UV = 8;
    public static final int I_LOCAL = 12;
    public static final int I_COLOR = 16;
    public static final int I_SHAPE = 20;
    public static final int I_CLIP_BOX = 24;
    public static final int I_FILTER = 28;

    // ---- the compositor's GPU instance buffer ----

    /** Floats 0 and 1: the composite's size in px. */
    public static final int G_SCREEN = 0;
    /** Per window slot, four floats from here: origin x, origin y, unused, unused. */
    public static final int G_WINDOWS = 16;
    public static final int MAX_WINDOWS = 64;
    /** Where instance 0 starts. */
    public static final int G_INSTANCES = 512;

    // ---- the batch, in shared memory ----

    /** Size of each program's shared-memory file. One slot: a program has at most one batch outstanding. */
    public static final int SHM_BYTES = 8 << 20;
    /** Header: int op count, int byte length of the ops. */
    public static final int BATCH_HEADER = 8;
    public static final int OP_UPSERT = 1;
    public static final int OP_REMOVE = 2;
    /** An upsert: int kind, int id, then instance floats 1..31 (the window slot is the compositor's). */
    public static final int UPSERT_BYTES = 8 + (INSTANCE_FLOATS - 1) * Float.BYTES;
    /** A removal: int kind, int id. */
    public static final int REMOVE_BYTES = 8;

    public static String shmPath(long pid) {
        return "/dev/shm/vexel-m0-" + pid;
    }

    // ---- control messages, over the socket ----

    public static final int MSG_BYTES = 48;

    /** P to C. a0 scenario, a1 width, a2 height, tA pid. */
    public static final int HELLO = 1;
    /** C to P. a0 reason, frame = TTF frame, tA predicted present (nanoTime), tB sent (nanoTime), a1 input code. */
    public static final int WAKE = 2;
    /** P to C. frame = target, a0 bytes, a1 ops, tA wake sent (echo), tB program work ns, tC sent. */
    public static final int BATCH = 3;
    /** P to C. frame = target, tA wake sent (echo), tB program work ns, tC sent. */
    public static final int NO_CHANGE = 4;
    /** C to P. a0 window slot, a1 screen width, a2 screen height. */
    public static final int WELCOME = 5;
    /** C to P: exit. */
    public static final int BYE = 6;

    public static final int REASON_FIRST = 1;
    public static final int REASON_INPUT = 2;
    public static final int REASON_FRAME = 3;

    /** One control message. */
    public record Msg(int type, int a0, long frame, long tA, long tB, long tC, int a1, int a2) {

        public static Msg read(ByteBuffer b) {
            return new Msg(b.getInt(), b.getInt(), b.getLong(), b.getLong(), b.getLong(), b.getLong(), b.getInt(),
                    b.getInt());
        }

        public ByteBuffer encode() {
            ByteBuffer b = ByteBuffer.allocate(MSG_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(type).putInt(a0).putLong(frame).putLong(tA).putLong(tB).putLong(tC).putInt(a1).putInt(a2);
            return b.flip();
        }
    }
}
