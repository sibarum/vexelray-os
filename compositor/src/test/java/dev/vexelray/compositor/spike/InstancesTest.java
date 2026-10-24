package dev.vexelray.compositor.spike;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.CoreLooks;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.text.AtlasData;
import dev.vexelray.text.TextLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstancesTest {

    @Test
    void boxesAndTextConvertExactly() {
        int w = 640;
        int h = 400;
        Canvas c = new Canvas(w, h);
        c.looks(CoreLooks.table(CoreShadelets.composition()));
        TextLayout tl = new TextLayout(AtlasData.loadFromResource(Programs.ATLAS_JSON));
        c.begin();
        c.fillRect(0, 0, w, h, Color.rgb(0x202428));
        c.fillRoundRect(20.5f, 30.25f, 200, 48, 10, Color.rgb(0x3a6ea5));
        c.fillRoundRect(240, 30, 120, 48, 14, 0, Color.rgba(1f, 0.5f, 0.2f, 0.8f));
        c.fillCircle(400, 54, 20, Color.rgb(0xe0e0e0));
        c.pushClip(10, 100, 300, 60, 8);
        c.text(tl, "The quick brown fox jumps over the lazy dog 0123456789", 16, 110,
                TextLayout.TextStyle.of(18f), Color.rgb(0xffffff));
        c.popClip();
        c.text(tl, "{ return x -> x * 2; }", 16, 200, TextLayout.TextStyle.of(13f), Color.rgb(0xc0c0c0));

        float[] verts = c.toVertexArray();
        int vertexCount = c.vertexCount();
        assertEquals(0, vertexCount % 6, "every primitive is a quad");
        int quads = vertexCount / 6;
        assertTrue(quads > 60, "the text emitted glyphs: " + quads);
        float[] inst = new float[quads * Wire.INSTANCE_FLOATS];
        Instances.fromVertices(verts, 0, quads, w, h, inst, 0);
        float err = Instances.maxError(verts, 0, quads, w, h, inst, 0);
        assertTrue(err < 1e-4f, "instances rebuild the canvas's vertices: worst relative error " + err);
        System.out.printf("%d quads: %d bytes as canvas vertices, %d bytes as instances%n", quads,
                vertexCount * Wire.CANVAS_FLOATS_PER_VERTEX * 4, quads * Wire.INSTANCE_BYTES);
    }
}
