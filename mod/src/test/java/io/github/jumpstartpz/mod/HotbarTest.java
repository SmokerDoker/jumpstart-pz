package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Switching off a hotbar mod builds the hotbar again; other mods leave it alone. */
class HotbarTest {

    @Test
    void aModThatChangedISHotbarOrReplacesTheHotbarTouchesIt() {
        LuaJournal.Change render = new LuaJournal.Change(null, "render", "ISHotbar.render", null, null);
        assertTrue(GameActions.touchesHotbar(List.of(render), List.of()));
        assertTrue(GameActions.touchesHotbar(List.of(), List.of("getPlayerData(0).playerHotbar = KnoxHotbar:new(p)")));
        LuaJournal.Change other = new LuaJournal.Change(null, "render", "ISInventoryPage.render", null, null);
        assertFalse(GameActions.touchesHotbar(List.of(other), List.of("print('hello')")));
    }

    @Test
    void theRebuildUsesTheGamesOwnHotbarCode() {
        String lua = ReflectiveLuaGame.REBUILD_HOTBARS;
        assertTrue(lua.contains("ISHotbar:new(playerObj)") && lua.contains("removeFromUIManager") && lua.contains("data.playerHotbar = hotbar"));
    }
}
