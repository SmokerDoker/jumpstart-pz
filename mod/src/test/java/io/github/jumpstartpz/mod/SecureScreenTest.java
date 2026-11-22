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
import io.github.bitecheckpz.launcher.UsageSharing;
import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.gate.BiteCheckGate;
import io.github.jumpstartpz.core.gate.BiteCheckGate.Consent;
import io.github.jumpstartpz.core.gate.BiteCheckGate.Verdict;
import io.github.jumpstartpz.core.market.LibraryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The secure screen with a fake game (keyboard, drawing) and fake actions (BiteCheck, loader). */
class SecureScreenTest {
    private static final ModEntry VERIFIED = new ModEntry("Verified", "A verified mod", Path.of("verified.jar"), "pkg.v");
    private static final ModEntry UNKNOWN = new ModEntry("Unknown", "B unknown mod", Path.of("unknown.jar"), "pkg.u");
    private static final ModEntry SHADY = new ModEntry("Shady", "C shady mod", Path.of("shady.jar"), "pkg.s");
    private static final ModEntry BLOCKED = new ModEntry("Blocked", "D blocked mod", Path.of("blocked.jar"), "pkg.b");
    private static final ModEntry SELF = new ModEntry("Jumpstart", "Jumpstart", Path.of("Jumpstart.jar"), "io.github.jumpstartpz.mod");

    private FakeGame game;
    private FakeLink link;
    private FakeActions actions;
    private SecureScreen screen;
    private long now;

    @BeforeEach
    void setUp() {
        game = new FakeGame();
        link = new FakeLink();
        actions = new FakeActions();
        screen = new SecureScreen(game, actions, false, Runnable::run, link);
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
        SecureScreen testScreen = new SecureScreen(game, actions, true, Runnable::run, link);
        SecureScreen.install(testScreen);
        assertTrue(testScreen.inject(SecureScreen.OPEN));
        testScreen.frame(now);
        assertEquals(SecureScreen.Mode.LIST, testScreen.mode());
    }

    @Test
    void theJavaLibrarySearchesDownloadsAndLoadsThroughBiteCheck() {
        actions.folders.put("/workshop/111", List.of(new Actions.FolderMod("Verified", "A verified mod", VERIFIED)));
        openScreen();
        tap(SecureScreen.KEY_TAB);
        assertEquals(SecureScreen.Tab.JAVA_LIBRARY, screen.tab());
        type("fps");
        tap(SecureScreen.KEY_ENTER);
        assertEquals(List.of("JAVA 'fps' null"), actions.searches);
        assertTrue(screen.describe().contains("results=2 selected=111"), screen.describe());

        tap(SecureScreen.KEY_ENTER); // same words again: install the selected item
        assertEquals(List.of(111L), actions.workshop.subscribed);
        assertTrue(screen.describe().contains("downloading=111"), screen.describe());
        frame();
        assertTrue(actions.calls.isEmpty(), "nothing loads while Steam downloads");

        actions.workshop.states.put(111L, Workshop.SUBSCRIBED | Workshop.INSTALLED);
        frame();
        assertEquals(List.of("Verified"), actions.activated);
        assertEquals(List.of("install Verified NONE"), actions.calls, "verified: loaded without a question");
        assertEquals(SecureScreen.Tab.INSTALLED, screen.tab());
    }

    @Test
    void aLuaModFromTheLibraryLoadsLiveInAGame() {
        actions.folders.put("/workshop/201", List.of(new Actions.FolderMod("LuaMod", "Lua Mod", null)));
        actions.lua.put("LuaMod", new LuaLive.Result(LuaLive.Status.LOADED, 4, List.of(), List.of("scripts")));
        downloadLuaMod();
        assertEquals(List.of("LuaMod"), actions.activated);
        assertEquals(List.of("LuaMod"), actions.luaLoaded, "enabled, then loaded into the running game");
        assertTrue(actions.calls.isEmpty(), "no Java part, nothing for BiteCheck");
        assertTrue(screen.describe().contains("loaded live: 4 Lua files"), screen.describe());
        assertTrue(screen.describe().contains("items and recipes"), "what waits for the next load is named: " + screen.describe());
    }

