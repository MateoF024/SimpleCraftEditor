package org.mateof24.sce.core;

import org.mateof24.sce.SimpleCraftEditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stopwatch for the mod's own work, reported under {@link SceDebug.Category#PERF}.
 *
 * <p>Written because the honest answer to "editing is slow in my 600-mod pack" is that nobody knows
 * which part is slow. Guessing produced four rewrites of the recipe engine once already; this exists so
 * the next change is aimed at a measured number instead. Every operation worth a player's patience is
 * timed end to end and broken into the stages it is actually made of, so a slow one names its own cause.
 *
 * <p>Two shapes, because two kinds of work need different treatment:
 *
 * <ul>
 *   <li>A {@link Run} for a whole operation — loading the recipes, saving an edit, opening the editor.
 *       It logs one line with the total and every stage inside it, and keeps that line so
 *       {@code /sce debug perf} can show it again later.</li>
 *   <li>{@link #since} for work that happens too often to log each time — parsing one recipe, syncing
 *       one player. Those are accumulated into a count, a total, a mean and a worst case.</li>
 * </ul>
 *
 * <p><b>It costs nothing while it is off.</b> {@link #start} hands back a shared do-nothing {@link Run}
 * whose methods return immediately, and {@link #now} hands back zero, which {@link #since} then ignores.
 * So a call site is a field read and a branch, and it never allocates, formats or reads the clock. That
 * is what makes it safe to leave the instrumentation in the hot paths permanently rather than adding it
 * whenever a report comes in — by which time the pack that showed the problem is usually gone.
 */
public final class ScePerf {
    /** Nanoseconds per millisecond, as a double so the reports keep their decimals. */
    private static final double MS = 1_000_000.0;
    /** Stage time below which "the rest" is not worth naming, in nanoseconds. */
    private static final long NOISE = 100_000L; // 0.1 ms

    /** The last line each operation produced, so the command can show a run that has already scrolled by. */
    private static final Map<String, String> LAST = new ConcurrentHashMap<>();
    /** Running totals for the work that is too frequent to log line by line. */
    private static final Map<String, Totals> TOTALS = new ConcurrentHashMap<>();

    /** A do-nothing run, handed out while the category is off so no call site has to check for null. */
    private static final Run OFF = new Run(null, 0L);

    private ScePerf() {
    }

    // ------------------------------------------------------------------ whole operations

    /**
     * Begins timing {@code operation}. Always returns a usable object: a real one while PERF is on, and
     * the shared do-nothing one while it is off.
     */
    public static Run start(String operation) {
        return SceDebug.isOn(SceDebug.Category.PERF) ? new Run(operation, System.nanoTime()) : OFF;
    }

    /** One timed operation, split into named stages. Not thread-safe: a run belongs to one thread. */
    public static final class Run {
        private final String operation;
        private final long start;
        private long mark;
        private final List<String> names;
        private final List<Long> times;

        private Run(String operation, long start) {
            this.operation = operation;
            this.start = start;
            this.mark = start;
            // Null on the do-nothing run, and the only thing every method has to test.
            this.names = operation == null ? null : new ArrayList<>(12);
            this.times = operation == null ? null : new ArrayList<>(12);
        }

        /** Closes the stage that was running and names it. Call in order, once per step. */
        public void stage(String name) {
            if (names == null) {
                return;
            }
            long now = System.nanoTime();
            names.add(name);
            times.add(now - mark);
            mark = now;
        }

        /**
         * Ends the run and writes it to the log. {@code detail} uses SLF4J {@code {}} placeholders and is
         * where the counts go — a duration without the size of the work it did cannot be compared to
         * anything, so "took 4 s" is useless while "took 4 s for 41,208 recipes" is a measurement.
         */
        public void finish(String detail, Object... args) {
            if (names == null) {
                return;
            }
            long total = System.nanoTime() - start;
            StringBuilder sb = new StringBuilder(operation).append(": ").append(ms(total)).append(" ms");
            if (detail != null && !detail.isEmpty()) {
                sb.append(" [").append(format(detail, args)).append(']');
            }
            if (!names.isEmpty()) {
                long accounted = 0L;
                sb.append(" =");
                for (int i = 0; i < names.size(); i++) {
                    long time = times.get(i);
                    accounted += time;
                    sb.append(i == 0 ? " " : " + ").append(names.get(i)).append(' ').append(ms(time));
                }
                // Anything the stages did not cover is real time too, and hiding it would make a
                // breakdown that adds up to less than its own total look trustworthy.
                long rest = total - accounted;
                if (rest > NOISE) {
                    sb.append(" + the rest ").append(ms(rest));
                }
            }
            String line = sb.toString();
            LAST.put(operation, line);
            SimpleCraftEditor.LOGGER.info("[SCE-DBG/PERF] {}", line);
        }
    }

    // ------------------------------------------------------------------ frequent work

    /**
     * A clock reading to hand to {@link #since}, or zero while PERF is off. Kept separate from
     * {@code System.nanoTime()} so a hot path pays nothing for the timing it is not doing.
     */
    public static long now() {
        return SceDebug.isOn(SceDebug.Category.PERF) ? System.nanoTime() : 0L;
    }

    /** Adds the time since {@code startNanos} to {@code name}'s totals. A zero start is ignored. */
    public static void since(String name, long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        TOTALS.compute(name, (key, totals) -> {
            Totals t = totals == null ? new Totals() : totals;
            t.count++;
            t.total += elapsed;
            if (elapsed > t.worst) {
                t.worst = elapsed;
            }
            return t;
        });
    }

    private static final class Totals {
        private long count;
        private long total;
        private long worst;
    }

    // ------------------------------------------------------------------ reporting

    /**
     * Everything measured so far, for {@code /sce debug perf}. Includes the heap, because a recipe cache
     * the size of a modpack is a memory question as much as a time one.
     */
    public static String report() {
        StringBuilder sb = new StringBuilder("Performance so far");
        if (!SceDebug.isOn(SceDebug.Category.PERF)) {
            sb.append(" (measuring is OFF - turn it on with /sce debug perf true, then repeat what was slow)");
        }
        sb.append(':');

        if (LAST.isEmpty()) {
            sb.append("\n  Operations: none timed yet");
        } else {
            sb.append("\n  Last time each operation ran:");
            LAST.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sb.append("\n    ").append(entry.getValue()));
        }

        if (TOTALS.isEmpty()) {
            sb.append("\n  Repeated work: none timed yet");
        } else {
            sb.append("\n  Repeated work (count, total, mean, worst):");
            TOTALS.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        Totals t = entry.getValue();
                        sb.append("\n    ").append(entry.getKey())
                                .append(": ").append(t.count).append(" x")
                                .append(", total ").append(ms(t.total)).append(" ms")
                                .append(", mean ").append(ms(t.count == 0 ? 0 : t.total / t.count)).append(" ms")
                                .append(", worst ").append(ms(t.worst)).append(" ms");
                    });
        }

        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        sb.append("\n  Heap: ").append(mb(used)).append(" MB in use of ").append(mb(runtime.maxMemory()))
                .append(" MB maximum");
        return sb.toString();
    }

    /** Forgets every measurement, so a run can be timed on its own rather than mixed with the last one. */
    public static void reset() {
        LAST.clear();
        TOTALS.clear();
    }

    // ------------------------------------------------------------------ formatting

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / MS);
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.0f", bytes / (1024.0 * 1024.0));
    }

    /** Fills SLF4J-style {@code {}} placeholders, so a detail string reads like every other log line here. */
    private static String format(String pattern, Object... args) {
        if (args == null || args.length == 0) {
            return pattern;
        }
        StringBuilder sb = new StringBuilder(pattern.length() + 16);
        int arg = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (arg < args.length && pattern.charAt(i) == '{' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '}') {
                sb.append(args[arg++]);
                i++;
            } else {
                sb.append(pattern.charAt(i));
            }
        }
        return sb.toString();
    }
}
