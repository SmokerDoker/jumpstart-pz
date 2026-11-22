package io.github.jumpstartpz.mod;

import java.nio.file.Path;

import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.ZombieBuddyFork;
import io.github.jumpstartpz.core.gate.BiteCheckGate;

/**
 * Jumpstart's Lua API, exposed as {@code Jumpstart}. Every call returns one line that starts with
 * DONE, NEEDS_CONFIRMATION, REFUSED or FAILED, so a UI can show it as it is.
 *
 * <pre>
 *   print(Jumpstart.status())
 *   print(Jumpstart.check("C:/.../MyMod.jar"))
 *   print(Jumpstart.install("C:/.../MyMod.jar", "com.example.mymod", "MyMod", "none"))
 *   print(Jumpstart.update("C:/.../MyMod-1.1.jar", "com.example.mymod", "MyMod", "confirmed"))
 * </pre>
 *
 * Consent is "none", "confirmed" (unverified jars) or "confirmed_twice" (flagged or suspicious
 * jars). A UI must only pass more than "none" after the player really confirmed.
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

    /** Whether live installs work here, and why not. */
    public static String status() {
        String unavailable = ZombieBuddyFork.current().unavailableReason();
        return unavailable == null ? "ready" : "not available: " + unavailable;
    }

    /** BiteCheck's verdict for a jar, without changing anything. */
    public static String check(String jarPath) {
        try {
            BiteCheckGate.Check c = installer().gate().check(Path.of(jarPath));
            return c.verdict() + " " + c.status() + " (" + c.info() + ") sha256 " + c.sha256();
        } catch (Exception e) {
            return "FAILED " + e;
        }
    }

    public static String install(String jarPath, String javaPackage, String modId, String consent) {
        return format(installer().install(Path.of(jarPath), javaPackage, modId, null, consent(consent)));
    }

    public static String update(String jarPath, String javaPackage, String modId, String consent) {
        return format(installer().update(Path.of(jarPath), javaPackage, modId, null, consent(consent)));
    }

    /** Fetches BiteCheck's index again; returns what happened. */
    public static String refreshIndex() {
        installer().gate().indexSource().refresh();
        return String.join("; ", installer().gate().indexSource().notes());
    }

    private static BiteCheckGate.Consent consent(String value) {
        if (value == null) {
            return BiteCheckGate.Consent.NONE;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "confirmed" -> BiteCheckGate.Consent.CONFIRMED;
            case "confirmed_twice" -> BiteCheckGate.Consent.CONFIRMED_TWICE;
            default -> BiteCheckGate.Consent.NONE;
        };
    }

    private static String format(LiveInstaller.Outcome outcome) {
        return outcome.kind() + " " + outcome.message();
    }
}
