package dev.vexelray.compositor.spike;

import static dev.vexelray.compositor.spike.Wire.CANVAS_FLOATS_PER_VERTEX;
import static dev.vexelray.compositor.spike.Wire.INSTANCE_FLOATS;
import static dev.vexelray.compositor.spike.Wire.I_CLIP_AA;
import static dev.vexelray.compositor.spike.Wire.I_CLIP_BOX;
import static dev.vexelray.compositor.spike.Wire.I_CLIP_R;
import static dev.vexelray.compositor.spike.Wire.I_COLOR;
import static dev.vexelray.compositor.spike.Wire.I_ELEMENT;
import static dev.vexelray.compositor.spike.Wire.I_FILTER;
import static dev.vexelray.compositor.spike.Wire.I_LOCAL;
import static dev.vexelray.compositor.spike.Wire.I_POS;
import static dev.vexelray.compositor.spike.Wire.I_SHAPE;
import static dev.vexelray.compositor.spike.Wire.I_UV;

/**
 * Canvas fat vertices to instances, and back. The program side of gap G1: today's {@code Canvas} still emits six
 * fat vertices per primitive, so the spike converts them rather than changing the canvas.
 *
 * <p>Only axis-aligned quads convert exactly (no rotation transform); {@link #maxError} says how far a conversion is
 * from the vertices it came from, and the tests hold it to zero for what the spike's programs draw.
 */
public final class Instances {

    private Instances() {
    }

    // CanvasVertex float offsets.
    private static final int V_POS = 0;
    private static final int V_COLOR = 2;
    private static final int V_UV = 6;
    private static final int V_LOCAL = 8;
    private static final int V_SHAPE = 10;
    private static final int V_CLIP_BOX = 14;
    private static final int V_CLIP_RS = 18;
    private static final int V_FILTER = 22;
    private static final int V_ELEMENT = 26;

    /**
     * Convert {@code quads} quads starting at vertex {@code firstVertex} of a canvas vertex array into instances in
     * {@code out} from {@code outAt}. Positions go from the canvas's clip space to px in a {@code w} by {@code h}
     * window.
     */
    public static void fromVertices(float[] verts, int firstVertex, int quads, int w, int h, float[] out, int outAt) {
        for (int q = 0; q < quads; q++) {
            int v0 = (firstVertex + q * 6) * CANVAS_FLOATS_PER_VERTEX;
            int o = outAt + q * INSTANCE_FLOATS;
            int lo = v0;
            int hi = v0;
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (int k = 0; k < 6; k++) {
                int v = v0 + k * CANVAS_FLOATS_PER_VERTEX;
                minX = Math.min(minX, verts[v + V_POS]);
                minY = Math.min(minY, verts[v + V_POS + 1]);
                maxX = Math.max(maxX, verts[v + V_POS]);
                maxY = Math.max(maxY, verts[v + V_POS + 1]);
            }
            for (int k = 0; k < 6; k++) {
                int v = v0 + k * CANVAS_FLOATS_PER_VERTEX;
                if (verts[v + V_POS] == minX && verts[v + V_POS + 1] == minY) {
                    lo = v;
                }
                if (verts[v + V_POS] == maxX && verts[v + V_POS + 1] == maxY) {
                    hi = v;
                }
            }
            out[o] = 0f;
            out[o + I_ELEMENT] = verts[v0 + V_ELEMENT];
            out[o + I_CLIP_R] = verts[v0 + V_CLIP_RS + 2];
            out[o + I_CLIP_AA] = verts[v0 + V_CLIP_RS + 3];
            out[o + I_POS] = toPx(verts[lo + V_POS], w);
            out[o + I_POS + 1] = toPx(verts[lo + V_POS + 1], h);
            out[o + I_POS + 2] = toPx(verts[hi + V_POS], w);
            out[o + I_POS + 3] = toPx(verts[hi + V_POS + 1], h);
            out[o + I_UV] = verts[lo + V_UV];
            out[o + I_UV + 1] = verts[lo + V_UV + 1];
            out[o + I_UV + 2] = verts[hi + V_UV];
            out[o + I_UV + 3] = verts[hi + V_UV + 1];
            out[o + I_LOCAL] = verts[lo + V_LOCAL];
            out[o + I_LOCAL + 1] = verts[lo + V_LOCAL + 1];
            out[o + I_LOCAL + 2] = verts[hi + V_LOCAL];
            out[o + I_LOCAL + 3] = verts[hi + V_LOCAL + 1];
            System.arraycopy(verts, v0 + V_COLOR, out, o + I_COLOR, 4);
            System.arraycopy(verts, v0 + V_SHAPE, out, o + I_SHAPE, 4);
            System.arraycopy(verts, v0 + V_CLIP_BOX, out, o + I_CLIP_BOX, 4);
            System.arraycopy(verts, v0 + V_FILTER, out, o + I_FILTER, 4);
        }
    }

