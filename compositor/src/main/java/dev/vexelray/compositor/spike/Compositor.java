package dev.vexelray.compositor.spike;

import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.Composition;
import dev.vexelray.canvas.shadelet.CoreLooks;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import dev.vexelray.technique.canvas.CanvasTechnique;
import dev.vexelray.text.AtlasData;
import dev.vexelray.text.TextLayout;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.StorageBuffer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.foreign.MemorySegment;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import static dev.vexelray.compositor.spike.Wire.INSTANCE_FLOATS;

/**
 * The M0 compositor: nested in a window, retains every program's instances, applies batches on arrival, rasterizes
 * damage into the composite, presents it, and wakes programs with a TTF only when they must act.
 *
 * <p>{@code Compositor run <outDir>} spawns the three {@link Programs} and plays a scripted timeline of phases;
 * {@code Compositor sweep <outDir>} measures raster cost against instance count with no programs.
 */
public final class Compositor {

    static final int W = 1280;
    static final int H = 800;
    static final int SLAB = 16384;
    static final int MAX_LAYERS = 6;
    static final double HZ = 60.0;
    static final long PERIOD = (long) (1e9 / HZ);
    static final float[] DESKTOP = {0.11f, 0.12f, 0.14f};

    /** One window: a program's retained layer, or the compositor's own shell at slot 0. */
    static final class Layer {
        final int slot;
        final int scenario;
        final int w;
        final int h;
        float ox;
        float oy;
        final int gpuBase;
        final int[] ids = new int[SLAB];
        final float[] data = new float[SLAB * INSTANCE_FLOATS];
        int count;
        /** How many instances the GPU buffer holds as of the last upload: what a raster draws. Frame thread only. */
        int gpuCount;
        int dirtyLo = Integer.MAX_VALUE;
        int dirtyHi = -1;
        boolean originDirty = true;
        SocketChannel ch;
        MappedByteBuffer shm;
        long acceptedThrough = -1;
        boolean firstSent;
        boolean firstAnswered;
        boolean wakeOutstanding;
        long wakeSent;
        long wakeFrame;
        long wakePresent;
        boolean everyFrame;
        final ArrayDeque<Integer> inputs = new ArrayDeque<>();
        double responseEma = 4e6;
        final List<Applied> applied = new ArrayList<>();
        Applied first;
        Wire.Msg held;
        long heldArrived;

        Layer(int slot, int scenario, int w, int h, float ox, float oy) {
            this.slot = slot;
            this.scenario = scenario;
            this.w = w;
            this.h = h;
            this.ox = ox;
            this.oy = oy;
            this.gpuBase = slot * SLAB;
        }

        Composite.Rect bounds() {
            return new Composite.Rect((int) Math.floor(ox), (int) Math.floor(oy), w + 1, h + 1);
        }

        void dirty(int lo, int hi) {
            dirtyLo = Math.min(dirtyLo, lo);
            dirtyHi = Math.max(dirtyHi, hi);
        }
    }

    /** A batch, applied on arrival, waiting to be counted against the frame that draws it. */
    record Applied(long target, long wakeSent, long predictedPresent, long arrived, long programWork, int bytes,
                   int ops, long applyNanos) {
    }

    private final Object lock = new Object();
    private final List<Layer> layers = new ArrayList<>();
    private final List<Composite.Rect> damage = new ArrayList<>();
    private final Path outDir;
    private final boolean sweep;
    private final boolean offscreen;
    private final Path socket;
    private final List<Process> children = new ArrayList<>();

    private VulkanDevice device;
    private Composite composite;
    private InstanceBuffer instances;
    private StorageBuffer tables;
    private AtlasTexture atlas;
    private GraphicsPipeline pipeline;
    private DrawCommands cmds;
    private final CanvasTechnique present = new CanvasTechnique(W, H);
    private TextLayout textLayout;

    private long frame;
    /** The next frame whose raster has not begun; written and read holding the lock. */
    private long nextDraw;
    private long frameStart;
    private double periodEma = PERIOD;
    private double presentEma = 8e6;
    private long lastExit;
    private long nextTick;
    private final List<List<Applied>> drawnLastFrame = new ArrayList<>();

    private final List<Stats> phases = new ArrayList<>();
    private Stats stats;
    private long timelineStart;
    private int phaseIndex = -1;
    private int typed;
    private static final String TYPING = "the composite is the only retained image; a still desktop sends nothing. ";

