package io.github.jumpstartpz.mod;

import java.nio.file.Path;

import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.ZombieBuddyFork;
import io.github.jumpstartpz.core.gate.BiteCheckGate;

/**
 * Jumpstart's Lua API, exposed as {@code Jumpstart}. Results are one line that starts with a word a
 * UI can act on (DONE, DECLINED, NEEDS_CONFIRMATION, REFUSED, FAILED, OK).
 *
 * <pre>
 *   print(Jumpstart.status())
 *   print(Jumpstart.javaModOf(info:getCommonDir(), info:getVersionDir()))   -- "jar\tpackage" or ""
 *   print(Jumpstart.check("C:/.../MyMod.jar"))
 *   print(Jumpstart.install("C:/.../MyMod.jar", "com.example.mymod", "MyMod"))
 *   print(Jumpstart.update("C:/.../MyMod.jar", "com.example.mymod", "MyMod"))
 *   print(Jumpstart.stateOf("MyMod"), Jumpstart.off("MyMod"), Jumpstart.on("MyMod"))
 * </pre>
 *
 * Lua cannot confirm anything: where BiteCheck wants a confirmation, install and update ask the
 * player in a native window (see {@link PlayerPrompt}).
 */
public final class JumpstartLua {
    private static LiveInstaller installer;

    private JumpstartLua() {
    }

    private static synchronized LiveInstaller installer() {
        if (installer == null) {
            installer = LiveInstaller.forGame();
        }
        return installer;
    }

    private static ZombieBuddyFork zombieBuddy() {
        return ZombieBuddyFork.current();
    }

    /** "ready", or "not available: why". */
    public static String status() {
        String unavailable = zombieBuddy().unavailableReason();
        return unavailable == null ? "ready" : "not available: " + unavailable;
    }

    /** BiteCheck's verdict for a jar, without changing anything: "VERDICT STATUS (info) sha256 HASH". */
    public static String check(String jarPath) {
        try {
            BiteCheckGate.Check c = installer().gate().check(Path.of(jarPath));
            return c.verdict() + " " + c.status() + " (" + c.info() + ") sha256 " + c.sha256();
        } catch (Exception e) {
            return "FAILED " + e;
        }
    }

    /** Loads a mod that is not loaded yet; asks the player natively if BiteCheck wants that. */
    public static String install(String jarPath, String javaPackage, String modId) {
        return format(installer().install(Path.of(jarPath), javaPackage, modId, null, PlayerPrompt.INSTANCE));
    }

    /** Replaces the loaded version of a mod; asks the player natively if BiteCheck wants that. */
    public static String update(String jarPath, String javaPackage, String modId) {
        return format(installer().update(Path.of(jarPath), javaPackage, modId, null, PlayerPrompt.INSTANCE));
    }

    /** "on", "on, until restart", "off" or "not loaded". */
    public static String stateOf(String modId) {
        String state = zombieBuddy().stateOf(modId);
        return state == null ? "unknown" : state;
    }

    public static String off(String modId) {
        return orUpdate(zombieBuddy().off(modId));
    }

    public static String on(String modId) {
        return orUpdate(zombieBuddy().on(modId));
    }

    /** "jar path TAB package" when the mod folder has a Java part for this ZombieBuddy, else "". */
    public static String javaModOf(String commonDir, String versionDir) {
        String result = zombieBuddy().javaModOf(commonDir, versionDir);
        return result == null || result.startsWith("FAILED") ? "" : result;
    }

    /** Fetches BiteCheck's index again; returns what happened. */
    public static String refreshIndex() {
        installer().gate().indexSource().refresh();
        return String.join("; ", installer().gate().indexSource().notes());
    }

    private static String orUpdate(String result) {
        return result != null ? result : "FAILED this ZombieBuddy cannot switch mods; install the Jumpstart fork";
    }

    private static String format(LiveInstaller.Outcome outcome) {
        return outcome.kind() + " " + outcome.message();
    }
}
