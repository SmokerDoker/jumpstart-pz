package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * GameHooks writes calls into the game's own classes. The game's code may only call public static
 * methods of public classes; anything else ends the game at once with an IllegalAccessError.
 */
class GameHooksTest {

    @Test
    void everythingTheGameCallsIsPublicStatic() {
        Map<Class<?>, List<String>> called = Map.of(
                SecureScreen.class, List.of("onRender", "blocksInput"),
                FileJournal.class, List.of("onFileWriter", "onModFileWriter"),
                ModDataJournal.class, List.of("onKey"),
                LuaStartJournal.class, List.of("onLoadDir", "onSet"),
                io.github.jumpstartpz.mod.net.Net.class, List.of("clientEvent", "serverEvent", "serverTick", "worldPaused"));
        called.forEach((type, methods) -> {
            assertTrue(Modifier.isPublic(type.getModifiers()), type.getSimpleName() + " must be public");
            for (String name : methods) {
                List<Method> found = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name)).toList();
                assertTrue(!found.isEmpty(), type.getSimpleName() + "." + name + " is missing");
                for (Method m : found) {
                    assertTrue(Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()),
                            type.getSimpleName() + "." + name + " must be public static");
                }
            }
        });
    }

    @Test
    void theServerWorldStopsOnlyWhileJumpstartSaysSo() {
        assertTrue(!io.github.jumpstartpz.mod.net.Net.worldPaused(false), "runs as the game decides");
        assertTrue(io.github.jumpstartpz.mod.net.Net.worldPaused(true), "the game's own pause (empty server) stays");
        io.github.jumpstartpz.mod.net.Net.pauseWorld(true);
        try {
            assertTrue(io.github.jumpstartpz.mod.net.Net.worldPaused(false));
        } finally {
            io.github.jumpstartpz.mod.net.Net.pauseWorld(false);
        }
        assertTrue(!io.github.jumpstartpz.mod.net.Net.worldPaused(false));
    }
}
