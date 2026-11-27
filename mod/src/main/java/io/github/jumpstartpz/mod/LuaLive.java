package io.github.jumpstartpz.mod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads the Lua part of a mod into a running single player game, the way the game would have
 * loaded it at the start: the mod's files join the game's file system (so {@code require},
 * textures and translations find them), its Lua files run in the game's order (shared, client,
 * server; each folder sorted), and the start-up events the mod missed reach only the handlers it
 * just registered (OnGameStart, OnCreatePlayer, ...). Other mods do not see those events again.
 *
 * <p>Item and recipe scripts load too, before the mod's Lua (its Lua may use its items), only the
 * mod's own files ({@link Game#loadScripts}); after the Lua the loot tables are read again and the
 * animation sets refreshed. Parts the game reads only while a save loads (maps, sandbox options,
 * sounds, models, tiles, ...) are named in the result: they come with the next load of the save.
 */
final class LuaLive {

    /** RESUMED: a mod switched off in this session got its event handlers back. */
    enum Status { LOADED, RESUMED, NOT_IN_GAME, MULTIPLAYER, ALREADY_ACTIVE, NO_LUA, FAILED }

    /**
     * {@code needsRestart}: keys of {@link #RESTART_PARTS} the mod has. {@code changes}: what its
     * Lua changed in the globals ({@link LuaJournal}), taken back when it is switched off.
     */
    record Result(Status status, int files, List<String> errors, List<String> needsRestart, List<LuaJournal.Change> changes,
                  List<String> live) {
        Result(Status status, int files, List<String> errors, List<String> needsRestart) {
            this(status, files, errors, needsRestart, List.of(), List.of());
        }

        Result(Status status, int files, List<String> errors, List<String> needsRestart, List<LuaJournal.Change> changes) {
            this(status, files, errors, needsRestart, changes, List.of());
        }

        static Result of(Status status) {
            return new Result(status, 0, List.of(), List.of());
        }

        @Override
        public String toString() {
            return "Result[" + status + ", files=" + files + ", errors=" + errors + ", needsRestart=" + needsRestart
                    + ", live=" + live + ", globals changed=" + changes.size() + "]";
        }
    }

    /** Where the Lua runs: each side runs other folders of the mod (as the game does at the start). */
    enum Side {
        SINGLE_PLAYER(List.of("shared", "client", "server")),
        CLIENT(List.of("shared", "client")),
        SERVER(List.of("shared", "server"));

        final List<String> folders;

        Side(List<String> folders) {
            this.folders = folders;
        }
    }

    /** The running game. {@link ReflectiveLuaGame} is PZ; tests use a fake. */
    interface Game {
        /** A save is loaded (not the main menu), or this is a running server. */
        boolean inGame();

        /** A multiplayer client. */
        boolean multiplayer();

        /** A server (dedicated or hosted). */
        boolean isServer();

        /** The mod is in the running game's mod list already (its Lua ran at the start). */
        boolean isActive(String modId);

        /**
         * Adds the mod to the game's file system and mod list; returns the mod's files as the
         * game sees them: relative path (lower case, '/') to absolute path.
         */
        Map<String, String> register(String modId, String modDir) throws Exception;

        /** Runs one Lua file the way the game does at the start. */
        void run(String absolutePath) throws Exception;

        /** The handlers registered right now for each of the events (copies). */
        Map<String, List<Object>> handlers(Collection<String> events);

        void call(Object handler, Object[] args) throws Exception;

        /** The arguments the game passes with the event, for the local player. */
        Object[] argsFor(String event);

        void reloadTranslations() throws Exception;

        // --- Lua's globals, for LuaJournal (defaults: a game without them; nothing is recorded) ---

        /** Lua's global table, or null. */
        default Object globalTable() {
            return null;
        }

        default boolean isTable(Object value) {
            return false;
        }

        /** A copy of a table's entries (raw, no metatables). */
        default Map<Object, Object> entries(Object table) {
            return Map.of();
        }

        /** A table entry (raw); null for nil. */
        default Object get(Object table, Object key) {
            return null;
        }

        /** Sets a table entry (raw); null removes it. */
        default void set(Object table, Object key, Object value) {
        }

        /** The file a Lua function was defined in (normalized: lower case, '/'), or null for anything else. */
        default String sourceOf(Object value) {
            return null;
        }

        // --- data the game reads at the start (defaults: a game without them; they wait for the next load) ---

