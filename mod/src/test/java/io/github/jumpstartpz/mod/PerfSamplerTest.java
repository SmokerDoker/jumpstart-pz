package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PerfSamplerTest {

    private static final PerfSampler.Owners OWNERS = new PerfSampler.Owners(
            Map.of("com.swords.", "SwordMod", "com.swords.extra.", "SwordExtra"),
            Map.of("c:/zomboid/mods/maps/", "MapMod"));

    @AfterEach
    void tearDown() {
        PerfSampler.useForTest(null);
    }

    private static StackTraceElement frame(String cls) {
        return new StackTraceElement(cls, "run", null, 1);
    }

    private static PerfSampler.Probe probe(String luaFile, StackTraceElement... stack) {
        return new PerfSampler.Probe() {
            @Override public StackTraceElement[] mainStack() { return stack; }
            @Override public String luaFile() { return luaFile; }
        };
    }

    private static String[] owner(String luaFile, StackTraceElement... stack) {
        return PerfSampler.ownerOf(stack, probe(luaFile, stack), OWNERS);
    }

    @Test
    void aModsJavaCodeCountsForItEvenWhenItCallsTheGame() {
        assertArrayEquals(new String[] {"SwordMod", "java"},
                owner(null, frame("zombie.iso.IsoGridSquare"), frame("com.swords.Damage"), frame("zombie.GameWindow")));
        assertArrayEquals(new String[] {"SwordExtra", "java"}, owner(null, frame("com.swords.extra.Glow")), "the longest package");
        assertArrayEquals(new String[] {"SwordMod", "java"}, owner(null, frame("jsv3.com.swords.Damage")), "a live update's package");
        assertNull(owner(null, frame("zombie.GameWindow"), frame("java.lang.Thread")), "the game itself");
    }

    @Test
    void insideLuaTheRunningFileDecides() {
        StackTraceElement[] lua = {frame("zombie.characters.IsoPlayer"), frame("se.krka.kahlua.vm.KahluaThread"),
                frame("zombie.Lua.LuaEventManager"), frame("com.swords.Events"), frame("zombie.GameWindow")};
        assertArrayEquals(new String[] {"MapMod", "lua"}, owner("c:/zomboid/mods/maps/media/lua/client/draw.lua", lua),
                "a Lua mod's call into the game counts for the Lua mod, not the Java mod that fired the event");
        assertArrayEquals(new String[] {"SwordMod", "java"}, owner("c:/game/media/lua/client/vanilla.lua", lua),
                "vanilla Lua: the Java mod further out that called it");
        assertNull(owner(null, frame("se.krka.kahlua.vm.KahluaThread"), frame("zombie.GameWindow")));
    }

    @Test
    void aResultGivesMillisecondsPerFrameAndShares() {
        PerfSampler.Result r = new PerfSampler.Result(10_000, 500, 1000,
                Map.of("SwordMod", new PerfSampler.Share(30, 20), "MapMod", new PerfSampler.Share(0, 10)));
        assertEquals(20.0, r.frameMs(), 1e-9);
        assertEquals(5.0, r.percent("SwordMod"), 1e-9);
        assertEquals(1.0, r.msPerFrame("SwordMod"), 1e-9, "5 % of 20 ms");
        assertEquals(0.2, r.msPerFrame("MapMod"), 1e-9);
        assertEquals(0.0, r.msPerFrame("Unknown"), 1e-9);
    }

    @Test
    void aMeasurementSamplesInTheBackgroundAndFinishes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StackTraceElement[] stack = {frame("com.swords.Damage")};
        PerfSampler.Probe p = new PerfSampler.Probe() {
            @Override
            public StackTraceElement[] mainStack() {
                return calls.incrementAndGet() % 2 == 0 ? stack : new StackTraceElement[] {frame("zombie.GameWindow")};
            }

            @Override public String luaFile() { return null; }
        };
        assertNull(PerfSampler.start(OWNERS, p, 200, 1));
        assertTrue(PerfSampler.running());
        assertEquals("running", PerfSampler.start(OWNERS, p, 200, 1), "one at a time");
        for (int i = 0; i < 5; i++) {
            PerfSampler.onFrame();
        }
        long until = System.currentTimeMillis() + 5_000;
        while (PerfSampler.running() && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        PerfSampler.Result r = PerfSampler.last();
        assertTrue(r != null && !PerfSampler.running());
        assertEquals(5, r.frames());
        assertTrue(r.samples() > 10, "samples: " + r.samples());
        assertEquals(r.samples() / 2, r.mods().get("SwordMod").java(), 1, "every second sample was in the mod");
    }
}