    /**
     * The largest difference between any float of the original vertices and the vertex the instance shader would
     * rebuild for the same corner, over {@code quads} quads. Clip-space positions are compared in px.
     */
    public static float maxError(float[] verts, int firstVertex, int quads, int w, int h, float[] inst, int instAt) {
        float worst = 0f;
        for (int q = 0; q < quads; q++) {
            int o = instAt + q * INSTANCE_FLOATS;
            float x0 = inst[o + I_POS];
            float y0 = inst[o + I_POS + 1];
            float x1 = inst[o + I_POS + 2];
            float y1 = inst[o + I_POS + 3];
            for (int k = 0; k < 6; k++) {
                int v = (firstVertex + q * 6 + k) * CANVAS_FLOATS_PER_VERTEX;
                float px = toPx(verts[v + V_POS], w);
                float py = toPx(verts[v + V_POS + 1], h);
                float cx = x1 == x0 ? 0f : (px - x0) / (x1 - x0);
                float cy = y1 == y0 ? 0f : (py - y0) / (y1 - y0);
                // The shader only ever builds corners, so a vertex off a corner is a quad this cannot carry.
                worst = Math.max(worst, Math.min(Math.abs(cx), Math.abs(cx - 1f)));
                worst = Math.max(worst, Math.min(Math.abs(cy), Math.abs(cy - 1f)));
                cx = Math.round(cx);
                cy = Math.round(cy);
                worst = Math.max(worst, err(verts[v + V_UV], lerp(inst[o + I_UV], inst[o + I_UV + 2], cx)));
                worst = Math.max(worst, err(verts[v + V_UV + 1], lerp(inst[o + I_UV + 1], inst[o + I_UV + 3], cy)));
                worst = Math.max(worst, err(verts[v + V_LOCAL], lerp(inst[o + I_LOCAL], inst[o + I_LOCAL + 2], cx)));
                worst = Math.max(worst,
                        err(verts[v + V_LOCAL + 1], lerp(inst[o + I_LOCAL + 1], inst[o + I_LOCAL + 3], cy)));
                // clipRs.xy is the fragment's own position, which the shader takes from the rebuilt corner.
                worst = Math.max(worst, err(verts[v + V_CLIP_RS], px));
                worst = Math.max(worst, err(verts[v + V_CLIP_RS + 1], py));
                worst = Math.max(worst, err(verts[v + V_CLIP_RS + 2], inst[o + I_CLIP_R]));
                worst = Math.max(worst, err(verts[v + V_CLIP_RS + 3], inst[o + I_CLIP_AA]));
                worst = Math.max(worst, err(verts[v + V_ELEMENT], inst[o + I_ELEMENT]));
                for (int c = 0; c < 4; c++) {
                    worst = Math.max(worst, err(verts[v + V_COLOR + c], inst[o + I_COLOR + c]));
                    worst = Math.max(worst, err(verts[v + V_SHAPE + c], inst[o + I_SHAPE + c]));
                    worst = Math.max(worst, err(verts[v + V_CLIP_BOX + c], inst[o + I_CLIP_BOX + c]));
                    worst = Math.max(worst, err(verts[v + V_FILTER + c], inst[o + I_FILTER + c]));
                }
            }
        }
        return worst;
    }

    static float toPx(float clip, int size) {
        return (clip + 1f) * 0.5f * size;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float err(float a, float b) {
        return Math.abs(a - b) / Math.max(1f, Math.abs(a));
    }
}
