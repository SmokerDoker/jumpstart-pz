package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Loading a mod's Lua part into a running game, against a fake game. */
class LuaLiveTest {

    @Test
    void runsSharedClientServerInTheGamesOrderAndSendsMissedStartEventsOnlyToTheNewHandlers() {
        FakeGame game = new FakeGame();
        game.files.put("media/lua/client/Minimap_UI.lua", "/mods/Minimap/42/media/lua/client/Minimap_UI.lua");
        game.files.put("media/lua/client/minimap.lua", "/mods/Minimap/42/media/lua/client/minimap.lua");
        game.files.put("media/lua/shared/Minimap_Defs.lua", "/mods/Minimap/common/media/lua/shared/Minimap_Defs.lua");
        game.files.put("media/lua/server/Minimap_Server.lua", "/mods/Minimap/42/media/lua/server/Minimap_Server.lua");
        game.files.put("media/textures/minimap.png", "/mods/Minimap/42/media/textures/minimap.png");
        game.handlers.put("OnGameStart", new ArrayList<>(List.of("other mod's handler")));

        LuaLive.Result r = LuaLive.load(game, "Minimap", "/mods/Minimap");

        assertEquals(LuaLive.Status.LOADED, r.status());
        assertEquals(4, r.files());
        assertEquals(List.of(
                "/mods/Minimap/common/media/lua/shared/Minimap_Defs.lua",
                "/mods/Minimap/42/media/lua/client/minimap.lua",
                "/mods/Minimap/42/media/lua/client/Minimap_UI.lua",
                "/mods/Minimap/42/media/lua/server/Minimap_Server.lua"), game.ran);
        assertEquals(List.of("OnGameStart: Minimap.onStart []", "OnCreatePlayer: Minimap.onPlayer [0.0, player]"), game.called,
                "only the handlers the mod just added get the events it missed");
        assertEquals(List.of(), r.needsRestart(), "textures load when used");
    }

    @Test
    void namesWhatWaitsForTheNextLoadOfTheSave() {
        assertEquals(List.of("scripts", "animations", "sandbox"), LuaLive.needsRestart(List.of(
                "media/sandbox-options.txt", "media/animsets/zombie/reallimbs/human.xml",
                "media/scripts/items_minimap.txt", "media/lua/client/a.lua", "media/textures/x.png")));
    }

    @Test
    void doesNothingOutsideASinglePlayerGameOrForAnActiveMod() {
        FakeGame game = new FakeGame();
        game.inGame = false;
        assertEquals(LuaLive.Status.NOT_IN_GAME, LuaLive.load(game, "Minimap", "/mods/Minimap").status());
        game.inGame = true;
        game.multiplayer = true;
        assertEquals(LuaLive.Status.MULTIPLAYER, LuaLive.load(game, "Minimap", "/mods/Minimap").status());
        game.multiplayer = false;
        game.active = true;
        assertEquals(LuaLive.Status.ALREADY_ACTIVE, LuaLive.load(game, "Minimap", "/mods/Minimap").status());
        assertTrue(game.ran.isEmpty());
        assertEquals(0, game.registered);
    }

    @Test
    void inMultiplayerOnlyForTheServerAndEachSideRunsItsOwnFolders() {
        FakeGame client = new FakeGame();
        client.multiplayer = true;
        client.files.put("media/lua/shared/a.lua", "/m/shared/a.lua");
        client.files.put("media/lua/client/b.lua", "/m/client/b.lua");
        client.files.put("media/lua/server/c.lua", "/m/server/c.lua");
        assertEquals(LuaLive.Status.MULTIPLAYER, LuaLive.load(client, "M", "/m").status(), "never alone");
        assertEquals(LuaLive.Status.LOADED, LuaLive.load(client, "M", "/m", true).status());
        assertEquals(List.of("/m/shared/a.lua", "/m/client/b.lua"), client.ran);

        FakeGame server = new FakeGame();
        server.server = true;
        server.files.putAll(client.files);
        assertEquals(LuaLive.Status.LOADED, LuaLive.load(server, "M", "/m", true).status());
        assertEquals(List.of("/m/shared/a.lua", "/m/server/c.lua"), server.ran);
    }

