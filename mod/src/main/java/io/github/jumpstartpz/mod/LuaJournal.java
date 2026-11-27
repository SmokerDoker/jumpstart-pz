package io.github.jumpstartpz.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * What a mod's Lua changed in Lua's globals while it loaded, so switching it off can take it back:
 * a snapshot of the global table (its entries and the direct entries of the tables in it, where
 * mods replace functions such as {@code ISInventoryPane.render}) before and after the mod's files
 * ran. Kept are new globals, functions the mod's files define that replaced something or were
 * added to a table, and entries the mod added to the game's tables (registrations: a key binding in
 * {@code keyBinding}, an entry in a list or a lookup table). Values it changed in existing entries
 * stay: those are the game's state.
 *
 * <p>An entry added to a list (key 1, 2, 3, ...) goes like {@code table.remove}: the entries after it
 * move up, so {@code ipairs} does not stop at a hole; it comes back at the end of the list.
 *
 * <p>Undo and redo only touch an entry that still holds what the mod (or, for redo, the game) put
 * there. If another mod replaced it again since (for example by wrapping the mod's function), it
 * stays and is reported: forcing it back would break that other mod.
 */
final class LuaJournal {

    /** "Not there" in a snapshot (Lua nil). */
    static final Object ABSENT = new Object() {
        @Override
        public String toString() {
            return "nil";
        }
    };

    /** Bigger tables (item lists, translations) hold data, not functions mods replace: not scanned. */
    static final int MAX_TABLE = 20_000;

    /** One entry the mod changed. {@code before} is {@link #ABSENT} for an entry it added. */
    record Change(Object table, Object key, String path, Object before, Object after) {
    }

    /** What undo or redo did: entries changed, and the paths left alone because something else changed them. */
    record Outcome(int done, List<String> kept) {
    }

    /** A table entry: the table by identity, the key by value (Lua keys are strings and numbers). */
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

    /** Lua's globals at one moment. */
    static final class Snapshot {
        final Object env;
        final Map<Ref, Object> values = new HashMap<>();
        /** A table found at the top level, by identity, to its global name (for messages). */
        final Map<Object, String> names = new IdentityHashMap<>();

        Snapshot(Object env) {
            this.env = env;
        }

        Object get(Object table, Object key) {
            return values.getOrDefault(new Ref(table, key), ABSENT);
        }
    }

    private LuaJournal() {
    }

    static Snapshot snapshot(LuaLive.Game game) {
        Object env = game.globalTable();
        Snapshot s = new Snapshot(env);
        if (env == null) {
            return s;
        }
        for (Map.Entry<Object, Object> e : game.entries(env).entrySet()) {
            s.values.put(new Ref(env, e.getKey()), e.getValue());
            Object v = e.getValue();
            if (v != env && game.isTable(v) && !s.names.containsKey(v)) {
                s.names.put(v, String.valueOf(e.getKey()));
                Map<Object, Object> inner = game.entries(v);
                if (inner.size() <= MAX_TABLE) {
                    for (Map.Entry<Object, Object> i : inner.entrySet()) {
                        s.values.put(new Ref(v, i.getKey()), i.getValue());
                    }
                }
            }
        }
        return s;
    }

    /**
     * What changed between the snapshots that the mod did: new globals (any value), functions from
     * the mod's files that are new or replaced something, at the top level or one table down, and
     * entries added one table down (only the mod ran between the snapshots). Changed values in
     * existing entries (game counters, data) are the game's own and stay out.
     */
    static List<Change> diff(LuaLive.Game game, Snapshot before, Snapshot after, Predicate<String> modFile) {
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<Ref, Object> e : after.values.entrySet()) {
            Ref ref = e.getKey();
            Object now = e.getValue();
            Object old = before.get(ref.table(), ref.key());
            if (old == now) {
                continue;
            }
            boolean top = ref.table() == after.env;
            if (!top && !before.names.containsKey(ref.table())) {
                continue; // inside a table the mod added: going with that table is enough
            }
            boolean fromMod = modFile.test(game.sourceOf(now));
            if (old == ABSENT || fromMod) {
                String path = top ? String.valueOf(ref.key())
                        : after.names.getOrDefault(ref.table(), "?") + "." + ref.key();
                changes.add(new Change(ref.table(), ref.key(), path, old, now));
            }
        }
        changes.sort((a, b) -> a.path().compareTo(b.path())); // a stable order for messages and tests
        return changes;
    }

    /** Takes the changes back (newest first), each only if it still holds the mod's value. */
    static Outcome undo(LuaLive.Game game, List<Change> changes) {
        int done = 0;
        List<String> kept = new ArrayList<>();
        for (int i = changes.size() - 1; i >= 0; i--) {
            Change c = changes.get(i);
            if (c.before() == ABSENT && index(c.key()) > 0 && c.table() != null) {
                long at = find(game, c.table(), c.after());
                if (at > 0) {
                    removeAt(game, c.table(), at, c.key());
                    done++;
                } else {
                    kept.add(c.path());
                }
                continue;
            }
            if (game.get(c.table(), c.key()) == c.after()) {
                game.set(c.table(), c.key(), c.before() == ABSENT ? null : c.before());
                done++;
            } else {
                kept.add(c.path());
            }
        }
        return new Outcome(done, kept);
    }

    /** Puts the changes back when the mod comes on again, each only if nothing changed the entry since. */
    static Outcome redo(LuaLive.Game game, List<Change> changes) {
        int done = 0;
        List<String> kept = new ArrayList<>();
        for (Change c : changes) {
            if (c.before() == ABSENT && index(c.key()) > 0 && c.table() != null) {
                if (find(game, c.table(), c.after()) == 0) {
                    long end = 1;
                    while (game.get(c.table(), key(c.key(), end)) != null) {
                        end++;
                    }
                    game.set(c.table(), key(c.key(), end), c.after()); // table.insert: at the end
                }
                done++;
                continue;
            }
            Object now = game.get(c.table(), c.key());
            Object expected = c.before() == ABSENT ? null : c.before();
            if (now == expected) {
                game.set(c.table(), c.key(), c.after());
                done++;
            } else {
                kept.add(c.path());
            }
        }
        return new Outcome(done, kept);
    }

    /**
     * For a mod whose Lua ran with the save (no snapshot from before): the functions from its files
     * in the globals, at the top level and one table down. They cannot be restored, only named.
     */
    static List<String> functionsFrom(LuaLive.Game game, Predicate<String> modFile) {
        Snapshot s = snapshot(game);
        List<String> paths = new ArrayList<>();
        for (Map.Entry<Ref, Object> e : s.values.entrySet()) {
            if (modFile.test(game.sourceOf(e.getValue()))) {
                Ref ref = e.getKey();
                paths.add(ref.table() == s.env ? String.valueOf(ref.key())
                        : s.names.getOrDefault(ref.table(), "?") + "." + ref.key());
            }
        }
        paths.sort(null);
        return paths;
    }

    /** The list index a key stands for (Lua numbers are doubles), or 0 when it is no list index. */
    static long index(Object key) {
        if (key instanceof Number n) {
            double d = n.doubleValue();
            if (d >= 1 && d == Math.rint(d) && d < 1e9) {
                return (long) d;
            }
        }
        return 0;
    }

    /** A key for index {@code i} of the same number type as {@code like}. */
    private static Object key(Object like, long i) {
        if (like instanceof Double) {
            return (double) i;
        }
        if (like instanceof Integer) {
            return (int) i;
        }
        return i;
    }

    /** Where in the list (1..n) the value is, by identity; 0 when it is not in it. */
    private static long find(LuaLive.Game game, Object table, Object value) {
        Object like = 1.0;
        for (long i = 1; i < 1_000_000; i++) {
            Object v = game.get(table, key(like, i));
            if (v == null) {
                return 0;
            }
            if (v == value) {
                return i;
            }
        }
        return 0;
    }

    /** table.remove(t, at): the entries after it move up one. */
    private static void removeAt(LuaLive.Game game, Object table, long at, Object like) {
        long i = at;
        while (game.get(table, key(like, i + 1)) != null) {
            game.set(table, key(like, i), game.get(table, key(like, i + 1)));
            i++;
        }
        game.set(table, key(like, i), null);
    }

    /** "a, b, c and 4 more" for messages. */
    static String shortList(List<String> paths, int max) {
        if (paths.size() <= max) {
            return String.join(", ", paths);
        }
        return String.join(", ", paths.subList(0, max)) + " +" + (paths.size() - max);
    }
}
