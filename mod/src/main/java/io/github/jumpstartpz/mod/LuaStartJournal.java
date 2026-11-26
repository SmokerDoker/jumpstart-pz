package io.github.jumpstartpz.mod;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the mods' Lua changed in Lua's globals while the game loaded it (at the start, or with a
 * save), so a mod that came with the save can be switched off completely, as one loaded live
 * ({@link LuaJournal}). The game hooks call {@link #onLoadDir} when the game runs its Lua folders
 * and {@link #onSet} on every raw table write; while the game loads (until the first frame of the
 * main menu or the game, so the start events are in it too), writes from a mod's file are kept:
 * new globals, and functions the mod's files put into a table. Vanilla files are not kept.
 *
 * <p>{@link #changesOf} turns the writes of one mod into {@link LuaJournal.Change}s with the same
 * rules as a live load: new globals, and functions at the top level or one table down (not inside
 * a table the mod added itself).
 */
public final class LuaStartJournal {

    /** One write: the value before (null for nil) and the value written, and the mod file it came from. */
    record Write(Object table, Object key, Object before, Object after, String file) {
    }

    private static volatile boolean recording;
    private static Object env;
    private static final List<Write> WRITES = new ArrayList<>();
    private static volatile Lookup lookup;
    /** The game's own Lua (normalized), not kept. */
    private static final String VANILLA = normalize(System.getProperty("user.dir", "")) + "/media/";

    private LuaStartJournal() {
    }

    // --- hooks (game code calls these; they must never throw) -------------------------------------

    /** LuaManager.LoadDirBase: the game runs its Lua folders; a new Lua state starts a new record. */
    public static void onLoadDir() {
        try {
            Object current = lookup().env.get(null);
            synchronized (WRITES) {
                if (current != env) {
                    WRITES.clear();
                    env = current;
                }
            }
            recording = true;
        } catch (Throwable t) {
            recording = false;
        }
    }

    /** KahluaTableImpl.rawset(Object, Object), before the write. */
    public static void onSet(Object table, Object key, Object value) {
        if (!recording) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            record(table, key, value);
        } catch (Throwable t) {
            // never in the game's way
        }
        spentNanos += System.nanoTime() - t0;
        calls++;
    }

    /** What the recording cost during the running load: time in record() and how many writes it saw. */
    private static long spentNanos;
    private static long calls;

    /** Every UI frame: the first frame of the main menu or the game ends the load. */
    static void onFrame() {
        if (!recording) {
            return;
        }
        try {
            Object states = Class.forName("zombie.GameWindow").getField("states").get(null);
            Object current = states.getClass().getField("current").get(states);
            String name = current == null ? "" : current.getClass().getSimpleName();
            if (name.equals("IngameState") || name.equals("MainScreenState")) {
                recording = false;
                int count;
                synchronized (WRITES) {
                    count = WRITES.size();
                }
                System.out.println("[Jumpstart] Lua load recorded: " + count + " changes from mod files; recording took "
                        + spentNanos / 1_000_000 + " ms for " + calls + " table writes");
                spentNanos = 0;
                calls = 0;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            recording = false;
        }
    }

    private static void record(Object table, Object key, Object value) throws Throwable {
        Lookup l = lookup();
        Object globals = env;
        boolean top = table == globals;
        boolean closure = value != null && value.getClass() == l.closure;
        if (!top && !closure) {
            return; // data in tables: the game's and the mods' state, not what switching off takes back
        }
        String file = closure ? fileOf(l, value) : LuaSource.currentFile();
        if (!isModFile(file)) {
            return;
        }
        Object before = l.rawget.invoke(table, key);
        if (before == value) {
            return;
        }
        synchronized (WRITES) {
            if (globals == env) {
                WRITES.add(new Write(table, key, before, value, file));
            }
        }
    }

    static boolean isModFile(String file) {
        if (file == null || file.isEmpty() || file.startsWith(VANILLA)) {
            return false;
        }
        boolean absolute = file.startsWith("/") || (file.length() > 2 && file.charAt(1) == ':');
        return absolute && file.endsWith(".lua");
    }

    private static String fileOf(Lookup l, Object closure) throws IllegalAccessException {
        Object prototype = l.prototype.get(closure);
        Object file = prototype == null ? null : l.filename.get(prototype);
        return file == null ? null : normalize(file.toString());
    }

    // --- for switching a mod off -----------------------------------------------------------------

    /**
     * The changes a mod's files made while the game loaded, merged per entry (first value before,
     * last value written), with the rules of a live load. Empty if this Lua state was not recorded.
     */
    static List<LuaJournal.Change> changesOf(LuaLive.Game game, Predicate<String> modFile) {
        List<Write> mine = new ArrayList<>();
        Object globals;
        synchronized (WRITES) {
            globals = env;
            for (Write w : WRITES) {
                if (modFile.test(w.file())) {
                    mine.add(w);
                }
            }
        }
        if (globals == null || globals != game.globalTable()) {
            return List.of();
        }
        return changesOf(game, globals, mine);
    }

    /** {@link #changesOf(LuaLive.Game, Predicate)} for the given writes (tests). */
    static List<LuaJournal.Change> changesOf(LuaLive.Game game, Object globals, List<Write> writes) {
        // tables at the top level now, by identity, to their global name
        Map<Object, String> names = new IdentityHashMap<>();
        for (Map.Entry<Object, Object> e : game.entries(globals).entrySet()) {
            if (e.getValue() != globals && game.isTable(e.getValue()) && !names.containsKey(e.getValue())) {
                names.put(e.getValue(), String.valueOf(e.getKey()));
            }
        }
        // tables the mod added itself: their entries go with them
        Set<Object> own = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Write w : writes) {
            if (w.table() == globals && w.before() == null && game.isTable(w.after())) {
                own.add(w.after());
            }
        }
        Map<Ref, LuaJournal.Change> merged = new LinkedHashMap<>();
        for (Write w : writes) {
            boolean top = w.table() == globals;
            if (!top && (!names.containsKey(w.table()) || own.contains(w.table()))) {
                continue;
            }
            Ref ref = new Ref(w.table(), w.key());
            LuaJournal.Change earlier = merged.get(ref);
            Object before = w.before() == null ? LuaJournal.ABSENT : w.before();
            if (earlier != null && earlier.after() == w.before()) {
                before = earlier.before(); // the mod wrote it again: from what was there before the mod
            }
            String path = top ? String.valueOf(w.key()) : names.get(w.table()) + "." + w.key();
            merged.remove(ref);
            merged.put(ref, new LuaJournal.Change(w.table(), w.key(), path, before, w.after()));
        }
        List<LuaJournal.Change> changes = new ArrayList<>(merged.values());
        changes.sort((a, b) -> a.path().compareTo(b.path()));
        return changes;
    }

    /** Tests: writes as the hooks would have kept them. */
    static void recordForTest(Object globals, List<Write> writes) {
        synchronized (WRITES) {
            WRITES.clear();
            WRITES.addAll(writes);
            env = globals;
        }
    }

    private record Ref(Object table, Object key) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Ref r && r.table == table && Objects.equals(r.key, key);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(table) * 31 + Objects.hashCode(key);
        }
    }

    private static String normalize(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static Lookup lookup() throws ReflectiveOperationException {
        Lookup l = lookup;
        if (l == null) {
            lookup = l = new Lookup();
        }
        return l;
    }

    private static final class Lookup {
        final Field env;
        final Class<?> closure;
        final Field prototype;
        final Field filename;
        final MethodHandle rawget;

        Lookup() throws ReflectiveOperationException {
            env = Class.forName("zombie.Lua.LuaManager").getField("env");
            closure = Class.forName("se.krka.kahlua.vm.LuaClosure");
            prototype = closure.getField("prototype");
            filename = Class.forName("se.krka.kahlua.vm.Prototype").getField("filename");
            Class<?> table = Class.forName("se.krka.kahlua.vm.KahluaTable");
            rawget = MethodHandles.publicLookup().findVirtual(table, "rawget", MethodType.methodType(Object.class, Object.class))
                    .asType(MethodType.methodType(Object.class, Object.class, Object.class));
        }
    }
}
