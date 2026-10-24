package dev.vexelray.compositor.spike;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Counters and samples for one phase of a run, and their summary. */
final class Stats {

    final String name;
    long frames;
    long framesWithDamage;
    long damagePx;
    long verticesDrawn;
    long uploadBytes;
    long wakesSent;
    long batches;
    long noChange;
    long batchBytes;
    long ops;
    long hits;
    long late;
    long ignored;
    long held;
    long inputsCoalesced;
    final List<Long> interval = new ArrayList<>();
    final List<Long> busy = new ArrayList<>();
    final List<Long> enginePresent = new ArrayList<>();
    final List<Long> raster = new ArrayList<>();
    final List<Long> latency = new ArrayList<>();
    final List<Long> programWork = new ArrayList<>();
    final List<Long> apply = new ArrayList<>();
    final List<Long> batchSize = new ArrayList<>();
    final List<Long> presentError = new ArrayList<>();

    Stats(String name) {
        this.name = name;
    }

    static double ms(long ns) {
        return ns / 1e6;
    }

    static long pct(List<Long> xs, double p) {
        if (xs.isEmpty()) {
            return 0;
        }
        List<Long> s = new ArrayList<>(xs);
        s.sort(null);
        return s.get(Math.min(s.size() - 1, (int) Math.floor(p * (s.size() - 1) + 0.5)));
    }

    static double mean(List<Long> xs) {
        return xs.isEmpty() ? 0 : xs.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", name);
        m.put("frames", frames);
        double meanInterval = mean(interval);
        m.put("fps", meanInterval > 0 ? String.format("%.1f", 1e9 / meanInterval) : "-");
        m.put("frame interval p50/p95/max ms", fmt3(interval));
        m.put("compositor busy per frame p50/p95/max ms", fmt3(busy));
        m.put("engine copy to swapchain + present p50/p95/max ms", fmt3(enginePresent));
        m.put("frames with damage", framesWithDamage);
        m.put("damaged px per damaged frame", framesWithDamage == 0 ? 0 : damagePx / framesWithDamage);
        m.put("raster p50/p95/max ms (damaged frames)", fmt3(raster));
        m.put("vertices drawn per damaged frame", framesWithDamage == 0 ? 0 : verticesDrawn / framesWithDamage);
        m.put("instance upload bytes per frame", frames == 0 ? 0 : uploadBytes / frames);
        m.put("wakes sent", wakesSent);
        m.put("batches", batches);
        m.put("no-change answers", noChange);
        m.put("messages per frame", frames == 0 ? "-" : String.format("%.2f",
                (wakesSent + batches + noChange) / (double) frames));
        m.put("batch bytes p50/p95/max", String.format("%d / %d / %d", pct(batchSize, 0.5), pct(batchSize, 0.95),
                pct(batchSize, 1.0)));
        m.put("ops per batch (mean)", batches == 0 ? 0 : String.format("%.1f", ops / (double) batches));
        m.put("apply on arrival p50/p95 ms", String.format("%.3f / %.3f", ms(pct(apply, 0.5)), ms(pct(apply, 0.95))));
        m.put("program work p50/p95 ms", String.format("%.2f / %.2f", ms(pct(programWork, 0.5)),
                ms(pct(programWork, 0.95))));
        m.put("wake to present p50/p95/max ms", fmt3(latency));
        long answered = hits + late;
        m.put("landed in TTF frame", answered == 0 ? "-" : String.format("%d of %d (%.1f%%)", hits, answered,
                100.0 * hits / answered));
        m.put("late by >= 1 frame", late);
        m.put("present-time prediction error p50/p95 ms (abs)", String.format("%.2f / %.2f",
                ms(pct(presentError, 0.5)), ms(pct(presentError, 0.95))));
        m.put("batches held for a later TTF frame", held);
        m.put("ignored batches (D5)", ignored);
        m.put("inputs coalesced behind an outstanding wake", inputsCoalesced);
        return m;
    }

    private static String fmt3(List<Long> xs) {
        return String.format("%.2f / %.2f / %.2f", ms(pct(xs, 0.5)), ms(pct(xs, 0.95)), ms(pct(xs, 1.0)));
    }
}
