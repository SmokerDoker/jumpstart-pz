package io.github.jumpstartpz.mod;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How much of the game's main thread each mod takes, measured by sampling: for a few seconds a
 * helper thread looks at the main thread's stack every few milliseconds and credits the sample to
 * the mod whose code runs (the innermost frame of a mod's Java package, or, inside Lua, the mod whose
 * folder holds the running Lua file), else to the game. Costs nothing while it does not measure.
 *
 * <p>A statistical picture: code that runs inside game methods without a frame of its own (a patch's
 * advice) counts for the game; Java code a mod moved to its own library package ({@code jslibN.})
 * counts for the code that called it.
 */
final class PerfSampler {

    /** What the sampler reads from the game; on the helper thread. */
    interface Probe {
        StackTraceElement[] mainStack();

        /** The running Lua file (normalized, lower case, '/'), or null. */
        String luaFile();
    }

    /**
     * Who owns what: Java package prefixes ("pkg.") and mod folders (normalized, lower case, ending
     * in '/') to mod ids.
     */
    record Owners(Map<String, String> packages, Map<String, String> dirs) {
    }

    /** Samples of one mod: in its Java code and in its Lua. */
    record Share(int java, int lua) {
        int total() {
            return java + lua;
        }
    }

    /** One measurement: how long, how many frames and samples, the samples per mod. */
    record Result(long millis, int frames, int samples, Map<String, Share> mods) {
        /** Main-thread milliseconds per frame the mod took. */
        double msPerFrame(String modId) {
            Share s = mods.get(modId);
            return s == null || samples == 0 || frames == 0 ? 0 : (double) s.total() / samples * millis / frames;
        }

        double percent(String modId) {
            Share s = mods.get(modId);
            return s == null || samples == 0 ? 0 : 100.0 * s.total() / samples;
        }

        double frameMs() {
            return frames == 0 ? 0 : (double) millis / frames;
        }
    }

    static final long DURATION_MS = 10_000;
    static final long INTERVAL_MS = 3;

    private static volatile Thread mainThread;
    private static volatile Run run;
    private static volatile Result last;

    private PerfSampler() {
    }

    /** Every frame, on the main thread: remembers it and counts frames while a measurement runs. */
    static void onFrame() {
        if (mainThread == null) {
            mainThread = Thread.currentThread();
        }
        Run r = run;
        if (r != null) {
            r.frames++;
        }
    }

    static Thread mainThread() {
        return mainThread;
    }

    /** Starts a measurement in the background. Returns null, or why not. */
    static synchronized String start(Owners owners, Probe probe, long durationMs, long intervalMs) {
        if (run != null) {
            return "running";
        }
        Run r = new Run(owners);
        run = r;
        Thread t = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                while (System.currentTimeMillis() - start < durationMs) {
                    sample(r, probe);
                    Thread.sleep(intervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finish(r, System.currentTimeMillis() - start);
            }
        }, "Jumpstart performance sampler");
        t.setDaemon(true);
        t.start();
        return null;
    }

    static boolean running() {
        return run != null;
    }

    /** The last finished measurement, or null. */
    static Result last() {
        return last;
    }

    /** For tests: a result as if measured. */
    static void useForTest(Result result) {
        last = result;
        run = null;
    }

    private static synchronized void finish(Run r, long millis) {
        Map<String, Share> mods = new LinkedHashMap<>();
        r.counts.forEach((mod, c) -> mods.put(mod, new Share(c[0], c[1])));
        last = new Result(millis, r.frames, r.samples, mods);
        run = null;
    }

    static void sample(Run r, Probe probe) {
        StackTraceElement[] stack;
        try {
            stack = probe.mainStack();
        } catch (RuntimeException e) {
            return;
        }
        if (stack == null || stack.length == 0) {
            return;
        }
        r.samples++;
        String[] owner = ownerOf(stack, probe, r.owners);
        if (owner != null) {
            int[] c = r.counts.computeIfAbsent(owner[0], k -> new int[2]);
            c[owner[1].equals("java") ? 0 : 1]++;
        }
    }

    /** {mod, "java" or "lua"} for the code a stack runs, or null for the game. Innermost frame first. */
    static String[] ownerOf(StackTraceElement[] stack, Probe probe, Owners owners) {
        boolean luaSeen = false;
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            String mod = packageOwner(cls, owners);
            if (mod != null) {
                return new String[] {mod, "java"};
            }
            if (!luaSeen && cls.startsWith("se.krka.kahlua.")) {
                luaSeen = true; // the innermost Lua function decides; vanilla Lua: look further out
                String file;
                try {
                    file = probe.luaFile();
                } catch (RuntimeException e) {
                    file = null;
                }
                String luaMod = dirOwner(file, owners);
                if (luaMod != null) {
                    return new String[] {luaMod, "lua"};
                }
            }
        }
        return null;
    }

    static String packageOwner(String className, Owners owners) {
        String name = className.replaceFirst("^jsv\\d+\\.", ""); // a live update's package
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, String> e : owners.packages().entrySet()) {
            if (name.startsWith(e.getKey()) && e.getKey().length() > bestLength) {
                best = e.getValue();
                bestLength = e.getKey().length();
            }
        }
        return best;
    }

    static String dirOwner(String file, Owners owners) {
        if (file == null) {
            return null;
        }
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, String> e : owners.dirs().entrySet()) {
            if (file.startsWith(e.getKey()) && e.getKey().length() > bestLength) {
                best = e.getValue();
                bestLength = e.getKey().length();
            }
        }
        return best;
    }

    /** One measurement in progress. Samples on the helper thread, frames on the main thread. */
    static final class Run {
        final Owners owners;
        final Map<String, int[]> counts = new LinkedHashMap<>();
        int samples;
        volatile int frames;

        Run(Owners owners) {
            this.owners = owners;
        }
    }
}
