package dev.vexelray.compositor.spike;

import dev.vexelray.canvas.Color;
import dev.vexelray.text.AtlasData;
import dev.vexelray.text.TextLayout;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneTest {

    private final TextLayout tl = new TextLayout(AtlasData.loadFromResource(Programs.ATLAS_JSON));
    private final ByteBuffer shm = ByteBuffer.allocate(Wire.SHM_BYTES).order(ByteOrder.LITTLE_ENDIAN);

    private int frame(Scene s, String a, String b, boolean cursor) {
        s.begin();
        s.item(1, c -> c.fillRect(0, 0, 200, 100, Color.rgb(0x202020)));
        s.item(2, c -> c.text(tl, a, 4, 4, TextLayout.TextStyle.of(12f), Color.rgb(0xffffff)));
        s.item(3, c -> c.text(tl, b, 4, 30, TextLayout.TextStyle.of(12f), Color.rgb(0xffffff)));
        if (cursor) {
            s.item(4, c -> c.fillRect(60, 30, 1.5f, 13, Color.rgb(0x61afef)));
        }
        s.end();
        return s.writeBatch(shm);
    }

    @Test
    void firstFrameUpsertsEverythingThenOnlyWhatChanged() {
        Scene s = new Scene(200, 100);
        int first = frame(s, "hello", "world", true);
        assertEquals(s.instanceCount(), first, "the first batch is every instance");
        assertEquals(0, frame(s, "hello", "world", true), "nothing changed, nothing sent");
        assertEquals(1, frame(s, "hello", "world!", true), "one glyph appended is one upsert");
        assertEquals(1, frame(s, "hello", "world!", false), "the cursor gone is one removal");
        assertEquals(Wire.OP_REMOVE, shm.getInt(Wire.BATCH_HEADER));
        assertEquals(Wire.BATCH_HEADER + Wire.REMOVE_BYTES, Wire.BATCH_HEADER + shm.getInt(4));
    }

    @Test
    void damageMergesOverlapsAndCapsAtEight() {
        var rects = new java.util.ArrayList<Composite.Rect>();
        rects.add(new Composite.Rect(10, 10, 20, 20));
        rects.add(new Composite.Rect(15, 15, 20, 20));
        assertEquals(1, Compositor.merge(rects).size());
        rects.clear();
        for (int i = 0; i < 20; i++) {
            rects.add(new Composite.Rect(i * 60, (i % 5) * 150, 10, 10));
        }
        assertEquals(8, Compositor.merge(rects).size());
    }
}