        /**
         * Loads the scripts in the mod's script files (relative paths, the game's order) into the running
         * game: new items, recipes and the rest, and the scripts the mod changes. Null when this game
         * cannot (then they come with the next load).
         */
        default Scripts loadScripts(String modId, List<String> scriptFiles) throws Exception {
            return null;
        }

        /** After the mod's Lua: what the new scripts do once Lua ran, then (if asked) the loot tables read again. */
        default void afterLua(boolean distributions) throws Exception {
        }

        /** The animation sets and action groups read again, with the mod's; false when this game cannot. */
        default boolean reloadAnimations() throws Exception {
            return false;
        }
    }

    /** What {@link Game#loadScripts} brought: scripts new or changed, the new items' full types, problems. */
    record Scripts(int scripts, List<String> newItems, List<String> errors) {
    }

    /** Start-up events a late mod missed, in the order the game sends them. */
    static final List<String> START_EVENTS = List.of(
            "OnGameBoot", "OnPreDistributionMerge", "OnDistributionMerge", "OnPostDistributionMerge",
            "OnInitWorld", "OnInitGlobalModData", "OnGameStart", "OnLoad", "OnCreatePlayer");

    /** Parts that need the save loaded again: key, then what the relative path starts with or ends with. */
    static final Map<String, List<String>> RESTART_PARTS = orderedMap(
            "scripts", List.of("media/scripts/"),
            "maps", List.of("media/maps/"),
            "animations", List.of("media/animsets/", "media/anims/", "media/anims_x/", "media/actiongroups/"),
            "sandbox", List.of("media/sandbox-options.txt"),
            "sounds", List.of("media/sound/", "media/sounds/", ".bank"),
            "models", List.of("media/models/", "media/models_x/"),
            "tiles", List.of("media/tiledefinitions", ".tiles", "media/texturepacks/", ".pack"),
            "clothing", List.of("media/clothing/"));

    private LuaLive() {
    }

    /** A single player game; a multiplayer client only with {@code forServer} (see the other load). */
    static Result load(Game game, String modId, String modDir) {
        return load(game, modId, modDir, false);
    }

    /**
     * @param forServer the server asked everybody to install this mod (Jumpstart's multiplayer round);
     *                  without it a multiplayer client loads nothing alone
     */
    static Result load(Game game, String modId, String modDir, boolean forServer) {
        if (!game.inGame()) {
            return Result.of(Status.NOT_IN_GAME);
        }
        Side side = game.isServer() ? Side.SERVER : game.multiplayer() ? Side.CLIENT : Side.SINGLE_PLAYER;
        if (side == Side.CLIENT && !forServer) {
            return Result.of(Status.MULTIPLAYER);
        }
        if (game.isActive(modId)) {
            return Result.of(Status.ALREADY_ACTIVE);
        }
        Map<String, String> files;
        try {
            files = game.register(modId, modDir);
        } catch (Exception e) {
            return new Result(Status.FAILED, 0, List.of(String.valueOf(e)), List.of());
        }
        List<String> restart = new ArrayList<>(needsRestart(files.keySet()));
        List<String> live = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean scriptsLive = loadScripts(game, modId, files, restart, live, errors);
        List<String> lua = luaOrder(files, side);
        if (lua.isEmpty()) {
            afterLua(game, side, scriptsLive, false, errors);
            reloadAnimations(game, restart, live, errors);
            return new Result(Status.NO_LUA, 0, errors, restart, List.of(), live);
        }
        Set<String> modFiles = new java.util.HashSet<>();
        for (String file : files.values()) {
            modFiles.add(file.replace('\\', '/').toLowerCase(Locale.ROOT));
        }
        LuaJournal.Snapshot globalsBefore = LuaJournal.snapshot(game);
        Map<String, List<Object>> before = game.handlers(START_EVENTS);
        for (String file : lua) {
            try {
                game.run(file);
            } catch (Exception e) {
                errors.add(fileName(file) + ": " + e);
            }
        }
        if (files.keySet().stream().anyMatch(f -> f.startsWith("media/lua/shared/translate/"))) {
            try {
                game.reloadTranslations();
            } catch (Exception e) {
                errors.add("translations: " + e);
            }
        }
        Map<String, List<Object>> after = game.handlers(START_EVENTS);
        for (String event : START_EVENTS) {
            if (side == Side.SERVER && event.equals("OnCreatePlayer")) {
                continue; // a server has no local player
            }
            Set<Object> old = Collections.newSetFromMap(new IdentityHashMap<>());
            old.addAll(before.getOrDefault(event, List.of()));
            for (Object handler : after.getOrDefault(event, List.of())) {
                if (old.contains(handler)) {
                    continue;
                }
                try {
                    game.call(handler, game.argsFor(event));
                } catch (Exception e) {
                    errors.add(event + ": " + e);
                }
            }
        }
        boolean serverLua = side.folders.contains("server") && files.keySet().stream().anyMatch(f -> f.startsWith("media/lua/server/") && f.endsWith(".lua"));
        afterLua(game, side, scriptsLive, serverLua, errors);
        reloadAnimations(game, restart, live, errors);
        // after the missed start events too: handlers often set up globals then
        List<LuaJournal.Change> changes = LuaJournal.diff(game, globalsBefore, LuaJournal.snapshot(game), modFiles::contains);
        return new Result(Status.LOADED, lua.size(), errors, restart, changes, live);
    }