    @Test
    void inTheMainMenuALuaModWaitsForTheSave() {
        actions.folders.put("/workshop/201", List.of(new Actions.FolderMod("LuaMod", "Lua Mod", null)));
        actions.lua.put("LuaMod", LuaLive.Result.of(LuaLive.Status.NOT_IN_GAME));
        downloadLuaMod();
        assertEquals(List.of("LuaMod"), actions.activated);
        assertTrue(screen.describe().contains("enabled; it loads with the next game start"), screen.describe());
    }

    @Test
    void aJavaModsLuaPartFollowsOnlyAfterItsJavaPartLoaded() {
        actions.folders.put("/workshop/111", List.of(new Actions.FolderMod("Unknown", "B unknown mod", UNKNOWN)));
        actions.lua.put("Unknown", new LuaLive.Result(LuaLive.Status.LOADED, 2, List.of(), List.of()));
        openScreen();
        tap(SecureScreen.KEY_TAB);
        type("x");
        tap(SecureScreen.KEY_ENTER);
        tap(SecureScreen.KEY_ENTER);
        actions.workshop.states.put(111L, Workshop.SUBSCRIBED | Workshop.INSTALLED);
        frame();
        assertEquals(SecureScreen.Mode.CONFIRM_ONCE, screen.mode(), "BiteCheck asks about the unknown jar");
        assertTrue(actions.luaLoaded.isEmpty(), "no Lua before the player said yes and the Java part runs");
        now += SecureScreen.FIRST_DELAY_MS;
        tap(SecureScreen.KEY_J);
        assertEquals(List.of("install Unknown CONFIRMED"), actions.calls);
        assertEquals(List.of("Unknown"), actions.luaLoaded);
        assertTrue(screen.describe().contains("loaded live: 2 Lua files"), screen.describe());
    }

    @Test
    void aDeclinedJavaModGetsNoLua() {
        actions.folders.put("/workshop/111", List.of(new Actions.FolderMod("Unknown", "B unknown mod", UNKNOWN)));
        openScreen();
        tap(SecureScreen.KEY_TAB);
        type("x");
        tap(SecureScreen.KEY_ENTER);
        tap(SecureScreen.KEY_ENTER);
        actions.workshop.states.put(111L, Workshop.SUBSCRIBED | Workshop.INSTALLED);
        frame();
        tap(SecureScreen.KEY_N);
        assertTrue(actions.luaLoaded.isEmpty());
    }

    @Test
    void jumpstartListsItselfButCannotBeSwitched() {
        actions.withSelf = true;
        actions.states.put("Jumpstart", "on, until restart");
        openScreen();
        assertTrue(game.drew("on (Jumpstart itself)"), String.valueOf(game.drawn));
        tap(SecureScreen.KEY_A);
        tap(SecureScreen.KEY_ENTER);
        assertTrue(actions.calls.isEmpty(), String.valueOf(actions.calls));
        assertEquals("on, until restart", actions.states.get("Jumpstart"));
        assertTrue(screen.describe().contains("This is Jumpstart itself"), screen.describe());
    }

    @Test
    void aLuaModInTheInstalledTabSwitchesOffAndOnLive() {
        actions.luaMods.add("Minimap");
        actions.luaStates.put("Minimap", "on");
        actions.lua.put("Minimap", LuaLive.Result.of(LuaLive.Status.RESUMED));
        openScreen();
        selectInstalled("Minimap");
        assertTrue(game.drew("Lua"), "the list says which mods are Lua mods");
        tap(SecureScreen.KEY_A);
        assertEquals(List.of("Minimap"), actions.deactivated, "out of the mod list");
        assertEquals(List.of("Minimap"), actions.unloaded, "and its event handlers stopped right away");
        assertTrue(screen.describe().contains("3 of its event handlers stopped"), screen.describe());
        tap(SecureScreen.KEY_A);
        assertEquals(List.of("Minimap"), actions.activated);
        assertEquals(List.of("Minimap"), actions.luaLoaded);
        assertTrue(screen.describe().contains("running again"), screen.describe());
        assertTrue(actions.calls.isEmpty(), "a Lua mod never goes to the Java loader");
    }