    private Compositor(Path outDir, boolean sweep, boolean offscreen) {
        this.outDir = outDir;
        this.sweep = sweep;
        this.offscreen = offscreen;
        this.socket = Path.of("/tmp/vexel-m0-" + ProcessHandle.current().pid() + ".sock");
    }

    public static void main(String[] args) throws Exception {
        boolean sweep = args.length > 0 && args[0].equals("sweep");
        Path out = Path.of(args.length > 1 ? args[1] : "target/m0");
        Files.createDirectories(out);
        boolean offscreen = args.length > 2 && args[2].equals("offscreen");
        new Compositor(out, sweep, offscreen).run();
    }

    // ---------------------------------------------------------------- setup

    private final class Realize implements RenderTechnique {
        @Override
        public void realize(TechniqueContext ctx) {
            device = ((VulkanTechniqueContext) ctx).device();
            composite = new Composite(device, W, H);
            composite.clear(DESKTOP[0], DESKTOP[1], DESKTOP[2]);
            instances = new InstanceBuffer(device, Wire.G_INSTANCES + MAX_LAYERS * SLAB * INSTANCE_FLOATS);
            instances.setUncounted(Wire.G_SCREEN, W);
            instances.setUncounted(Wire.G_SCREEN + 1, H);
            tables = new StorageBuffer(device, Composition.TABLES_FLOATS, Composition.TABLES_BINDING);
            float[] looks = CoreLooks.table(CoreShadelets.composition()).floats();
            tables.update(0, looks, looks.length);
            atlas = loadAtlas(device);
            long[] setLayouts = {atlas.descriptorSetLayout(), atlas.descriptorSetLayout(),
                    tables.descriptorSetLayout(), instances.descriptorSetLayout()};
            GraphicsPipeline.Config config = new GraphicsPipeline.Config(0, List.of(), setLayouts, true,
                    Vk.SHADER_STAGE_FRAGMENT_BIT, 0, true);
            pipeline = new GraphicsPipeline(device, composite.renderPass(), W, H,
                    InstanceShader.vertex().spirv(), "main", CanvasShader.fragment().spirv(), "main", config);
            cmds = new DrawCommands(device);
        }

        @Override
        public void record(FrameContext frame) {
        }

        @Override
        public void close() {
            if (cmds != null) {
                cmds.close();
                pipeline.close();
                atlas.close();
                tables.close();
                instances.close();
                composite.close();
            }
        }
    }