    /** The mod's script files (media/scripts/**.txt), in the order the game sorts them: by file name. */
    static List<String> scriptFiles(Collection<String> relativeFiles) {
        List<String> scripts = new ArrayList<>();
        for (String f : relativeFiles) {
            String lower = f.toLowerCase(Locale.ROOT);
            if (lower.startsWith("media/scripts/") && lower.endsWith(".txt")) {
                scripts.add(f);
            }
        }
        scripts.sort(java.util.Comparator.comparing((String f) -> fileName(f).toLowerCase(Locale.ROOT)).thenComparing(f -> f));
        return scripts;
    }

    /** Its scripts into the running game, before its Lua. True when they came in. */
    private static boolean loadScripts(Game game, String modId, Map<String, String> files, List<String> restart,
                                       List<String> live, List<String> errors) {
        List<String> scripts = scriptFiles(files.keySet());
        if (scripts.isEmpty()) {
            return false;
        }
        try {
            Scripts r = game.loadScripts(modId, scripts);
            if (r == null) {
                return false;
            }
            errors.addAll(r.errors());
            restart.remove("scripts");
            live.add("scripts:" + r.scripts() + ":" + r.newItems().size());
            return true;
        } catch (Exception e) {
            errors.add("scripts: " + e);
            return false;
        }
    }

    /**
     * After its Lua: the new scripts' after-Lua step; the loot tables read again where loot is made (not on
     * a multiplayer client: the server spawns loot) when it brought items or ran server Lua.
     */
    private static void afterLua(Game game, Side side, boolean scriptsLive, boolean serverLua, List<String> errors) {
        if (!scriptsLive && !serverLua) {
            return;
        }
        try {
            game.afterLua(side != Side.CLIENT);
        } catch (Exception e) {
            errors.add("loot tables: " + e);
        }
    }

    private static void reloadAnimations(Game game, List<String> restart, List<String> live, List<String> errors) {
        if (!restart.contains("animations")) {
            return;
        }
        try {
            if (game.reloadAnimations()) {
                restart.remove("animations");
                live.add("animations");
            }
        } catch (Exception e) {
            errors.add("animations: " + e);
        }
    }

    /** The Lua files to run: the side's folders in the game's order (shared, client, server), each sorted. */
    static List<String> luaOrder(Map<String, String> files, Side side) {
        List<String> order = new ArrayList<>();
        for (String folder : side.folders) {
            String prefix = "media/lua/" + folder + "/";
            List<String> keys = new ArrayList<>();
            for (String key : files.keySet()) {
                if (key.startsWith(prefix) && key.endsWith(".lua")) {
                    keys.add(key);
                }
            }
            keys.sort(String.CASE_INSENSITIVE_ORDER);
            for (String key : keys) {
                order.add(files.get(key));
            }
        }
        return order;
    }

    static List<String> needsRestart(Collection<String> relativeFiles) {
        Set<String> parts = new LinkedHashSet<>();
        for (String file : relativeFiles) {
            String f = file.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, List<String>> part : RESTART_PARTS.entrySet()) {
                for (String marker : part.getValue()) {
                    if (marker.startsWith("media/") ? f.startsWith(marker) : f.endsWith(marker)) {
                        parts.add(part.getKey());
                    }
                }
            }
        }
        List<String> ordered = new ArrayList<>();
        for (String key : RESTART_PARTS.keySet()) {
            if (parts.contains(key)) {
                ordered.add(key);
            }
        }
        return ordered;
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> orderedMap(Object... keyValues) {
        Map<String, List<String>> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], (List<String>) keyValues[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
