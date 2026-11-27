package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SaveReloadTest {

    private static final class FakeGame implements SaveReload.Game {
        String state = "IngameState";
        boolean menu;
        String[] latest = {"2026-11-27_14-00-00", "Sandbox"};
        final List<String> continued = new ArrayList<>();

        @Override public String state() { return state; }
        @Override public boolean menuReady() { return menu; }
        @Override public String[] latestSave() { return latest; }
        @Override public void continueSave(String world, String gameMode) { continued.add(gameMode + "/" + world); }
    }

    @AfterEach
    void reset() {
        SaveReload.reset();
    }

    private static void frames(int n, long now) {
        for (int i = 0; i < n; i++) {
            SaveReload.onFrame(now);
        }
    }

    @Test
    void continuesTheSaveOnceTheMainMenuHasSettled() {
        FakeGame game = new FakeGame();
        SaveReload.request("2026-11-27_14-00-00", game, 0);
        frames(100, 10);
        assertTrue(game.continued.isEmpty(), "still in the game: it saves first");
        game.state = "MainScreenState";
        frames(100, 20);
        assertTrue(game.continued.isEmpty(), "the menu's Lua screen is not there yet");
        game.menu = true;
        frames(SaveReload.SETTLE_FRAMES - 1, 30);
        assertTrue(game.continued.isEmpty(), "the menu settles first");
        frames(1, 30);
        assertEquals(List.of("Sandbox/2026-11-27_14-00-00"), game.continued);
        assertFalse(SaveReload.waiting());
        frames(200, 40);
        assertEquals(1, game.continued.size(), "once");
    }

    @Test
    void anotherLatestSaveIsNotContinued() {
        FakeGame game = new FakeGame();
        game.state = "MainScreenState";
        game.menu = true;
        game.latest = new String[] {"other", "Sandbox"};
        SaveReload.request("2026-11-27_14-00-00", game, 0);
        frames(SaveReload.SETTLE_FRAMES + 5, 10);
        assertTrue(game.continued.isEmpty());
        assertFalse(SaveReload.waiting(), "the player is in the main menu as after a normal quit");
    }

    @Test
    void givesUpAfterTheTimeout() {
        FakeGame game = new FakeGame();
        SaveReload.request("w", game, 0);
        frames(1, SaveReload.TIMEOUT_MS + 1);
        assertFalse(SaveReload.waiting());
        game.state = "MainScreenState";
        game.menu = true;
        game.latest = new String[] {"w", "Sandbox"};
        frames(SaveReload.SETTLE_FRAMES + 5, SaveReload.TIMEOUT_MS + 2);
        assertTrue(game.continued.isEmpty());
    }

    @Test
    void theMenuMayNameTheSaveWithItsModeFolder() {
        assertTrue(SaveReload.sameWorld("Sandbox/My Save", "my save"));
        assertTrue(SaveReload.sameWorld("Sandbox\\My Save", "My Save"));
        assertTrue(SaveReload.sameWorld("My Save", "My Save"));
        assertFalse(SaveReload.sameWorld("Sandbox/Other", "My Save"));
        assertFalse(SaveReload.sameWorld("Sandbox/x", ""));
    }
}
