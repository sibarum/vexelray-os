package dev.vexelray.compositor.spike;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.shadelet.CoreLooks;
import dev.vexelray.canvas.shadelet.CoreShadelets;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.Consumer;

import static dev.vexelray.compositor.spike.Wire.INSTANCE_FLOATS;

/**
 * A program's window as keyed items drawn through today's immediate-mode {@code Canvas}, converted to instances and
 * diffed against the previous frame to make a batch.
 *
 * <p>Spike shim for gap G2. vexelray-gui's {@code TreeRenderer} re-emits the whole tree every frame and has no
 * instance ids; here a program re-emits the whole window too, and ids come from item keys the program chooses: an
 * instance's id is {@code key << 10 | j} for the item's j-th quad. Keys must rise in paint order, so ids sort in paint
 * order and the compositor can order by id (design.md section 4.1).
 */
public final class Scene {

    private static final int MAX_QUADS_PER_ITEM = 1 << 10;

    private final Canvas canvas;
    private final int width;
    private final int height;

    private int[] itemKey = new int[256];
    private int[] itemFirst = new int[256];
    private int[] itemQuads = new int[256];
    private int items;
    private int lastKey;

    private int[] ids = new int[0];
    private float[] data = new float[0];
    private int count;

    private int[] prevIds = new int[0];
    private float[] prevData = new float[0];
    private int prevCount;

    public Scene(int width, int height) {
        this.width = width;
        this.height = height;
        this.canvas = new Canvas(width, height);
        canvas.looks(CoreLooks.table(CoreShadelets.composition()));
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void begin() {
        canvas.begin();
        items = 0;
        lastKey = -1;
    }

    /** Draw one item. Keys must rise from call to call within a frame. */
    public void item(int key, Consumer<Canvas> draw) {
        if (key <= lastKey || key >= 1 << 20) {
            throw new IllegalArgumentException("item keys must rise in paint order and stay below 2^20: " + key);
        }
        lastKey = key;
        int first = canvas.vertexCount();
        draw.accept(canvas);
        int quads = (canvas.vertexCount() - first) / 6;
        if (quads >= MAX_QUADS_PER_ITEM) {
            throw new IllegalStateException("item " + key + " drew " + quads + " quads; split it");
        }
        if (items == itemKey.length) {
            itemKey = Arrays.copyOf(itemKey, items * 2);
            itemFirst = Arrays.copyOf(itemFirst, items * 2);
            itemQuads = Arrays.copyOf(itemQuads, items * 2);
        }
        itemKey[items] = key;
        itemFirst[items] = first;
        itemQuads[items] = quads;
        items++;
    }

    /** Convert what was drawn since {@link #begin} into this frame's instances. */
    public void end() {
        float[] verts = canvas.toVertexArray();
        int total = canvas.vertexCount() / 6;
        int[] swapIds = prevIds;
        float[] swapData = prevData;
        prevIds = ids;
        prevData = data;
        prevCount = count;
        ids = swapIds.length >= total ? swapIds : new int[Math.max(total, 64)];
        data = swapData.length >= total * INSTANCE_FLOATS ? swapData : new float[Math.max(total, 64) * INSTANCE_FLOATS];
        count = 0;
        for (int i = 0; i < items; i++) {
            Instances.fromVertices(verts, itemFirst[i], itemQuads[i], width, height, data, count * INSTANCE_FLOATS);
            for (int j = 0; j < itemQuads[i]; j++) {
                ids[count++] = itemKey[i] << 10 | j;
            }
        }
    }

    public int instanceCount() {
        return count;
    }

    /**
     * Write the difference from the previous frame as a batch into {@code shm} from position 0: header, then an upsert
     * for every instance that is new or changed and a removal for every one that is gone.
     *
     * @return the number of ops; 0 means nothing changed
     */
    public int writeBatch(ByteBuffer shm) {
        shm.position(Wire.BATCH_HEADER);
        int ops = 0;
        int a = 0;
        int b = 0;
        while (a < prevCount || b < count) {
            int pa = a < prevCount ? prevIds[a] : Integer.MAX_VALUE;
            int nb = b < count ? ids[b] : Integer.MAX_VALUE;
            if (pa < nb) {
                shm.putInt(Wire.OP_REMOVE).putInt(pa);
                ops++;
                a++;
            } else if (nb < pa) {
                upsert(shm, b);
                ops++;
                b++;
            } else {
                int fa = a * INSTANCE_FLOATS;
                int fb = b * INSTANCE_FLOATS;
                if (!Arrays.equals(prevData, fa, fa + INSTANCE_FLOATS, data, fb, fb + INSTANCE_FLOATS)) {
                    upsert(shm, b);
                    ops++;
                }
                a++;
                b++;
            }
        }
        int bytes = shm.position() - Wire.BATCH_HEADER;
        shm.putInt(0, ops).putInt(4, bytes);
        return ops;
    }

    private void upsert(ByteBuffer shm, int i) {
        shm.putInt(Wire.OP_UPSERT).putInt(ids[i]);
        int f = i * INSTANCE_FLOATS;
        for (int k = 1; k < INSTANCE_FLOATS; k++) {
            shm.putFloat(data[f + k]);
        }
    }
}
