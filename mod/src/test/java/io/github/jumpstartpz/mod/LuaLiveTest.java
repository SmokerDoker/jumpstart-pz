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

        @Override public boolean inGame() { return inGame; }
        @Override public boolean multiplayer() { return multiplayer; }
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