    @Test
    void aBrokenFileDoesNotStopTheOthers() {
        FakeGame game = new FakeGame();
        game.files.put("media/lua/client/a.lua", "/m/a.lua");
        game.files.put("media/lua/client/b.lua", "/m/b.lua");
        game.failing = "/m/a.lua";
        LuaLive.Result r = LuaLive.load(game, "M", "/m");
        assertEquals(LuaLive.Status.LOADED, r.status());
        assertEquals(List.of("/m/a.lua", "/m/b.lua"), game.ran);
        assertEquals(1, r.errors().size());
    }

    @Test
    void aModWithoutLuaIsOnlyRegistered() {
        FakeGame game = new FakeGame();
        game.files.put("media/scripts/items.txt", "/m/items.txt");
        LuaLive.Result r = LuaLive.load(game, "M", "/m");
        assertEquals(LuaLive.Status.NO_LUA, r.status());
        assertEquals(List.of("scripts"), r.needsRestart());
        assertEquals(1, game.registered);
    }

    /** A Lua table: identity, like Kahlua's. */
    private static final class Table {
        final Map<Object, Object> map = new HashMap<>();
    }

    /** A Lua function and the file it was defined in (normalized). */
    private static final class Fn {
        final String file;
        final String name;

        Fn(String file, String name) {
            this.file = file;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    void recordsWhatTheModChangedInTheGlobalsAndTakesItBackOnlyWhereItIsStillTheMods() {
        FakeGame game = new FakeGame();
        String file = "/mods/jst/42/media/lua/client/jst.lua";
        game.files.put("media/lua/client/jst.lua", file);
        Table luautils = new Table();
        Fn gameStringStarts = new Fn("/game/media/lua/shared/luautils.lua", "game stringStarts");
        luautils.map.put("stringStarts", gameStringStarts);
        luautils.map.put("counter", 1.0);
        game.env.map.put("luautils", luautils);
        Fn modStringStarts = new Fn(file, "mod stringStarts");
        Table panel = new Table();
        panel.map.put("prerender", new Fn(file, "JSTPanel.prerender"));
        game.scripts.put(file, () -> {
            luautils.map.put("stringStarts", modStringStarts); // replaces a game function
            luautils.map.put("counter", 2.0); // a value the game changes all the time: not the mod's
            game.env.map.put("JST_Marker", true);
            game.env.map.put("JSTPanel", panel);
        });

        LuaLive.Result r = LuaLive.load(game, "jst", "/mods/jst");

        assertEquals(LuaLive.Status.LOADED, r.status());
        assertEquals(List.of("JSTPanel", "JST_Marker", "luautils.stringStarts"),
                r.changes().stream().map(LuaJournal.Change::path).toList());

        LuaJournal.Outcome undo = LuaJournal.undo(game, r.changes());
        assertEquals(3, undo.done());
        assertTrue(undo.kept().isEmpty());
        assertEquals(gameStringStarts, luautils.map.get("stringStarts"), "the game's function is back");
        assertTrue(!game.env.map.containsKey("JST_Marker") && !game.env.map.containsKey("JSTPanel"), "new globals are gone");
        assertEquals(2.0, luautils.map.get("counter"), "the game's own values stay");

        LuaJournal.Outcome redo = LuaJournal.redo(game, r.changes());
        assertEquals(3, redo.done());
        assertEquals(modStringStarts, luautils.map.get("stringStarts"));
        assertEquals(panel, game.env.map.get("JSTPanel"));

        // another mod wraps the function now: switching off leaves its wrapper and says so
        Fn wrapper = new Fn("/mods/other/media/lua/client/other.lua", "other wrapper");
        luautils.map.put("stringStarts", wrapper);
        undo = LuaJournal.undo(game, r.changes());
        assertEquals(List.of("luautils.stringStarts"), undo.kept());
        assertEquals(wrapper, luautils.map.get("stringStarts"));
    }

    @Test
    void namesTheFunctionsOfAModThatCameWithTheSave() {
        FakeGame game = new FakeGame();
        Table luautils = new Table();
        luautils.map.put("stringStarts", new Fn("/mods/old/media/lua/client/old.lua", "old"));
        game.env.map.put("luautils", luautils);
        game.env.map.put("OldMod_init", new Fn("/mods/old/media/lua/client/old.lua", "init"));
        game.env.map.put("print", new Fn("/game/print.lua", "print"));
        assertEquals(List.of("OldMod_init", "luautils.stringStarts"),
                LuaJournal.functionsFrom(game, f -> f != null && f.startsWith("/mods/old/")));
    }

    private static final class FakeGame implements LuaLive.Game {
        boolean inGame = true;
        boolean multiplayer;
        boolean active;
        int registered;
        String failing;
        final Map<String, String> files = new LinkedHashMap<>();
        final Map<String, List<Object>> handlers = new HashMap<>();
        final List<String> ran = new ArrayList<>();
        final List<String> called = new ArrayList<>();
        /** Lua's globals: tables are {@link Table}, functions {@link Fn}; what a file does when it runs. */
        final Table env = new Table();
        final Map<String, Runnable> scripts = new HashMap<>();

        @Override public Object globalTable() { return env; }
        @Override public boolean isTable(Object v) { return v instanceof Table; }
        @Override public Map<Object, Object> entries(Object t) { return new HashMap<>(((Table) t).map); }
        @Override public Object get(Object t, Object k) { return ((Table) t).map.get(k); }

        @Override
        public void set(Object t, Object k, Object v) {
            if (v == null) {
                ((Table) t).map.remove(k);
            } else {
                ((Table) t).map.put(k, v);
            }
        }

        @Override public String sourceOf(Object v) { return v instanceof Fn f ? f.file : null; }

        @Override public boolean inGame() { return inGame; }
        @Override public boolean multiplayer() { return multiplayer; }
        boolean server;
        @Override public boolean isServer() { return server; }
        @Override public boolean isActive(String modId) { return active; }

        @Override
        public Map<String, String> register(String modId, String modDir) {
            registered++;
            Map<String, String> lower = new LinkedHashMap<>();
            files.forEach((k, v) -> lower.put(k.toLowerCase(java.util.Locale.ROOT), v));
            return lower;
        }

        @Override
        public void run(String absolutePath) {
            ran.add(absolutePath);
            if (absolutePath.equals(failing)) {
                throw new IllegalStateException("syntax error");
            }
            Runnable script = scripts.get(absolutePath);
            if (script != null) {
                script.run();
            }
            if (absolutePath.endsWith("Minimap_UI.lua")) {
                handlers.computeIfAbsent("OnGameStart", k -> new ArrayList<>()).add("Minimap.onStart");
                handlers.computeIfAbsent("OnCreatePlayer", k -> new ArrayList<>()).add("Minimap.onPlayer");
            }
        }

        @Override
        public Map<String, List<Object>> handlers(Collection<String> events) {
            Map<String, List<Object>> copy = new HashMap<>();
            for (String e : events) {
                copy.put(e, new ArrayList<>(handlers.getOrDefault(e, List.of())));
            }
            return copy;
        }

        @Override
        public void call(Object handler, Object[] args) {
            String event = handlers.entrySet().stream().filter(e -> e.getValue().contains(handler)).findFirst().orElseThrow().getKey();
            called.add(event + ": " + handler + " " + java.util.Arrays.toString(args));
        }

        @Override
        public Object[] argsFor(String event) {
            return event.equals("OnCreatePlayer") ? new Object[] {0.0, "player"} : new Object[0];
        }

        @Override public void reloadTranslations() { }
    }
}
