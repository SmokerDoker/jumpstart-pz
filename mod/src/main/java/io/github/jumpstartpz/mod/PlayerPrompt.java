package io.github.jumpstartpz.mod;

import java.lang.reflect.Method;
import java.util.Locale;

import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.gate.BiteCheckGate;

/**
 * Asks the player for BiteCheck's confirmations with a native window (LWJGL TinyFileDialogs, like
 * ZombieBuddy's own approval), not with a Lua dialog: Lua mods are not checked by anyone, so a Lua
 * window could be clicked or faked by any other mod. Lua can start an install, only the player can
 * confirm it.
 *
 * <p>For automated tests only, the JVM option {@code -Djumpstart.autoConsent=yes|no} answers every
 * question without a window. It is read once when this class loads, so it cannot be changed from
 * Lua while the game runs.
 */
final class PlayerPrompt implements LiveInstaller.ConsentSource {
    private static final String TITLE = "Jumpstart - BiteCheck";
    private static final String AUTO_CONSENT = readAutoConsent();

    static final PlayerPrompt INSTANCE = new PlayerPrompt();

    private PlayerPrompt() {
    }

    private static String readAutoConsent() {
        String value = System.getProperty("jumpstart.autoConsent");
        if (value == null || value.isBlank()) {
            return null;
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        System.out.println("[Jumpstart] TEST MODE: every confirmation is answered '" + value + "' (-Djumpstart.autoConsent)");
        return value;
    }

    @Override
    public BiteCheckGate.Consent consentFor(BiteCheckGate.Check check) {
        String jar = check.jar().getFileName().toString();
        String first = "Install this Java mod while the game runs?\n\n"
                + "File: " + jar + "\n"
                + "BiteCheck: " + check.status() + " (" + check.info() + ")\n"
                + "SHA-256: " + check.sha256() + "\n\n"
                + "BiteCheck does not know this exact file. Java mods can do anything on your computer.\n"
                + "Only say yes if you trust where it comes from.";
        if (!ask(first)) {
            return null;
        }
        if (check.verdict() != BiteCheckGate.Verdict.CONFIRM_TWICE) {
            return BiteCheckGate.Consent.CONFIRMED;
        }
        String reason = check.status() == io.github.bitecheckpz.core.Verifier.Status.FLAGGED
                ? "The BiteCheck registry has FLAGGED this file: " + check.info()
                : "BiteCheck's scanner found code that is typical for malware (for example starting programs or downloading files).";
        String second = "Are you really sure?\n\n" + reason + "\n\n"
                + "File: " + jar + "\n\n"
                + "Say yes only if you know exactly why this mod needs that.";
        return ask(second) ? BiteCheckGate.Consent.CONFIRMED_TWICE : null;
    }

    private static boolean ask(String message) {
        if (AUTO_CONSENT != null) {
            return AUTO_CONSENT.equals("yes");
        }
        try {
            Class<?> tinyfd = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
            Method box = tinyfd.getMethod("tinyfd_messageBox", CharSequence.class, CharSequence.class,
                    CharSequence.class, CharSequence.class, boolean.class);
            // tinyfd refuses quotes in some backends; the default button is "no"
            Object yes = box.invoke(null, TITLE, message.replace('"', '\'').replace('\'', '`'), "yesno", "warning", false);
            return Boolean.TRUE.equals(yes);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            System.out.println("[Jumpstart] could not show the confirmation window, so the answer is no: " + e);
            return false;
        }
    }
}
