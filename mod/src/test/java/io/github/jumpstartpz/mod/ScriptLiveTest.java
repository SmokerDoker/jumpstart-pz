package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The server's world dictionary ids for new items, as the commit carries them to the clients. */
class ScriptLiveTest {
    @Test
    void itemIdsTravelAsLinesAndComeBackTheSame() {
        Map<String, Short> ids = new LinkedHashMap<>();
        ids.put("Guns.Pistol", (short) 4711);
        ids.put("Guns.Ammo=Box", (short) -2);
        String lines = ScriptLive.encode(ids);
        assertEquals("Guns.Pistol=4711\nGuns.Ammo=Box=-2\n", lines);
        assertEquals(ids, ScriptLive.decode(lines));
    }

    @Test
    void brokenLinesAreLeftOut() {
        assertEquals(Map.of("A.B", (short) 1), ScriptLive.decode("A.B=1\nnonsense\nC.D=x\n=5\n"));
        assertEquals(Map.of(), ScriptLive.decode(null));
    }
}
