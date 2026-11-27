package io.github.jumpstartpz.mod;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The game's Lua checksum in multiplayer, worked out again after Jumpstart installed or removed a
 * mod live.
 *
 * <p>Server and client each hash every Lua file they load, once (LuaManager.checksumDone): the
 * server at its start (shared, client, server), the client when it joins. A player who joins after a
 * live install has the new mod's files in their sum, the server does not, and the game turns them
 * away ("Lua checksum"). This runs the game's own steps again without running any Lua:
 * {@code LuaManager.initChecksum}, {@code LoadDirBase(dir, true)} for shared, client and server
 * (the second argument: list and hash only), {@code finishChecksum}, which sets
 * {@code GameServer.checksum} or {@code GameClient.checksum}.
 *
 * <p>The game keeps the per-file sums for its file-by-file comparison in
 * {@code NetChecksum$GroupOfFiles.groups}, the Lua ones first, then those of item scripts and
 * animations. The Lua groups are built again, the others kept behind them.
 *
 * <p>Main thread (server thread) only, like the game's own loading.
 */
final class LuaChecksum {
    private LuaChecksum() {
    }

    /** Works the checksum out again; returns it, or throws when the game's code is not as expected. */
    static String recompute() throws ReflectiveOperationException {
        Class<?> lua = Class.forName("zombie.Lua.LuaManager");
        Class<?> groups = Class.forName("zombie.network.NetChecksum$GroupOfFiles");
        Field done = lua.getField("checksumDone");
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) groups.getField("groups").get(null);
        List<Object> others = new ArrayList<>();
        for (Object g : list) {
            if (!isLua(g)) {
                others.add(g);
            }
        }
        done.setBoolean(null, false);
        try {
            lua.getMethod("initChecksum").invoke(null);
            groups.getMethod("initChecksum").invoke(null);
            java.lang.reflect.Method load = lua.getMethod("LoadDirBase", String.class, boolean.class);
            for (String dir : List.of("shared", "client", "server")) {
                load.invoke(null, dir, true);
            }
            lua.getMethod("finishChecksum").invoke(null);
        } finally {
            done.setBoolean(null, true);
            list.addAll(others);
        }
        return current();
    }

    /** The checksum the game has now: the server's on a server, else the client's; "" when none. */
    static String current() {
        try {
            boolean server = Class.forName("zombie.network.GameServer").getField("server").getBoolean(null);
            Object sum = Class.forName(server ? "zombie.network.GameServer" : "zombie.network.GameClient").getField("checksum").get(null);
            return sum == null ? "" : sum.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /** False after a failed {@link #selfTest}: the checksum is left as the game made it. */
    private static volatile boolean trusted = true;

    static boolean trusted() {
        return trusted;
    }

    /**
     * Server start: works the checksum out again with nothing changed. It must come out as the
     * game's own; if not (the game loads its Lua otherwise than this copies), the game's checksum and
     * groups go back and Jumpstart neither changes nor compares it later.
     */
    static boolean selfTest() {
        String before = current();
        List<Object> saved;
        try {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) Class.forName("zombie.network.NetChecksum$GroupOfFiles").getField("groups").get(null);
            saved = new ArrayList<>(list);
        } catch (ReflectiveOperationException | RuntimeException e) {
            trusted = false;
            System.out.println("[Jumpstart] Lua checksum: cannot read the game's groups (" + e + "); not changed after live installs");
            return false;
        }
        if (before.isEmpty()) {
            trusted = false;
            System.out.println("[Jumpstart] Lua checksum: the game has none yet; not changed after live installs");
            return false;
        }
        String after;
        try {
            after = recompute();
        } catch (ReflectiveOperationException | RuntimeException e) {
            after = "failed: " + e;
        }
        if (before.equals(after)) {
            System.out.println("[Jumpstart] Lua checksum: worked out again, same as the game's (" + before + ")");
            return true;
        }
        trusted = false;
        try {
            Class.forName("zombie.network.GameServer").getField("checksum").set(null, before);
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) Class.forName("zombie.network.NetChecksum$GroupOfFiles").getField("groups").get(null);
            list.clear();
            list.addAll(saved);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] Lua checksum: cannot put the game's back: " + e);
        }
        System.out.println("[Jumpstart] Lua checksum: worked out again it is " + after + ", the game's is " + before
                + "; left as the game made it, not changed after live installs (players who join after one may need a server restart)");
        return false;
    }

    /** Works it out again; "" (and a line in the log) when that fails or the self test failed. */
    static String recomputeQuietly(String why) {
        if (!trusted) {
            return "";
        }
        try {
            String before = current();
            String after = recompute();
            System.out.println("[Jumpstart] Lua checksum after " + why + ": " + (before.equals(after) ? "unchanged" : before + " -> " + after));
            return after;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot work out the Lua checksum again after " + why + ": " + e);
            return "";
        }
    }

    /** A group of the Lua files: every path in it ends with .lua. */
    private static boolean isLua(Object group) {
        try {
            short count = group.getClass().getField("fileCount").getShort(group);
            String[] paths = (String[]) group.getClass().getField("relPaths").get(group);
            if (count == 0) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                if (paths[i] == null || !paths[i].toLowerCase(java.util.Locale.ROOT).endsWith(".lua")) {
                    return false;
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
