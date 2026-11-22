package io.github.jumpstartpz.mod;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.ZombieBuddyFork;

/**
 * Called by ZombieBuddy when the mod loads: sets up the secure screen (patched into the game's UI)
 * and the read-only Lua API {@code Jumpstart}.
 */
public final class Main {
    /** Tests only: -Djumpstart.testInput=true lets Lua press the secure screen's keys. Read once. */
    static final boolean TEST_INPUT = Boolean.getBoolean("jumpstart.testInput");
    private static boolean patched;

    private Main() {
    }

    public static void main(String[] args) {
        ZombieBuddyFork zombieBuddy = ZombieBuddyFork.current();
        LiveInstaller installer = LiveInstaller.forGame();
        JumpstartLua.init(installer, zombieBuddy);
        startSecureScreen(installer, zombieBuddy);
        expose();
    }

    private static void expose() {
        try {
            Class<?> exposer = Class.forName("me.zed_0xff.zombie_buddy.Exposer", true, Main.class.getClassLoader());
            Method expose = exposer.getMethod("exposeClass", Class.class, String.class);
            expose.invoke(null, JumpstartLua.class, "Jumpstart");
            System.out.println("[Jumpstart] Lua API ready (read-only); " + JumpstartLua.status());
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] could not expose the Lua API: " + e);
        }
    }

    private static synchronized void startSecureScreen(LiveInstaller installer, ZombieBuddyFork zombieBuddy) {
        try {
            SecureScreen.install(new SecureScreen(new ReflectiveGameScreen(), new GameActions(installer, zombieBuddy), TEST_INPUT));
            if (patched) {
                return; // main() ran again (Lua reloaded); the game is patched already
            }
            Instrumentation instrumentation = (Instrumentation) Class.forName("me.zed_0xff.zombie_buddy.Loader", true,
                    Main.class.getClassLoader()).getMethod("getInstrumentation").invoke(null);
            GameHooks.install(instrumentation);
            patched = true;
            System.out.println("[Jumpstart] secure screen ready: Ctrl+Shift+J"
                    + (TEST_INPUT ? " (TEST MODE: Lua may press its keys, -Djumpstart.testInput)" : ""));
        } catch (Throwable t) {
            SecureScreen.install(null);
            System.out.println("[Jumpstart] secure screen not available, so nothing can be installed: " + t);
        }
    }
}