    private static AtlasTexture loadAtlas(VulkanDevice device) {
        String png = Programs.ATLAS_JSON.replace(".json", ".png");
        try (InputStream in = Compositor.class.getResourceAsStream(png)) {
            BufferedImage img = ImageIO.read(in);
            int w = img.getWidth();
            int h = img.getHeight();
            byte[] rgba = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int i = (y * w + x) * 4;
                    rgba[i] = (byte) (argb >> 16);
                    rgba[i + 1] = (byte) (argb >> 8);
                    rgba[i + 2] = (byte) argb;
                    rgba[i + 3] = (byte) (argb >>> 24);
                }
            }
            return new AtlasTexture(device, w, h, rgba);
        } catch (IOException e) {
            throw new IllegalStateException("the shared font atlas", e);
        }
    }

    private void run() throws Exception {
        textLayout = new TextLayout(AtlasData.loadFromResource(Programs.ATLAS_JSON));
        layers.add(shell());
        if (!sweep) {
            listen();
            for (int scenario : new int[]{Programs.EDITOR, Programs.DASHBOARD, Programs.LIST}) {
                spawn(scenario);
            }
        }
        RenderPipeline rp = RenderPipeline.builder()
                .target((offscreen ? Target.offscreen(W, H) : Target.windowed("vexelray-os M0", W, H))
                        .color(AttachmentFormat.SWAPCHAIN))
                .technique(new Realize())
                .technique(present)
                .build();
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("vexelray-os-m0"))) {
            engine.run(rp, info -> {
                try {
                    return sweep ? sweepFrame(engine) : frame(engine);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            if (offscreen) {
                screenshot(engine.lastFrameRgba(), sweep ? "sweep-last.png" : "run-last.png");
            }
        } finally {
            for (Process p : children) {
                p.destroy();
            }
            Files.deleteIfExists(socket);
        }
        report();
    }

    /** The compositor's own layer: the desktop under every window. */
    private Layer shell() {
        Layer s = new Layer(0, 0, W, H, 0, 0);
        Scene scene = new Scene(W, H);
        scene.begin();
        scene.item(1, c -> c.fillRect(0, 0, W, H, Color.rgb(0x1b1e23)));
        scene.item(2, c -> c.text(textLayout, "vexelray-os  M0 spike  (nested)", 24, H - 36,
                TextLayout.TextStyle.of(14f), Color.rgb(0x5c6370)));
        scene.end();
        ByteBuffer b = ByteBuffer.allocate(Wire.SHM_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int ops = scene.writeBatch(b);
        applyOps(s, b, ops);
        return s;
    }

    private void spawn(int scenario) throws IOException {
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "-Xss2m",
                "-cp", System.getProperty("java.class.path"), Programs.class.getName(), socket.toString(),
                Integer.toString(scenario));
        pb.redirectErrorStream(true);
        pb.redirectOutput(outDir.resolve("program-" + scenario + ".log").toFile());
        children.add(pb.start());
    }

    private void listen() throws IOException {
        Files.deleteIfExists(socket);
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        Thread accept = new Thread(() -> {
            try {
                while (true) {
                    SocketChannel ch = server.accept();
                    Wire.Msg hello = read(ch);
                    if (hello == null || hello.type() != Wire.HELLO) {
                        ch.close();
                        continue;
                    }
                    Layer layer;
                    synchronized (lock) {
                        int slot = layers.size();
                        float[] at = switch (hello.a0()) {
                            case Programs.EDITOR -> new float[]{40, 40};
                            case Programs.DASHBOARD -> new float[]{760, 70};
                            default -> new float[]{880, 250};
                        };
                        layer = new Layer(slot, hello.a0(), hello.a1(), hello.a2(), at[0], at[1]);
                        layer.ch = ch;
                        try (RandomAccessFile f = new RandomAccessFile(Wire.shmPath(hello.tA()), "r")) {
                            layer.shm = f.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, Wire.SHM_BYTES);
                        }
                        layer.shm.order(ByteOrder.LITTLE_ENDIAN);
                        layers.add(layer);
                    }
                    send(layer, new Wire.Msg(Wire.WELCOME, layer.slot, 0, 0, 0, 0, W, H));
                    Thread ingest = new Thread(() -> ingest(layer), "ingest-" + layer.slot);
                    ingest.setDaemon(true);
                    ingest.start();
                }
            } catch (IOException e) {
                // server closed at exit
            }
        }, "accept");
        accept.setDaemon(true);
        accept.start();
    }

    private static Wire.Msg read(SocketChannel ch) throws IOException {
        ByteBuffer in = ByteBuffer.allocate(Wire.MSG_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        while (in.hasRemaining()) {
            if (ch.read(in) < 0) {
                return null;
            }
        }
        return Wire.Msg.read(in.flip());
    }

    private static void send(Layer layer, Wire.Msg m) {
        try {
            ByteBuffer b = m.encode();
            while (b.hasRemaining()) {
                layer.ch.write(b);
            }
        } catch (IOException e) {
            // a program that went away costs its own window and nothing else
        }
    }

    // ---------------------------------------------------------------- ingest: apply on arrival

    private void ingest(Layer layer) {
        try {
            while (true) {
                Wire.Msg m = read(layer.ch);
                if (m == null) {
                    return;
                }
                long arrived = System.nanoTime();
                synchronized (lock) {
                    if (m.type() == Wire.BATCH && m.frame() <= layer.acceptedThrough && layer.firstAnswered) {
                        // One batch per frame (D5): a second for the same frame is ignored, and counted. It is not
                        // an answer either: the wake it claims to answer was answered already.
                        if (stats != null) {
                            stats.ignored++;
                        }
                        continue;
                    }
                    layer.responseEma = 0.8 * layer.responseEma + 0.2 * (arrived - m.tA());
                    if (m.type() == Wire.BATCH && m.frame() > nextDraw && layer.firstAnswered) {
                        // Early for its TTF frame: sampled for a later present, so it is held until that frame
                        // rather than drawn now. Its wake stays outstanding, so the program cannot overwrite the
                        // shared memory the held batch is still in.
                        layer.acceptedThrough = m.frame();
                        layer.held = m;
                        layer.heldArrived = arrived;
                        continue;
                    }
                    layer.wakeOutstanding = false;
                    if (m.type() == Wire.NO_CHANGE) {
                        if (stats != null) {
                            stats.noChange++;
                        }
                        continue;
                    }
                    if (m.type() != Wire.BATCH) {
                        continue;
                    }
                    layer.acceptedThrough = m.frame();
                    apply(layer, m, arrived);
                }
            }
        } catch (IOException e) {
            // closed
        }
    }

    /** Apply a batch now, from the program's shared memory: the copy path. Called holding the lock. */
    private void apply(Layer layer, Wire.Msg m, long arrived) {
        long t0 = System.nanoTime();
        ByteBuffer b = layer.shm.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        applyOps(layer, b, b.getInt(0));
        long applyNanos = System.nanoTime() - t0;
        Applied a = new Applied(m.frame(), m.tA(), layer.wakePresent, arrived, m.tB(), m.a0(), m.a1(), applyNanos);
        if (!layer.firstAnswered) {
            layer.first = a;
            layer.firstAnswered = true;
        }
        layer.applied.add(a);
    }

    /** Apply a batch's ops to a layer's retained, id-sorted instances, recording damage and dirty ranges. */
    private void applyOps(Layer layer, ByteBuffer b, int ops) {
        b.position(Wire.BATCH_HEADER);
        for (int n = 0; n < ops; n++) {
            int kind = b.getInt();
            int id = b.getInt();
            int at = Arrays.binarySearch(layer.ids, 0, layer.count, id);
            if (kind == Wire.OP_REMOVE) {
                if (at >= 0) {
                    damageInstance(layer, at);
                    System.arraycopy(layer.ids, at + 1, layer.ids, at, layer.count - at - 1);
                    System.arraycopy(layer.data, (at + 1) * INSTANCE_FLOATS, layer.data, at * INSTANCE_FLOATS,
                            (layer.count - at - 1) * INSTANCE_FLOATS);
                    layer.count--;
                    layer.dirty(at, layer.count - 1);
                }
                continue;
            }
            if (at >= 0) {
                damageInstance(layer, at);
            } else {
                if (layer.count == SLAB) {
                    b.position(b.position() + (INSTANCE_FLOATS - 1) * Float.BYTES);
                    continue;
                }
                at = -at - 1;
                System.arraycopy(layer.ids, at, layer.ids, at + 1, layer.count - at);
                System.arraycopy(layer.data, at * INSTANCE_FLOATS, layer.data, (at + 1) * INSTANCE_FLOATS,
                        (layer.count - at) * INSTANCE_FLOATS);
                layer.count++;
                layer.ids[at] = id;
                layer.dirty(at, layer.count - 1);
            }
            int f = at * INSTANCE_FLOATS;
            layer.data[f] = layer.slot;
            for (int k = 1; k < INSTANCE_FLOATS; k++) {
                layer.data[f + k] = b.getFloat();
            }
            layer.dirty(at, at);
            damageInstance(layer, at);
        }
    }

    private void damageInstance(Layer layer, int at) {
        int f = at * INSTANCE_FLOATS + Wire.I_POS;
        int x0 = (int) Math.floor(layer.data[f] + layer.ox) - 1;
        int y0 = (int) Math.floor(layer.data[f + 1] + layer.oy) - 1;
        int x1 = (int) Math.ceil(layer.data[f + 2] + layer.ox) + 1;
        int y1 = (int) Math.ceil(layer.data[f + 3] + layer.oy) + 1;
        Composite.Rect r = new Composite.Rect(x0, y0, x1 - x0, y1 - y0).intersect(layer.bounds());
        if (r != null) {
            damage.add(r);
        }
    }

    // ---------------------------------------------------------------- the frame

    private boolean frame(VexelEngine engine) throws IOException {
        long entered = System.nanoTime();
        if (frame == 0) {
            present.draw(c -> c.image(0, 0, W, H, composite.image()));
        }
        // What the previous frame drew was presented by now: close out its batches' latency.
        if (stats != null) {
            for (List<Applied> batches : drawnLastFrame) {
                for (Applied a : batches) {
                    stats.latency.add(entered - a.wakeSent());
                    stats.presentError.add(Math.abs(entered - a.predictedPresent()));
                    if (System.getenv("M0_TRACE") != null && stats.presentError.size() % 40 == 1) {
                        System.err.printf("trace %s frame=%d target=%d wake->entered=%.2f predicted->entered=%.2f "
                                        + "wake->arrived=%.2f%n", stats.name, frame, a.target(),
                                (entered - a.wakeSent()) / 1e6, (entered - a.predictedPresent()) / 1e6,
                                (a.arrived() - a.wakeSent()) / 1e6);
                    }
                }
            }
        }
        drawnLastFrame.clear();
        if (frameStart != 0) {
            // From the previous frame's start to here: its raster, then the engine copying the composite to the
            // swapchain and presenting it. Nested and unmapped, "presented" means handed to the presentation engine.
            presentEma = 0.9 * presentEma + 0.1 * (entered - frameStart);
            if (stats != null) {
                stats.enginePresent.add(entered - lastExit);
            }
        }
        // Pace to a fixed refresh: the nested window is not mapped when run headless, so present does not throttle.
        if (nextTick == 0) {
            nextTick = entered;
        }
        nextTick += PERIOD;
        long now = System.nanoTime();
        if (nextTick > now) {
            LockSupport.parkNanos(nextTick - now);
        } else if (now - nextTick > 4 * PERIOD) {
            nextTick = now;
        }
        long start = System.nanoTime();
        if (frameStart != 0) {
            periodEma = 0.9 * periodEma + 0.1 * (start - frameStart);
            if (stats != null) {
                stats.interval.add(start - frameStart);
            }
        }
        frameStart = start;
        boolean keepGoing = script(engine, start);

        List<Composite.Rect> rects;
        synchronized (lock) {
            for (Layer layer : layers) {
                if (layer.held != null && layer.held.frame() <= frame) {
                    apply(layer, layer.held, layer.heldArrived);
                    layer.held = null;
                    layer.wakeOutstanding = false;
                    if (stats != null) {
                        stats.held++;
                    }
                }
                if (!layer.applied.isEmpty()) {
                    for (Applied a : layer.applied) {
                        if (stats != null) {
                            stats.batches++;
                            stats.batchBytes += a.bytes();
                            stats.ops += a.ops();
                            stats.batchSize.add((long) a.bytes());
                            stats.programWork.add(a.programWork());
                            stats.apply.add(a.applyNanos());
                            if (frame <= a.target()) {
                                stats.hits++;
                            } else {
                                stats.late++;
                            }
                        }
                    }
                    drawnLastFrame.add(new ArrayList<>(layer.applied));
                    layer.applied.clear();
                }
                if (layer.originDirty) {
                    instances.setUncounted(Wire.G_WINDOWS + layer.slot * 4, layer.ox);
                    instances.setUncounted(Wire.G_WINDOWS + layer.slot * 4 + 1, layer.oy);
                    layer.originDirty = false;
                }
                if (layer.dirtyHi >= layer.dirtyLo) {
                    instances.write(Wire.G_INSTANCES + (layer.gpuBase + layer.dirtyLo) * INSTANCE_FLOATS, layer.data,
                            layer.dirtyLo * INSTANCE_FLOATS, (layer.dirtyHi - layer.dirtyLo + 1) * INSTANCE_FLOATS);
                    layer.dirtyLo = Integer.MAX_VALUE;
                    layer.dirtyHi = -1;
                }
                // The raster runs outside the lock while batches keep arriving: it draws what was just uploaded,
                // not layer.count, which may already be ahead of the GPU buffer. Changes after this are drawn next
                // frame, from their damage.
                layer.gpuCount = layer.count;
            }
            rects = merge(damage);
            damage.clear();
            nextDraw = frame + 1;
        }
        long rasterNs = 0;
        long vertices = 0;
        if (!rects.isEmpty()) {
            long[] drawn = {0};
            List<Layer> order;
            synchronized (lock) {
                order = new ArrayList<>(layers);
            }
            long t0 = System.nanoTime();
            composite.raster(rects, (cmd, rect) -> drawn[0] += drawRect(cmd, rect, order));
            rasterNs = System.nanoTime() - t0;
            vertices = drawn[0];
        }
        wakes(start);
        if (stats != null) {
            stats.frames++;
            stats.uploadBytes += instances.takeBytesWritten();
            if (!rects.isEmpty()) {
                stats.framesWithDamage++;
                stats.raster.add(rasterNs);
                stats.verticesDrawn += vertices;
                for (Composite.Rect r : rects) {
                    stats.damagePx += r.area();
                }
            }
            stats.busy.add(System.nanoTime() - start);
        } else {
            instances.takeBytesWritten();
        }
        frame++;
        lastExit = System.nanoTime();
        return keepGoing;
    }

    /** Draw every layer intersecting {@code rect}, bottom to top, each scissored to its own window. */
    private long drawRect(MemorySegment cmd, Composite.Rect rect, List<Layer> order) {
        cmds.bindPipeline(cmd, pipeline);
        cmds.bindDescriptorSet(cmd, pipeline, CanvasShader.ATLAS_SET, atlas.descriptorSet());
        cmds.bindDescriptorSet(cmd, pipeline, CanvasShader.IMAGE_SET, atlas.descriptorSet());
        cmds.bindDescriptorSet(cmd, pipeline, Composition.TABLES_SET, tables.descriptorSet());
        cmds.bindDescriptorSet(cmd, pipeline, InstanceShader.INSTANCES_SET, instances.descriptorSet());
        long vertices = 0;
        for (Layer layer : order) {
            int count = layer.gpuCount;
            if (count == 0) {
                continue;
            }
            Composite.Rect clip = rect.intersect(layer.bounds());
            if (clip == null) {
                continue;
            }
            composite.scissor(cmd, clip);
            cmds.draw(cmd, count * 6, layer.gpuBase * 6);
            vertices += count * 6L;
        }
        return vertices;
    }

    /** Merge overlapping or nearly-adjacent damage, down to at most eight rects, clipped to the screen. */
    static List<Composite.Rect> merge(List<Composite.Rect> in) {
        Composite.Rect screen = new Composite.Rect(0, 0, W, H);
        List<Composite.Rect> rs = new ArrayList<>();
        for (Composite.Rect r : in) {
            Composite.Rect c = r.intersect(screen);
            if (c != null) {
                rs.add(c);
            }
        }
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < rs.size(); i++) {
                for (int j = i + 1; j < rs.size(); j++) {
                    Composite.Rect u = rs.get(i).union(rs.get(j));
                    if (u.area() <= 1.25 * (rs.get(i).area() + rs.get(j).area())) {
                        rs.set(i, u);
                        rs.remove(j);
                        merged = true;
                        break outer;
                    }
                }
            }
        }
        while (rs.size() > 8) {
            int bi = 0;
            int bj = 1;
            long best = Long.MAX_VALUE;
            for (int i = 0; i < rs.size(); i++) {
                for (int j = i + 1; j < rs.size(); j++) {
                    long grow = rs.get(i).union(rs.get(j)).area() - rs.get(i).area() - rs.get(j).area();
                    if (grow < best) {
                        best = grow;
                        bi = i;
                        bj = j;
                    }
                }
            }
            rs.set(bi, rs.get(bi).union(rs.get(bj)));
            rs.remove(bj);
        }
        return rs;
    }

    // ---------------------------------------------------------------- wakes and the TTF

    /**
     * Wake every program that must act. The TTF is the first frame whose raster starts after the program's expected
     * answer arrives; its present time is predicted as one period after that raster begins.
     */
    private void wakes(long frameStartNanos) {
        long now = System.nanoTime();
        synchronized (lock) {
            for (Layer layer : layers) {
                if (layer.slot == 0 || layer.wakeOutstanding) {
                    continue;
                }
                int reason;
                int code = 0;
                if (!layer.firstSent) {
                    reason = Wire.REASON_FIRST;
                    layer.firstSent = true;
                } else if (!layer.inputs.isEmpty()) {
                    reason = Wire.REASON_INPUT;
                    code = layer.inputs.poll();
                } else if (layer.everyFrame) {
                    reason = Wire.REASON_FRAME;
                } else {
                    continue;
                }
                // Never a frame this program already has a batch for: a second one would be ignored (D5).
                long k = Math.max(frame + 1, layer.acceptedThrough + 1);
                double margin = 1e6;
                while (now + layer.responseEma + margin > frameStartNanos + (k - frame) * periodEma) {
                    k++;
                }
                long predictedPresent = (long) (frameStartNanos + (k - frame) * periodEma + presentEma);
                layer.wakeOutstanding = true;
                layer.wakeSent = now;
                layer.wakeFrame = k;
                layer.wakePresent = predictedPresent;
                send(layer, new Wire.Msg(Wire.WAKE, reason, k, predictedPresent, now, 0, code, 0));
                if (stats != null) {
                    stats.wakesSent++;
                }
            }
        }
    }

    // ---------------------------------------------------------------- the scripted timeline

    private static final String[] PHASES = {"still", "typing", "animating", "moving", "all at once"};
    private static final double PHASE_SECONDS = 6.0;

    private boolean script(VexelEngine engine, long now) throws IOException {
        if (timelineStart == 0) {
            synchronized (lock) {
                boolean ready = layers.size() == 4;
                for (Layer l : layers) {
                    ready &= l.slot == 0 || l.firstAnswered;
                }
                if (!ready) {
                    return frame < 60 * 60;
                }
            }
            timelineStart = now + (long) 1e9;   // a second to settle after the first batches
            return true;
        }
        double t = (now - timelineStart) / 1e9;
        if (t < 0) {
            return true;
        }
        int p = (int) (t / PHASE_SECONDS);
        if (p != phaseIndex) {
            if (p >= PHASES.length) {
                synchronized (lock) {
                    for (Layer l : layers) {
                        if (l.slot != 0) {
                            send(l, new Wire.Msg(Wire.BYE, 0, 0, 0, 0, 0, 0, 0));
                        }
                    }
                }
                return false;
            }
            phaseIndex = p;
            stats = new Stats(PHASES[p]);
            phases.add(stats);
        }
        String phase = PHASES[p];
        boolean typing = phase.equals("typing") || phase.equals("all at once");
        boolean animating = phase.equals("animating") || phase.equals("all at once");
        boolean moving = phase.equals("moving") || phase.equals("all at once");
        synchronized (lock) {
            for (Layer l : layers) {
                if (l.scenario == Programs.DASHBOARD) {
                    l.everyFrame = animating;
                }
                if (l.scenario == Programs.EDITOR && typing && frame % 5 == 0) {
                    // About twelve keys a second. A key that arrives while a wake is outstanding waits for it.
                    if (l.wakeOutstanding) {
                        stats.inputsCoalesced++;
                    }
                    l.inputs.add((int) TYPING.charAt(typed++ % TYPING.length()));
                }
                if (l.scenario == Programs.LIST && moving) {
                    double u = t - p * PHASE_SECONDS;
                    move(l, (float) (880 - 420 * (0.5 - 0.5 * Math.cos(u * 1.1))),
                            (float) (250 + 140 * Math.sin(u * 0.9)));
                }
            }
        }
        return true;
    }

    /** Move a window: damage where it was and where it is. The program is not told. */
    private void move(Layer l, float x, float y) {
        Composite.Rect before = l.bounds();
        l.ox = x;
        l.oy = y;
        damage.add(before);
        damage.add(l.bounds());
        l.originDirty = true;
    }

    private void screenshot(byte[] rgba, String name) throws IOException {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                img.setRGB(x, y, (rgba[i] & 0xff) << 16 | (rgba[i + 1] & 0xff) << 8 | (rgba[i + 2] & 0xff));
            }
        }
        ImageIO.write(img, "PNG", outDir.resolve(name).toFile());
    }

    // ---------------------------------------------------------------- the sweep

    private static final int[] SWEEP_LINES = {4, 16, 32, 64, 128};
    private int sweepStep = -1;
    private int sweepFrame;
    private final List<String> sweepRows = new ArrayList<>();
    private final List<Long> sweepFull = new ArrayList<>();
    private final List<Long> sweepSmall = new ArrayList<>();
    private final List<Long> sweepIdle = new ArrayList<>();
    private long sweepLast;

    private boolean sweepFrame(VexelEngine engine) throws IOException {
        if (frame == 0) {
            present.draw(c -> c.image(0, 0, W, H, composite.image()));
        }
        long now = System.nanoTime();
        long idle = sweepLast == 0 ? 0 : now - sweepLast;
        if (sweepStep < 0 || sweepFrame == 60) {
            if (sweepStep >= 0) {
                sweepRows.add(String.format("| %d | %d | %.2f | %.2f | %.2f |", SWEEP_LINES[sweepStep],
                        layers.get(1).count, Stats.ms(Stats.pct(sweepFull, 0.5)),
                        Stats.ms(Stats.pct(sweepSmall, 0.5)), Stats.ms(Stats.pct(sweepIdle, 0.5))));
            }
            sweepStep++;
            if (sweepStep == SWEEP_LINES.length) {
                return false;
            }
            sweepFrame = 0;
            sweepFull.clear();
            sweepSmall.clear();
            sweepIdle.clear();
            synchronized (lock) {
                Layer text = sweepLayer(SWEEP_LINES[sweepStep]);
                if (layers.size() > 1) {
                    layers.set(1, text);
                } else {
                    layers.add(text);
                }
                instances.write(Wire.G_INSTANCES + text.gpuBase * INSTANCE_FLOATS, text.data, 0,
                        text.count * INSTANCE_FLOATS);
                text.gpuCount = text.count;
                instances.setUncounted(Wire.G_WINDOWS + 4, 0);
                instances.setUncounted(Wire.G_WINDOWS + 5, 0);
            }
        }
        List<Layer> order = new ArrayList<>(layers);
        List<Composite.Rect> rects;
        List<Long> into;
        if (sweepFrame < 20) {
            rects = List.of(new Composite.Rect(0, 0, W, H));
            into = sweepFull;
        } else if (sweepFrame < 40) {
            rects = List.of(new Composite.Rect(300, 300, 220, 18));
            into = sweepSmall;
        } else {
            rects = List.of();
            into = null;
            if (idle > 0) {
                sweepIdle.add(idle);
            }
        }
        if (into != null) {
            long t0 = System.nanoTime();
            composite.raster(rects, (cmd, rect) -> drawRect(cmd, rect, order));
            into.add(System.nanoTime() - t0);
        }
        sweepFrame++;
        frame++;
        sweepLast = System.nanoTime();
        return true;
    }

    /** A full-screen window of {@code lines} lines of 11 px text, about 150 glyphs each. */
    private Layer sweepLayer(int lines) {
        Layer l = new Layer(1, 0, W, H, 0, 0);
        Scene scene = new Scene(W, H);
        scene.begin();
        String line = "public void frame(long now) { Damage d = damage.take(); raster.draw(layers, d); "
                + "for (Layer layer : layers) layer.wake(predictor.ttf(layer, now)); }";
        for (int i = 0; i < lines; i++) {
            float y = 4 + (i % 64) * 12.4f;
            float x = 4 + (i / 64) * 3f;
            scene.item(10 + i, c -> c.text(textLayout, line, x, y, TextLayout.TextStyle.of(11f),
                    Color.rgb(0xd7dae0)));
        }
        scene.end();
        ByteBuffer b = ByteBuffer.allocate(Wire.SHM_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        applyOps(l, b, scene.writeBatch(b));
        damage.clear();
        return l;
    }

    // ---------------------------------------------------------------- report

    private void report() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(sweep ? "# M0 sweep\n\n" : "# M0 run\n\n");
        if (sweep) {
            sb.append("| text lines | instances | full-screen raster p50 ms | 220x18 raster p50 ms "
                    + "| frame with no damage p50 ms |\n|---|---|---|---|---|\n");
            sweepRows.forEach(r -> sb.append(r).append('\n'));
        } else {
            synchronized (lock) {
                for (Layer l : layers) {
                    sb.append(String.format("layer %d (scenario %d): %d instances (%d bytes retained; %d bytes as one "
                                    + "RGBA8 buffer of its pixels), %dx%d", l.slot, l.scenario, l.count,
                            l.count * Wire.INSTANCE_BYTES, l.w * l.h * 4, l.w, l.h));
                    if (l.first != null) {
                        sb.append(String.format("; first batch %d bytes, %d ops, program %.2f ms, apply %.3f ms",
                                l.first.bytes(), l.first.ops(), Stats.ms(l.first.programWork()),
                                Stats.ms(l.first.applyNanos())));
                    }
                    sb.append('\n');
                }
            }
            sb.append('\n');
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Stats s : phases) {
                rows.add(s.summary());
            }
            if (!rows.isEmpty()) {
                sb.append("| metric |");
                for (Map<String, Object> r : rows) {
                    sb.append(' ').append(r.get("phase")).append(" |");
                }
                sb.append("\n|---|");
                rows.forEach(r -> sb.append("---|"));
                sb.append('\n');
                for (String key : rows.get(0).keySet()) {
                    if (key.equals("phase")) {
                        continue;
                    }
                    sb.append("| ").append(key).append(" |");
                    for (Map<String, Object> r : rows) {
                        sb.append(' ').append(r.get(key)).append(" |");
                    }
                    sb.append('\n');
                }
            }
        }
        Path file = outDir.resolve(sweep ? "sweep.md" : "run.md");
        Files.writeString(file, sb.toString());
        System.out.println(sb);
        System.out.println("report: " + file.toAbsolutePath());
    }
}
