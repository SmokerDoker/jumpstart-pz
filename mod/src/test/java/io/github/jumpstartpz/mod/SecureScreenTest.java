package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.bitecheckpz.core.Verifier;
import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.gate.BiteCheckGate;
import io.github.jumpstartpz.core.gate.BiteCheckGate.Consent;
import io.github.jumpstartpz.core.gate.BiteCheckGate.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The secure screen with a fake game (keyboard, drawing) and fake actions (BiteCheck, loader). */
class SecureScreenTest {
    private static final ModEntry VERIFIED = new ModEntry("Verified", "A verified mod", Path.of("verified.jar"), "pkg.v");
    private static final ModEntry UNKNOWN = new ModEntry("Unknown", "B unknown mod", Path.of("unknown.jar"), "pkg.u");
    private static final ModEntry SHADY = new ModEntry("Shady", "C shady mod", Path.of("shady.jar"), "pkg.s");
    private static final ModEntry BLOCKED = new ModEntry("Blocked", "D blocked mod", Path.of("blocked.jar"), "pkg.b");

    private FakeGame game;
    private FakeActions actions;
    private SecureScreen screen;
    private long now;

    @BeforeEach
    void setUp() {
        game = new FakeGame();
        actions = new FakeActions();
        screen = new SecureScreen(game, actions, false);
        SecureScreen.install(screen);
        now = 1_000_000;
    }

    @Test
    void opensOnlyOnThePhysicalCtrlShiftJ() {
        frame();
        assertTrue(game.drew("Jumpstart: Ctrl+Shift+J"), "the hint at the main menu");
        tap(SecureScreen.KEY_J);
        tap(SecureScreen.KEY_ENTER);
        assertEquals(SecureScreen.Mode.CLOSED, screen.mode());
        assertFalse(SecureScreen.blocksInput());

        openScreen();
        assertEquals(SecureScreen.Mode.LIST, screen.mode());
        assertTrue(SecureScreen.blocksInput(), "the game and Lua get no input while it is open");
        assertTrue(game.paused);
        assertEquals(List.of("Verified", "Unknown", "Shady", "Blocked"), screen.modIds());
    }

    @Test
    void keyPressesFromLuaAreRefusedOutsideTests() {
        assertFalse(screen.inject(SecureScreen.OPEN));
        frame();
        assertEquals(SecureScreen.Mode.CLOSED, screen.mode());
    }

    @Test
    void escClosesAndKeepsInputBlockedUntilTheKeyIsReleased() {
        openScreen();
        game.down.add(SecureScreen.KEY_ESCAPE);
        frame();
        assertEquals(SecureScreen.Mode.CLOSED, screen.mode());
        assertTrue(SecureScreen.blocksInput(), "Esc still held: the game must not see it");
        assertFalse(game.paused);
        game.down.clear();
        frame();
        assertFalse(SecureScreen.blocksInput());
    }

    @Test
    void aVerifiedModLoadsWithoutAQuestion() {
        openScreen();
        tap(SecureScreen.KEY_ENTER);
        assertEquals(List.of("install Verified NONE"), actions.calls);
        assertEquals("on", actions.states.get("Verified"));
        assertTrue(screen.describe().contains("message=DONE"), screen.describe());
    }

    @Test
    void anUnknownModNeedsJAfterTheDelay() {
        openScreen();
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_ENTER);
        assertEquals(SecureScreen.Mode.CONFIRM_ONCE, screen.mode());
        assertTrue(game.drew("Load a file BiteCheck does not know?"));

        tap(SecureScreen.KEY_J); // too early: does nothing
        assertEquals(SecureScreen.Mode.CONFIRM_ONCE, screen.mode());
        assertTrue(actions.calls.isEmpty());