    @Test
    void uninstallingNeedsTwoDelayedYeses() {
        actions.luaMods.add("Minimap");
        openScreen();
        selectInstalled("Minimap");
        tap(SecureScreen.KEY_U);
        assertEquals(SecureScreen.Mode.UNINSTALL_ONCE, screen.mode());
        tap(SecureScreen.KEY_J);
        assertEquals(SecureScreen.Mode.UNINSTALL_ONCE, screen.mode(), "J right away does nothing");
        now += SecureScreen.UNINSTALL_FIRST_DELAY_MS;
        tap(SecureScreen.KEY_J);
        assertEquals(SecureScreen.Mode.UNINSTALL_TWICE, screen.mode(), "a second question");
        tap(SecureScreen.KEY_J);
        assertTrue(actions.uninstalled.isEmpty(), "the second question has its own delay");
        now += SecureScreen.UNINSTALL_SECOND_DELAY_MS;
        tap(SecureScreen.KEY_J);
        assertEquals(List.of("Minimap"), actions.uninstalled);
        assertEquals(SecureScreen.Mode.LIST, screen.mode());
        assertTrue(screen.describe().contains("Unsubscribe"), screen.describe());
    }

    @Test
    void noKeepsTheModAndJumpstartCannotBeUninstalled() {
        actions.luaMods.add("Minimap");
        actions.withSelf = true;
        openScreen();
        selectInstalled("Minimap");
        tap(SecureScreen.KEY_U);
        tap(SecureScreen.KEY_N);
        assertEquals(SecureScreen.Mode.LIST, screen.mode());
        assertTrue(actions.uninstalled.isEmpty());
        selectInstalled("Jumpstart");
        tap(SecureScreen.KEY_U);
        assertEquals(SecureScreen.Mode.LIST, screen.mode(), "no question for Jumpstart itself");
        assertTrue(screen.describe().contains("This is Jumpstart itself"), screen.describe());
    }

    private void selectInstalled(String id) {
        for (int i = 0; i < 20 && !screen.describe().contains("selected=" + id + " "); i++) {
            tap(SecureScreen.KEY_UP);
        }
        for (int i = 0; i < 20 && !screen.describe().contains("selected=" + id + " "); i++) {
            tap(SecureScreen.KEY_DOWN);
        }
        assertTrue(screen.describe().contains("selected=" + id + " "), screen.describe());
    }

