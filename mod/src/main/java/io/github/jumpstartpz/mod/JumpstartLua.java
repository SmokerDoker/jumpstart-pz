package io.github.jumpstartpz.mod;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.ZombieBuddyFork;
import io.github.jumpstartpz.core.gate.BiteCheckGate;

/**
 * Jumpstart's Lua API, exposed as {@code Jumpstart}: read-only. Lua mods are not checked by anyone,
 * so they can neither install, update nor switch mods, nor start a question to the player; all of
 * that happens only in the {@link SecureScreen} (Ctrl+Shift+J).
 *
 * <pre>
 *   print(Jumpstart.status())                  -- "ready" or why not
 *   print(Jumpstart.stateOf("MyMod"))          -- "on", "off", "not loaded", ...
 *   print(Jumpstart.check("C:/.../MyMod.jar")) -- BiteCheck's verdict
 * </pre>
 *
 * With the JVM option {@code -Djumpstart.testInput=true} (automated tests only, read once at start)
 * {@link #testKey} and {@link #testScreen} drive the secure screen.
 */
public final class JumpstartLua {
    private static LiveInstaller installer;
    private static ZombieBuddyFork zombieBuddy;

    private static final Map<String, Integer> TEST_KEYS = Map.ofEntries(
            Map.entry("open", SecureScreen.OPEN), Map.entry("up", SecureScreen.KEY_UP),
            Map.entry("down", SecureScreen.KEY_DOWN), Map.entry("pgup", SecureScreen.KEY_PAGE_UP),
            Map.entry("pgdn", SecureScreen.KEY_PAGE_DOWN), Map.entry("enter", SecureScreen.KEY_ENTER),
            Map.entry("esc", SecureScreen.KEY_ESCAPE), Map.entry("j", SecureScreen.KEY_J),
            Map.entry("n", SecureScreen.KEY_N), Map.entry("a", SecureScreen.KEY_A), Map.entry("r", SecureScreen.KEY_R),
            Map.entry("tab", SecureScreen.KEY_TAB), Map.entry("backspace", SecureScreen.KEY_BACKSPACE));

    private JumpstartLua() {
    }

    static synchronized void init(LiveInstaller installer, ZombieBuddyFork zombieBuddy) {
        JumpstartLua.installer = installer;
        JumpstartLua.zombieBuddy = zombieBuddy;
    }

    /** "ready", or "not available: why". */
    public static String status() {
        String unavailable = zombieBuddy.unavailableReason();
        if (unavailable != null) {
            return "not available: " + unavailable;
        }
        return SecureScreen.current() == null ? "not available: the secure screen could not start (see console.txt)" : "ready";
    }

    /** "on", "on, until restart", "off" or "not loaded". */
    public static String stateOf(String modId) {
        String state = zombieBuddy.stateOf(modId);
        return state == null ? "unknown" : state;
    }

    /** BiteCheck's verdict for a jar, without changing anything: "VERDICT STATUS (info) sha256 HASH". */
    public static String check(String jarPath) {
        try {
            BiteCheckGate.Check c = installer.gate().check(Path.of(jarPath));
            return c.verdict() + " " + c.status() + " (" + c.info() + ") sha256 " + c.sha256();
        } catch (Exception e) {
            return "FAILED " + e;
        }
    }

    /** Tests only: presses a key of the secure screen ("open", "up", "down", "pgup", "pgdn", "enter", "esc", "j", "n", "a", "r"). */
    public static String testKey(String name) {
        SecureScreen screen = SecureScreen.current();
        Integer key = name == null ? null : TEST_KEYS.get(name.toLowerCase(Locale.ROOT));
        if (screen == null || key == null || !screen.inject(key)) {
            return "FAILED test input is off";
        }
        return "OK";
    }

    /** Tests only: types letters, digits and spaces into the secure screen (one key per character). */
    public static String testType(String text) {
        SecureScreen screen = SecureScreen.current();
        if (screen == null || text == null) {
            return "FAILED test input is off";
        }
        for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
            int key = c == ' ' ? SecureScreen.KEY_SPACE : c == '-' ? SecureScreen.KEY_MINUS : c;
            if (!screen.inject(key)) {
                return "FAILED test input is off";
            }
        }
        return "OK";
    }

    /** Tests only: the secure screen's state in one line. */
    public static String testScreen() {
        SecureScreen screen = SecureScreen.current();
        return screen == null || !Main.TEST_INPUT ? "FAILED test input is off" : screen.describe();
    }
}