        now += SecureScreen.FIRST_DELAY_MS;
        tap(SecureScreen.KEY_J);
        assertEquals(SecureScreen.Mode.LIST, screen.mode());
        assertEquals(List.of("install Unknown CONFIRMED"), actions.calls);
    }

    @Test
    void theAnswerOnlyCountsForTheFileThePlayerSaw() {
        openScreen();
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_ENTER);
        now += SecureScreen.FIRST_DELAY_MS;
        actions.swapped = true; // the jar changes while the question is on screen
        tap(SecureScreen.KEY_J);
        assertEquals(List.of("install Unknown null"), actions.calls, "no consent for another hash");
    }

    @Test
    void aSuspiciousModNeedsTwoAnswersWithDelays() {
        openScreen();
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_ENTER);
        now += SecureScreen.FIRST_DELAY_MS;
        tap(SecureScreen.KEY_J);
        assertEquals(SecureScreen.Mode.CONFIRM_TWICE, screen.mode());
        assertTrue(game.drew("Are you really sure?"));
        now += SecureScreen.SECOND_DELAY_MS - 200; // a tap takes two frames of 16 ms
        tap(SecureScreen.KEY_J);
        assertEquals(SecureScreen.Mode.CONFIRM_TWICE, screen.mode(), "the second answer waits longer");
        now += 200;
        tap(SecureScreen.KEY_J);
        assertEquals(List.of("install Shady CONFIRMED_TWICE"), actions.calls);
    }

    @Test
    void noCancels() {
        openScreen();
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_ENTER);
        tap(SecureScreen.KEY_N);
        assertEquals(SecureScreen.Mode.LIST, screen.mode());
        assertTrue(actions.calls.isEmpty());
    }

    @Test
    void aBlockedModIsNeverOffered() {
        openScreen();
        for (int i = 0; i < 3; i++) {
            tap(SecureScreen.KEY_DOWN);
        }
        tap(SecureScreen.KEY_ENTER);
        assertEquals(SecureScreen.Mode.LIST, screen.mode());
        assertTrue(actions.calls.isEmpty());
        assertTrue(screen.describe().contains("BiteCheck blocks this file"), screen.describe());
    }

    @Test
    void aSwitchesALoadedModOffAndOn() {
        actions.states.put("Verified", "on");
        openScreen();
        tap(SecureScreen.KEY_A);
        assertEquals("off", actions.states.get("Verified"));
        tap(SecureScreen.KEY_A);
        assertEquals("on", actions.states.get("Verified"));
        tap(SecureScreen.KEY_ENTER); // loaded already: Enter reloads the jar
        assertEquals("update Verified NONE", actions.calls.get(actions.calls.size() - 1));
    }

    @Test
    void injectedKeysWorkInTestMode() {
        SecureScreen testScreen = new SecureScreen(game, actions, true);
        SecureScreen.install(testScreen);
        assertTrue(testScreen.inject(SecureScreen.OPEN));
        testScreen.frame(now);
        assertEquals(SecureScreen.Mode.LIST, testScreen.mode());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void frame() {
        game.drawn.clear();
        screen.frame(now);
        now += 16;
    }

    private void tap(int key) {
        game.down.add(key);
        frame();
        game.down.remove(key);
        frame();
    }

    private void openScreen() {
        game.down.add(SecureScreen.KEY_LEFT_CONTROL);
        game.down.add(SecureScreen.KEY_LEFT_SHIFT);
        tap(SecureScreen.KEY_J);
        game.down.clear();
        frame();
    }

    private static final class FakeGame implements GameScreen {
        final Set<Integer> down = new HashSet<>();
        final List<String> drawn = new ArrayList<>();
        boolean paused;

        boolean drew(String text) {
            return drawn.stream().anyMatch(t -> t.contains(text));
        }

        @Override public int width() { return 1920; }
        @Override public int height() { return 1080; }
        @Override public int fontHeight(Font font) { return 16; }
        @Override public int textWidth(Font font, String text) { return text.length() * 8; }
        @Override public void text(Font font, int x, int y, String text, float r, float g, float b, float a) { drawn.add(text); }
        @Override public void rect(int x, int y, int w, int h, float r, float g, float b, float a) { }
        @Override public boolean physicalKeyDown(int glfwKey) { return down.contains(glfwKey); }
        @Override public boolean inGame() { return false; }
        @Override public void pause(boolean paused) { this.paused = paused; }
        @Override public String language() { return "EN"; }
    }

    private static final class FakeActions implements Actions {
        final List<String> calls = new ArrayList<>();
        final Map<String, String> states = new HashMap<>();
        boolean swapped;

        @Override
        public List<ModEntry> javaMods() {
            return List.of(VERIFIED, UNKNOWN, SHADY, BLOCKED);
        }

        @Override
        public String stateOf(String modId) {
            return states.getOrDefault(modId, "not loaded");
        }

        @Override
        public BiteCheckGate.Check check(Path jar) {
            String name = jar.getFileName().toString();
            String hash = (swapped ? "other-" : "") + name;
            return switch (name) {
                case "verified.jar" -> check(jar, hash, Verifier.Status.VERIFIED, false, Verdict.ALLOW);
                case "shady.jar" -> check(jar, hash, Verifier.Status.UNVERIFIED, true, Verdict.CONFIRM_TWICE);
                case "blocked.jar" -> check(jar, hash, Verifier.Status.BLOCKED, false, Verdict.REFUSE);
                default -> check(jar, hash, Verifier.Status.UNVERIFIED, false, Verdict.CONFIRM);
            };
        }

        private static BiteCheckGate.Check check(Path jar, String hash, Verifier.Status status, boolean suspicious, Verdict verdict) {
            return new BiteCheckGate.Check(jar, hash, status, "test", suspicious, null, verdict);
        }

        @Override
        public LiveInstaller.Outcome install(ModEntry mod, LiveInstaller.ConsentSource player) {
            return run("install", mod, player);
        }

        @Override
        public LiveInstaller.Outcome update(ModEntry mod, LiveInstaller.ConsentSource player) {
            return run("update", mod, player);
        }

        /** Like LiveInstaller: checks again and asks only when the verdict wants it. */
        private LiveInstaller.Outcome run(String what, ModEntry mod, LiveInstaller.ConsentSource player) {
            BiteCheckGate.Check check = check(mod.jar());
            Consent consent = check.verdict() == Verdict.ALLOW ? Consent.NONE : player.consentFor(check);
            calls.add(what + " " + mod.id() + " " + consent);
            if (consent == null) {
                return new LiveInstaller.Outcome(LiveInstaller.Kind.DECLINED, check, "declined");
            }
            states.put(mod.id(), "on");
            return new LiveInstaller.Outcome(LiveInstaller.Kind.DONE, check, mod.id() + " loaded");
        }

        @Override
        public String off(String modId) {
            states.put(modId, "off");
            return "OK " + modId + " off";
        }

        @Override
        public String on(String modId) {
            states.put(modId, "on");
            return "OK " + modId + " on";
        }

        @Override
        public String refreshIndex() {
            return "refreshed";
        }
    }
}