    private void downloadLuaMod() {
        openScreen();
        tap(SecureScreen.KEY_TAB);
        tap(SecureScreen.KEY_TAB);
        assertEquals(SecureScreen.Tab.LUA_LIBRARY, screen.tab());
        tap(SecureScreen.KEY_ENTER); // empty search: the most popular mods
        assertEquals(List.of("LUA '' null"), actions.searches);
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_ENTER);
        actions.workshop.states.put(201L, Workshop.SUBSCRIBED | Workshop.INSTALLED);
        frame();
    }

    @Test
    void sharingIsOffUntilThePlayerTurnsItOnAndShowsWhatItSends() {
        openScreen();
        for (int i = 0; i < 3; i++) {
            tap(SecureScreen.KEY_TAB);
        }
        assertEquals(SecureScreen.Tab.SHARING, screen.tab());
        assertTrue(actions.shared.isEmpty(), "nothing is sent while it is off");
        assertTrue(screen.sharingPreview().contains("jar\t" + "ab".repeat(32) + "\t201\tUnknown\t42"), screen.sharingPreview());
        tap(SecureScreen.KEY_ENTER);
        assertTrue(actions.sharing);
        assertEquals(List.of("1 jars"), actions.shared);
        tap(SecureScreen.KEY_ENTER);
        assertFalse(actions.sharing);
    }

    @Test
    void aServerChangeMustBeAgreedToAndNoIsReported() {
        link.connected = true;
        frame();
        assertEquals(List.of("hello {v=1}"), link.sent, "hello once when the multiplayer game starts");
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "0", "mods", "Unknown\t\told\tunknown.jar\ton\n"));
        frame();
        assertTrue(screen.session().connected());

        screen.onServerCommand("prepare", Map.of("action", "update", "mod", "Unknown", "sha", "unknown.jar", "workshop", "",
                "seconds", "60", "by", "admin", "yours", "0"));
        frame();
        assertEquals(SecureScreen.Mode.SERVER_CONSENT, screen.mode(), "the server opened the screen and the player is asked");
        assertEquals(SecureScreen.Tab.SERVER, screen.tab());
        assertTrue(link.paused);
        assertTrue(game.drew("Admin admin changes a Java mod for everybody"));
        tap(SecureScreen.KEY_J); // too early
        assertEquals(SecureScreen.Mode.SERVER_CONSENT, screen.mode());
        tap(SecureScreen.KEY_N);
        assertEquals("failed {mod=Unknown, reason=the player said no, token=T}", link.sent.get(link.sent.size() - 1));
        assertTrue(actions.calls.isEmpty());

        screen.onServerCommand("done", Map.of("action", "update", "mod", "Unknown", "ok", "1", "message", ""));
        frame();
        assertFalse(link.paused);
        assertEquals(SecureScreen.Mode.CLOSED, screen.mode(), "the screen the server opened closes again");
    }

    @Test
    void anAgreedUpdateGoesThroughBiteCheckAndIsReady() {
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "0", "mods", ""));
        screen.onServerCommand("prepare", Map.of("action", "update", "mod", "Unknown", "sha", "unknown.jar", "workshop", "",
                "seconds", "60", "by", "admin", "yours", "0"));
        frame();
        now += SecureScreen.CONSENT_DELAY_MS;
        tap(SecureScreen.KEY_J); // agrees to the admin's change ...
        frame();
        assertEquals(SecureScreen.Mode.CONFIRM_ONCE, screen.mode(), "... and BiteCheck still asks about the unknown jar");
        tap(SecureScreen.KEY_ESCAPE);
        assertEquals("failed {mod=Unknown, reason=the player said no, token=T}", link.sent.get(link.sent.size() - 1));

        link.sent.clear();
        screen.onServerCommand("prepare", Map.of("action", "update", "mod", "Unknown", "sha", "unknown.jar", "workshop", "",
                "seconds", "60", "by", "me", "yours", "1"));
        frame();
        frame();
        assertEquals(SecureScreen.Mode.CONFIRM_ONCE, screen.mode(), "the admin who started it is not asked to agree, BiteCheck asks");
        now += SecureScreen.FIRST_DELAY_MS;
        tap(SecureScreen.KEY_J);
        assertEquals(List.of("install Unknown CONFIRMED"), actions.calls);
        assertEquals("ready {mod=Unknown, sha=unknown.jar, token=T}", link.sent.get(link.sent.size() - 1));
        tap(SecureScreen.KEY_ESCAPE);
        assertEquals(SecureScreen.Mode.LIST, screen.mode(), "stays open until the server says done");
    }

    @Test
    void anAdminSwitchesAModOffForEverybody() {
        actions.states.put("Verified", "on");
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "0", "mods", ""));
        screen.onServerCommand("prepare", Map.of("action", "off", "mod", "Verified", "sha", "s", "workshop", "",
                "seconds", "60", "by", "admin", "yours", "0"));
        frame();
        now += SecureScreen.CONSENT_DELAY_MS;
        tap(SecureScreen.KEY_J);
        assertEquals("off", actions.states.get("Verified"));
        assertEquals("ready {mod=Verified, sha=s, token=T}", link.sent.get(link.sent.size() - 1));
    }

    @Test
    void inMultiplayerNobodySwitchesAlone() {
        actions.states.put("Verified", "on");
        link.connected = true;
        openScreen();
        tap(SecureScreen.KEY_A);
        tap(SecureScreen.KEY_ENTER);
        assertEquals("on", actions.states.get("Verified"));
        assertTrue(actions.calls.isEmpty());
        assertTrue(screen.describe().contains("only an admin switches"), screen.describe());
    }

    @Test
    void aWrongVersionIsNeverLoadedForTheServer() {
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "1", "mods", ""));
        screen.onServerCommand("prepare", Map.of("action", "update", "mod", "Unknown", "sha", "c".repeat(64), "workshop", "",
                "seconds", "5", "by", "me", "yours", "1"));
        frame();
        frame();
        assertTrue(actions.calls.isEmpty());
        now += 6000;
        frame();
        assertEquals("failed {mod=Unknown, reason=not ready in time, token=T}", link.sent.get(link.sent.size() - 1));
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

    private void type(String text) {
        for (char c : text.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            tap(c);
        }
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
        @Override public String keyName(int glfwKey) { return String.valueOf((char) Character.toLowerCase(glfwKey)); }
        @Override public boolean inGame() { return false; }
        @Override public void pause(boolean paused) { this.paused = paused; }
        @Override public String language() { return "EN"; }
    }

    private static final class FakeActions implements Actions {
        final List<String> calls = new ArrayList<>();
        final Map<String, String> states = new HashMap<>();
        boolean swapped;

        boolean withSelf;
        /** Lua-only mods in the mod folders (shown after the Java ones). */
        final List<String> luaMods = new ArrayList<>();
        final Map<String, String> luaStates = new HashMap<>();
        final List<String> deactivated = new ArrayList<>();
        final List<String> unloaded = new ArrayList<>();
        final List<String> uninstalled = new ArrayList<>();

        @Override
        public List<ModEntry> javaMods() {
            return withSelf ? List.of(SELF, VERIFIED, UNKNOWN, SHADY, BLOCKED) : List.of(VERIFIED, UNKNOWN, SHADY, BLOCKED);
        }

        @Override
        public List<InstalledMod> installedMods() {
            List<InstalledMod> list = new ArrayList<>();
            for (ModEntry m : javaMods()) {
                list.add(new InstalledMod(m.id(), m.name(), m, 0));
            }
            for (String id : luaMods) {
                if (!uninstalled.contains(id)) {
                    list.add(new InstalledMod(id, id + " (Lua)", null, 201));
                }
            }
            return list;
        }

        @Override
        public String luaState(String modId) {
            return luaStates.getOrDefault(modId, "off");
        }

        @Override
        public String deactivate(String modId) {
            deactivated.add(modId);
            luaStates.put(modId, "off, next load");
            return null;
        }

        @Override
        public int unloadLua(String modId) {
            unloaded.add(modId);
            return 3;
        }

        @Override
        public String uninstall(String modId) {
            uninstalled.add(modId);
            return "OK workshop 201";
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

        // marketplace
        final List<String> searches = new ArrayList<>();
        final FakeWorkshop workshop = new FakeWorkshop();
        final Map<String, List<FolderMod>> folders = new HashMap<>();
        final List<String> activated = new ArrayList<>();

        @Override
        public LibraryClient.Page search(LibraryClient.Kind kind, String query, String cursor) {
            searches.add(kind + " '" + query + "' " + cursor);
            return new LibraryClient.Page(2, "", List.of(
                    new LibraryClient.Item(111, "Java Mod One", 500, 0, true, List.of("Build 42"), "java"),
                    new LibraryClient.Item(201, "Lua Mod", 7, 0, false, List.of(), "lua")));
        }

        @Override
        public Workshop workshop() {
            return workshop;
        }

        @Override
        public List<FolderMod> modsInFolder(String itemFolder) {
            return folders.getOrDefault(itemFolder, List.of());
        }

        @Override
        public String activate(String modId) {
            activated.add(modId);
            return null;
        }

        final Map<String, LuaLive.Result> lua = new HashMap<>();
        final List<String> luaLoaded = new ArrayList<>();

        @Override
        public LuaLive.Result loadLua(String modId) {
            luaLoaded.add(modId);
            LuaLive.Result r = lua.getOrDefault(modId, LuaLive.Result.of(LuaLive.Status.NOT_IN_GAME));
            if (r.status() == LuaLive.Status.LOADED || r.status() == LuaLive.Status.RESUMED) {
                luaStates.put(modId, "on");
            }
            return r;
        }

        // sharing
        boolean sharing;
        final List<String> shared = new ArrayList<>();

        @Override
        public boolean sharingEnabled() {
            return sharing;
        }

        @Override
        public void setSharing(boolean enabled) {
            sharing = enabled;
        }

        @Override
        public List<UsageSharing.Jar> unverifiedJars() {
            return List.of(new UsageSharing.Jar("ab".repeat(32), "201", "Unknown", 42));
        }

        @Override
        public String shareIfDue(List<UsageSharing.Jar> jars) {
            shared.add(jars.size() + " jars");
            return "OK " + jars.size() + " jars";
        }
    }

    private static final class FakeLink implements ServerLink {
        boolean connected;
        boolean paused;
        final List<String> sent = new ArrayList<>();

        @Override public boolean connected() { return connected; }
        @Override public void send(String command, Map<String, String> args) {
            sent.add(command + " " + new java.util.TreeMap<>(args));
        }
        @Override public void pause(boolean paused) { this.paused = paused; }
    }

    private static final class FakeWorkshop implements Workshop {
        final Map<Long, Long> states = new HashMap<>();
        final List<Long> subscribed = new ArrayList<>();

        @Override public String unavailableReason() { return null; }
        @Override public long state(long itemId) { return states.getOrDefault(itemId, 0L); }

        @Override
        public boolean subscribeAndDownload(long itemId) {
            subscribed.add(itemId);
            states.put(itemId, SUBSCRIBED | DOWNLOADING);
            return true;
        }

        @Override public String installFolder(long itemId) { return "/workshop/" + itemId; }
    }
}
