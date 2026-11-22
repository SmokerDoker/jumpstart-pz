package io.github.jumpstartpz.core;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import io.github.jumpstartpz.core.fixtures.CallLog;
import io.github.jumpstartpz.core.fixtures.Greeter;
import io.github.jumpstartpz.core.fixtures.ModAAdvice;
import io.github.jumpstartpz.core.fixtures.ModBAdvice;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feasibility test for the core idea: mods switched on and off live, without touching each other.
 * The fixtures stand in for game classes and two Advice-only Java mods.
 */
class HotswapEngineTest {
    private static final String GREETER = Greeter.class.getName();
    private static final String LATE_GREETER = "io.github.jumpstartpz.core.fixtures.LateGreeter";

    private static Instrumentation instrumentation;
    private static HotswapEngine engine;

    @BeforeAll
    static void installAgent() {
        instrumentation = ByteBuddyAgent.install();
        engine = new HotswapEngine(instrumentation);
    }

    @AfterEach
    void switchEverythingOff() {
        for (String mod : engine.enabledMods()) {
            engine.disable(mod);
        }
        CallLog.drain();
    }

    @Test
    void switchesAModOnAndOffInAnAlreadyLoadedClass() {
        Greeter greeter = new Greeter();
        assertEquals("vanilla", greeter.greet());
        assertEquals(List.of(), CallLog.drain());

        engine.enable("mod-a", List.of(new PatchSpec(GREETER, "greet", ModAAdvice.class)));
        assertEquals("vanilla", greeter.greet());
        assertEquals(List.of("A"), CallLog.drain());

        engine.disable("mod-a");
        assertEquals("vanilla", greeter.greet());
        assertEquals(List.of(), CallLog.drain());
    }

    @Test
    void switchingOneModOffKeepsTheOtherOnTheSameMethod() {
        Greeter greeter = new Greeter();
        engine.enable("mod-a", List.of(new PatchSpec(GREETER, "greet", ModAAdvice.class)));
        engine.enable("mod-b", List.of(new PatchSpec(GREETER, "greet", ModBAdvice.class)));
        greeter.greet();
        assertEquals(Set.of("A", "B"), Set.copyOf(CallLog.drain()));

        engine.disable("mod-a");
        greeter.greet();
        assertEquals(List.of("B"), CallLog.drain());

        engine.enable("mod-a", List.of(new PatchSpec(GREETER, "greet", ModAAdvice.class)));
        greeter.greet();
        assertEquals(Set.of("A", "B"), Set.copyOf(CallLog.drain()));

        engine.disable("mod-b");
        greeter.greet();
        assertEquals(List.of("A"), CallLog.drain());
    }

    @Test
    void patchesAClassThatLoadsAfterTheModWasSwitchedOnAndCanStillRemoveIt() throws Exception {
        assertFalse(isLoaded(LATE_GREETER), "fixture must not be loaded before the mod is on");

        engine.enable("mod-a", List.of(new PatchSpec(LATE_GREETER, "greet", ModAAdvice.class)));
        Class<?> lateClass = Class.forName(LATE_GREETER);
        Object late = lateClass.getConstructor().newInstance();
        var greet = lateClass.getMethod("greet");

        assertEquals("late", greet.invoke(late));
        assertEquals(List.of("A"), CallLog.drain());

        engine.disable("mod-a");
        assertEquals("late", greet.invoke(late));
        assertEquals(List.of(), CallLog.drain());
    }

    @Test
    void survivesRepeatedToggling() {
        Greeter greeter = new Greeter();
        List<PatchSpec> modA = List.of(new PatchSpec(GREETER, "greet", ModAAdvice.class));
        for (int i = 0; i < 200; i++) {
            engine.enable("mod-a", modA);
            greeter.greet();
            assertEquals(List.of("A"), CallLog.drain(), "on, round " + i);

            engine.disable("mod-a");
            greeter.greet();
            assertEquals(List.of(), CallLog.drain(), "off, round " + i);
        }
    }

    @Test
    void refusesToSwitchAModOnTwiceOrOffWhenItIsNotOn() {
        List<PatchSpec> modA = List.of(new PatchSpec(GREETER, "greet", ModAAdvice.class));
        engine.enable("mod-a", modA);
        assertThrows(HotswapException.class, () -> engine.enable("mod-a", modA));
        assertTrue(engine.isEnabled("mod-a"));

        assertThrows(HotswapException.class, () -> engine.disable("mod-b"));
        assertThrows(HotswapException.class, () -> engine.enable("empty", List.of()));
    }

    private static boolean isLoaded(String className) {
        return Arrays.stream(instrumentation.getAllLoadedClasses())
                .anyMatch(c -> c.getName().equals(className));
    }
}
