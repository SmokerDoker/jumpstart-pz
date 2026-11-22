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
 * <p>Parts the game reads only while a save loads (items and recipes, maps, animations, sandbox
 * options, sounds, ...) are named in the result: they come with the next load of the save.
 */
final class LuaLive {

    enum Status { LOADED, NOT_IN_GAME, MULTIPLAYER, ALREADY_ACTIVE, NO_LUA, FAILED }

    /** {@code needsRestart}: keys of {@link #RESTART_PARTS} the mod has. */
    record Result(Status status, int files, List<String> errors, List<String> needsRestart) {
        static Result of(Status status) {
            return new Result(status, 0, List.of(), List.of());
        }
    }

    /** The running game. {@link ReflectiveLuaGame} is PZ; tests use a fake. */
    interface Game {
        /** A save is loaded (not the main menu). */
        boolean inGame();

        boolean multiplayer();

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
    }

    /** Start-up events a late mod missed, in the order the game sends them. */
    static final List<String> START_EVENTS = List.of(
            "OnGameBoot", "OnInitWorld", "OnInitGlobalModData", "OnGameStart", "OnLoad", "OnCreatePlayer");

    /** Parts that need the save loaded again: key, then what the relative path starts with or ends with. */
    static final Map<String, List<String>> RESTART_PARTS = orderedMap(
            "scripts", List.of("media/scripts/"),
            "maps", List.of("media/maps/"),
            "animations", List.of("media/animsets/", "media/anims/", "media/anims_x/"),
            "sandbox", List.of("media/sandbox-options.txt"),
            "sounds", List.of("media/sound/", "media/sounds/", ".bank"),
            "models", List.of("media/models/", "media/models_x/"),
            "tiles", List.of("media/tiledefinitions", ".tiles", "media/texturepacks/", ".pack"),
            "clothing", List.of("media/clothing/"));

    private LuaLive() {
    }

    static Result load(Game game, String modId, String modDir) {
        if (!game.inGame()) {
            return Result.of(Status.NOT_IN_GAME);
        }
        if (game.multiplayer()) {
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
        List<String> restart = needsRestart(files.keySet());
        List<String> lua = luaOrder(files, true);
        if (lua.isEmpty()) {
            return new Result(Status.NO_LUA, 0, List.of(), restart);
        }
        List<String> errors = new ArrayList<>();
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
        return new Result(Status.LOADED, lua.size(), errors, restart);
    }

    /** The Lua files to run: shared, client, then server (single player runs all three), each sorted. */
    static List<String> luaOrder(Map<String, String> files, boolean withServer) {
        List<String> order = new ArrayList<>();
        for (String folder : withServer ? List.of("shared", "client", "server") : List.of("shared", "client")) {
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
