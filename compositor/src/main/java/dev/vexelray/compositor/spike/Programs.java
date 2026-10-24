package dev.vexelray.compositor.spike;

import dev.vexelray.canvas.Color;
import dev.vexelray.text.AtlasData;
import dev.vexelray.text.TextLayout;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The spike's programs: a separate process each, no GPU device, drawing with today's {@code Canvas} and sending
 * instance batches. Run as {@code Programs <socket> <scenario>}.
 *
 * <ul>
 *   <li>{@link #EDITOR}: a text-heavy window. Woken by typed input; each key changes a glyph, the cursor and the
 *       status line.
 *   <li>{@link #DASHBOARD}: animated. Woken every frame while the compositor asks; samples its animation at the
 *       wake's predicted present time.
 *   <li>{@link #LIST}: still. Woken once, then never; the compositor moves it without telling it.
 * </ul>
 */
public final class Programs {

    public static final String ATLAS_JSON = "/dev/vexelray/text/atlas/primary.json";

    public static final int EDITOR = 1;
    public static final int DASHBOARD = 2;
    public static final int LIST = 3;

    public static int width(int scenario) {
        return switch (scenario) {
            case EDITOR -> 780;
            case DASHBOARD -> 420;
            case LIST -> 340;
            default -> throw new IllegalArgumentException("scenario " + scenario);
        };
    }

    public static int height(int scenario) {
        return switch (scenario) {
            case EDITOR -> 620;
            case DASHBOARD -> 300;
            case LIST -> 520;
            default -> throw new IllegalArgumentException("scenario " + scenario);
        };
    }

    public static void main(String[] args) throws IOException {
        Path socket = Path.of(args[0]);
        int scenario = Integer.parseInt(args[1]);
        long pid = ProcessHandle.current().pid();
        Path shmPath = Path.of(Wire.shmPath(pid));
        Files.deleteIfExists(shmPath);
        MappedByteBuffer shm;
        try (RandomAccessFile f = new RandomAccessFile(shmPath.toFile(), "rw")) {
            f.setLength(Wire.SHM_BYTES);
            shm = f.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, Wire.SHM_BYTES);
        }
        shm.order(ByteOrder.LITTLE_ENDIAN);

        Window window = switch (scenario) {
            case EDITOR -> new Editor();
            case DASHBOARD -> new Dashboard();
            case LIST -> new FileList();
            default -> throw new IllegalArgumentException("scenario " + scenario);
        };
        Scene scene = new Scene(width(scenario), height(scenario));
        TextLayout tl = new TextLayout(AtlasData.loadFromResource(ATLAS_JSON));
        long start = System.nanoTime();
        int batches = 0;

        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(socket));
            send(ch, new Wire.Msg(Wire.HELLO, scenario, 0, pid, 0, 0, scene.width(), scene.height()));
            ByteBuffer in = ByteBuffer.allocate(Wire.MSG_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            while (true) {
                in.clear();
                while (in.hasRemaining()) {
                    if (ch.read(in) < 0) {
                        return;
                    }
                }
                Wire.Msg m = Wire.Msg.read(in.flip());
                if (m.type() == Wire.BYE) {
                    break;
                }
                if (m.type() != Wire.WAKE) {
                    continue;
                }
                long woke = System.nanoTime();
                if (m.a0() == Wire.REASON_INPUT) {
                    window.input(m.a1());
                }
                // Sample at the TTF: the time the frame this batch lands in is predicted to be shown.
                double t = (m.tA() - start) / 1e9;
                scene.begin();
                window.draw(scene, tl, t);
                scene.end();
                int ops = scene.writeBatch(shm);
                long done = System.nanoTime();
                if (ops == 0) {
                    send(ch, new Wire.Msg(Wire.NO_CHANGE, 0, m.frame(), m.tB(), done - woke, done, 0, 0));
                } else {
                    int bytes = Wire.BATCH_HEADER + shm.getInt(4);
                    send(ch, new Wire.Msg(Wire.BATCH, bytes, m.frame(), m.tB(), done - woke, done, ops,
                            scene.instanceCount()));
                    if (scenario == DASHBOARD && ++batches % 30 == 0) {
                        // Misbehaving on purpose, to exercise D5: the same batch again, for the same frame.
                        send(ch, new Wire.Msg(Wire.BATCH, bytes, m.frame(), m.tB(), done - woke, done, ops,
                                scene.instanceCount()));
                    }
                }
            }
        } finally {
            Files.deleteIfExists(shmPath);
        }
    }

    private static void send(SocketChannel ch, Wire.Msg m) throws IOException {
        ByteBuffer b = m.encode();
        while (b.hasRemaining()) {
            ch.write(b);
        }
    }

    interface Window {
        default void input(int code) {
        }

        void draw(Scene s, TextLayout tl, double t);
    }

    private static final Color BG = Color.rgb(0x1e2227);
    private static final Color PANEL = Color.rgb(0x272c33);
    private static final Color TITLE = Color.rgb(0x323843);
    private static final Color INK = Color.rgb(0xd7dae0);
    private static final Color DIM = Color.rgb(0x7f8794);
    private static final Color ACCENT = Color.rgb(0x61afef);
    private static final Color WARM = Color.rgb(0xe5c07b);

    static void chrome(Scene s, TextLayout tl, String title) {
        s.item(1, c -> c.fillRoundRect(0, 0, s.width(), s.height(), 10, BG));
        s.item(2, c -> c.fillRoundRect(0, 0, s.width(), 34, 10, 0, TITLE));
        s.item(3, c -> c.text(tl, title, 14, 9, TextLayout.TextStyle.of(14f), INK));
    }

    /** A text-heavy window. */
    static final class Editor implements Window {

        private static final String[] SOURCE = {
                "package dev.vexelray.compositor;",
                "",
                "import java.util.ArrayList;",
                "import java.util.List;",
                "",
                "/** Retains every layer's instances; the composite is the only image it keeps. */",
                "public final class Compositor implements AutoCloseable {",
                "",
                "    private final List<Layer> layers = new ArrayList<>();",
                "    private final Predictor predictor = new Predictor(60.0);",
                "    private long frame;",
                "",
                "    public void apply(Layer layer, Batch batch) {",
                "        if (batch.frame() < layer.acceptedThrough()) {",
                "            probe.count(\"batch.ignored\");   // one batch per frame (D5)",
                "            return;",
                "        }",
                "        for (Op op : batch.ops()) {",
                "            Rect before = layer.bounds(op.id());",
                "            layer.write(op);",
                "            damage.add(before.union(layer.bounds(op.id())));",
                "        }",
                "        layer.accept(batch.frame());",
                "    }",
                "",
                "    public void frame(long now) {",
                "        Damage d = damage.take();",
                "        if (!d.isEmpty()) {",
                "            raster.draw(layers, d);   // every instance in the region, in z order",
                "        }",
                "        for (Layer layer : layers) {",
                "            if (layer.wantsWake(frame)) {",
                "                layer.wake(predictor.ttf(layer, now));",
                "            }",
                "        }",
                "        frame++;",
                "    }",
                "",
                "    @Override",
                "    public void close() {",
                "        layers.forEach(Layer::close);",
                "    }",
                "}",
                "",
                "",
                "",
                "",
        };
        private static final int CURSOR_LINE = 43;

        private final StringBuilder[] lines = new StringBuilder[SOURCE.length];

        Editor() {
            for (int i = 0; i < SOURCE.length; i++) {
                lines[i] = new StringBuilder(SOURCE[i]);
            }
            lines[CURSOR_LINE].append("// ");
        }

        @Override
        public void input(int code) {
            StringBuilder line = lines[CURSOR_LINE];
            if (code == '\b') {
                line.setLength(Math.max(3, line.length() - 1));
            } else if (line.length() > 90) {
                line.setLength(3);
            } else {
                line.append((char) code);
            }
        }

        @Override
        public void draw(Scene s, TextLayout tl, double t) {
            chrome(s, tl, "Compositor.java - editor");
            float top = 44f;
            float lh = 12.4f;
            s.item(4, c -> c.fillRect(0, 34, 44, s.height() - 60, PANEL));
            for (int i = 0; i < lines.length; i++) {
                String n = Integer.toString(i + 1);
                float y = top + i * lh;
                s.item(1000 + i, c -> c.text(tl, n, 30 - n.length() * 6.4f, y, TextLayout.TextStyle.of(11f), DIM));
            }
            for (int i = 0; i < lines.length; i++) {
                String text = lines[i].toString();
                float y = top + i * lh;
                Color ink = text.trim().startsWith("//") || text.trim().startsWith("/**") ? DIM : INK;
                s.item(20000 + i, c -> c.text(tl, text, 54, y, TextLayout.TextStyle.of(11f), ink));
            }
            float cx = 54 + tl.measure(lines[CURSOR_LINE].toString(), 11f).width();
            float cy = top + CURSOR_LINE * lh;
            s.item(90000, c -> c.fillRect(cx + 1, cy, 1.5f, 13, ACCENT));
            String status = "Ln " + (CURSOR_LINE + 1) + ", Col " + (lines[CURSOR_LINE].length() + 1)
                    + "    UTF-8    Java    spaces: 4";
            s.item(90001, c -> c.fillRoundRect(0, s.height() - 26, s.width(), 26, 0, 10, TITLE));
            s.item(90002, c -> c.text(tl, status, 14, s.height() - 20, TextLayout.TextStyle.of(11f), DIM));
        }
    }

    /** An animated window, sampled at the predicted present time. */
    static final class Dashboard implements Window {

        @Override
        public void draw(Scene s, TextLayout tl, double t) {
            chrome(s, tl, "Throughput");
            String[] names = {"ingest", "raster", "present"};
            for (int i = 0; i < 3; i++) {
                float y = 60 + i * 52;
                double phase = t * (0.35 + i * 0.17);
                float frac = (float) (0.5 + 0.45 * Math.sin(phase * Math.PI * 2));
                int k = i;
                s.item(10 + i * 10, c -> c.text(tl, names[k], 20, y, TextLayout.TextStyle.of(13f), DIM));
                s.item(11 + i * 10, c -> c.fillRoundRect(20, y + 20, 380, 14, 7, PANEL));
                s.item(12 + i * 10, c -> c.fillRoundRect(20, y + 20, 380 * frac, 14, 7, k == 1 ? WARM : ACCENT));
                String pct = (int) (frac * 100) + "%";
                s.item(13 + i * 10, c -> c.text(tl, pct, 360, y, TextLayout.TextStyle.of(13f), INK));
            }
            float dotX = (float) (30 + 360 * (0.5 + 0.5 * Math.sin(t * 1.3)));
            s.item(100, c -> c.fillCircle(dotX, 236, 6, WARM));
            String clock = String.format("t = %.2f s", t);
            s.item(101, c -> c.text(tl, clock, 20, 260, TextLayout.TextStyle.of(16f), INK));
        }
    }

    /** A still window. */
    static final class FileList implements Window {

        @Override
        public void draw(Scene s, TextLayout tl, double t) {
            chrome(s, tl, "~/workspace/vexelray-os");
            String[] names = {"compositor", "docs", "mkosi.extra", "mkosi.profiles", "playground", "scripts",
                    "tests", ".gitattributes", ".gitignore", "README.md", "mkosi.conf", "design.md",
                    "decisions.md", "m0-spike.md", "setup.sh", "jdk.sh", "build.sh", "run-qemu.sh", "to-vmdk.sh",
                    "smoke.sh", "hostname", "vexelray-java.sh"};
            for (int i = 0; i < names.length; i++) {
                float y = 44 + i * 21;
                String name = names[i];
                boolean dir = i < 7;
                String size = dir ? "--" : (1200 + i * 731) / 100 / 10.0 + " KB";
                boolean stripe = i % 2 == 0;
                s.item(100 + i, c -> {
                    if (stripe) {
                        c.fillRect(0, y - 3, s.width(), 21, PANEL);
                    }
                    c.fillRoundRect(14, y, 14, 14, 3, dir ? ACCENT : DIM);
                    c.text(tl, name, 36, y, TextLayout.TextStyle.of(13f), INK);
                    c.text(tl, size, 260, y, TextLayout.TextStyle.of(12f), DIM);
                });
            }
        }
    }

    private Programs() {
    }
}
