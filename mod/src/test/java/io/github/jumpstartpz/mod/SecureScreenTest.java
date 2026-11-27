package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void crashProtectionShowsABannerStopsTheLuaAndMarksTheMod() {
        frame();
        assertEquals(1, actions.guardPolls, "one cheap poll per frame");
        assertEquals(0, actions.guardFetches, "nothing to fetch without events");

        // a mod loaded with the save threw before Jumpstart's first frame: still reported
        actions.guardEvents.add("1\tUnknown\tadvice\tUIManager.render\tjava.lang.NullPointerException: x\tlogged");
        actions.guardEvents.add("2\tUnknown\tadvice\tUIManager.render\tjava.lang.NullPointerException: x\toff");
        actions.guardStatus.put("Unknown", "2\toff-by-guard");
        actions.states.put("Unknown", "off");
        frame();
        assertEquals(1, actions.guardFetches);
        assertTrue(game.drew("Unknown caused an error (NullPointerException in UIManager.render) and was switched off. Ctrl+Shift+J: details"));
        assertEquals(List.of("Unknown"), actions.unloaded, "its Lua side stops too");
        assertTrue(screen.describe().contains("banner=Unknown caused an error"), screen.describe());

        frame();
        assertEquals(1, actions.guardFetches, "the same events are not fetched again");

        openScreen();
        assertTrue(game.drew("off (crash protection, 2 errors)"), "the list says why it is off");

        now += SecureScreen.BANNER_MS + 1;
        frame();
        assertFalse(screen.describe().contains("banner="), "the banner goes after a few seconds");
    }

    @Test
    void aModThatFailedToStartIsReportedButNotStoppedAgain() {
        actions.guardEvents.add("1\tShady\tmain\tShady.Main.main\tjava.lang.IllegalStateException\trefused");
        frame();
        assertTrue(game.drew("Shady failed to start (IllegalStateException) and was not loaded. Ctrl+Shift+J: details"));
        assertTrue(actions.unloaded.isEmpty());
    }

    @Test
    void withoutAGuardNothingIsShown() {
        actions.noGuard = true; // an older fork
        frame();
        assertEquals(0, actions.guardFetches);
        assertFalse(screen.describe().contains("banner="));
    }

    @Test
    void whileTheSaveIsCopiedNothingSwitchesAndTheScreenStaysOpen() {
        openScreen();
        actions.snapshotState = "running";
        tap(SecureScreen.KEY_ENTER);
        tap(SecureScreen.KEY_ESCAPE);
        assertEquals(SecureScreen.Mode.LIST, screen.mode(), "Esc waits: closing would let the game write its save");
        assertTrue(actions.calls.isEmpty(), "nothing was loaded or switched: " + actions.calls);
        assertTrue(game.drew("Your save is being copied first (a snapshot). One moment, then try again."));
        actions.snapshotState = "done";
        frame();
        assertTrue(game.drew("A snapshot of your save was made. To go back to it: main menu, Jumpstart, tab Snapshots."));
        tap(SecureScreen.KEY_ESCAPE);
        assertEquals(SecureScreen.Mode.CLOSED, screen.mode());
    }

    @Test
    void theMainMenuRestoresASnapshotOnlyAfterASecondEnter() {
        actions.snapshotLabels.add("Sandbox/My save   2026-11-25 10:00:00");
        actions.snapshotLabels.add("Sandbox/My save   2026-11-24 09:00:00");
        openScreen();
        for (int i = 0; i < 4 && screen.tab() != SecureScreen.Tab.SNAPSHOTS; i++) {
            tap(SecureScreen.KEY_TAB);
        }
        assertEquals(SecureScreen.Tab.SNAPSHOTS, screen.tab(), "offered in the main menu");
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_ENTER);
        assertTrue(actions.restored.isEmpty(), "the first Enter only asks");
        tap(SecureScreen.KEY_ENTER);
        assertEquals(List.of(1), actions.restored);
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
    void theMouseAnswersLikeTheKeysAndWithTheSameDelay() {
        openScreen();
        click("B unknown mod"); // a row: selects it
        click("Load live / reload (Enter)");
        assertEquals(SecureScreen.Mode.CONFIRM_ONCE, screen.mode());
        assertTrue(game.drew("Yes (J) in 3 s"), "Yes waits like the J key: " + game.drawn);

        click("Yes (J)"); // too early: does nothing, like J
        assertEquals(SecureScreen.Mode.CONFIRM_ONCE, screen.mode());
        assertTrue(actions.calls.isEmpty());

        now += SecureScreen.FIRST_DELAY_MS;
        frame();
        click("Yes (J)");
        assertEquals(SecureScreen.Mode.LIST, screen.mode());
        assertEquals(List.of("install Unknown CONFIRMED"), actions.calls);
    }

    @Test
    void aClickCountsOnlyDownAndUpOnTheSameButton() {
        openScreen();
        click("B unknown mod");
        click("Load live / reload (Enter)");
        now += SecureScreen.FIRST_DELAY_MS;
        frame();
        int[] no = game.at.get("No (N)");
        int[] yes = game.at.get("Yes (J)");
        // pressed on No, released on Yes (or the other way): nothing
        game.mouse = new int[] {no[0] + 3, no[1] + 3};
        game.mouseDown = true;
        frame();
        game.mouse = new int[] {yes[0] + 3, yes[1] + 3};
        game.mouseDown = false;
        frame();
        assertEquals(SecureScreen.Mode.CONFIRM_ONCE, screen.mode());
        assertTrue(actions.calls.isEmpty());
        click("No (N)");
        assertEquals(SecureScreen.Mode.LIST, screen.mode());
        assertTrue(actions.calls.isEmpty());
    }

    @Test
    void tabsRowsAndTheWheel() {
        openScreen();
        click("Java library");
        assertTrue(screen.describe().contains("tab=JAVA_LIBRARY"), screen.describe());
        click("Installed");
        assertTrue(screen.describe().contains("tab=INSTALLED"), screen.describe());
        int[] row = game.at.get("C shady mod"); // only in the list (the selected one is also the detail title)
        game.mouse = new int[] {row[0], row[1] + 2};
        game.wheel = -1; // down: the next mod
        frame();
        frame();
        assertTrue(game.drew("B unknown mod"));
        assertTrue(screen.describe().contains("selected=Unknown"), screen.describe());
        click("Close (Esc)");
        assertEquals(SecureScreen.Mode.CLOSED, screen.mode());
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
    void aRunningModBiteCheckBlocksNowGoesOffAtOnce() {
        actions.states.put("Verified", "on");
        actions.blockedNow = List.<String[]>of(new String[] {"Verified", "blocked"});
        frame();
        assertEquals("off", actions.states.get("Verified"));
        assertTrue(game.drew("BiteCheck blocks Verified now (blocked): it was switched off. Ctrl+Shift+J: details"));
    }

    @Test
    void inMultiplayerTheServerSwitchesABlockedModOffNotThePlayer() {
        actions.states.put("Verified", "on");
        link.connected = true;
        actions.blockedNow = List.<String[]>of(new String[] {"Verified", "blocked"});
        frame();
        assertEquals("on", actions.states.get("Verified"));
    }

    @Test
    void anItemWithSeveralModsAsksWhichOneInSinglePlayer() {
        actions.folders.put("/workshop/201", List.of(new Actions.FolderMod("Car60", "Car 60 %", null),
                new Actions.FolderMod("Car80", "Car 80 %", null)));
        openScreen();
        tap(SecureScreen.KEY_TAB);
        tap(SecureScreen.KEY_TAB);
        tap(SecureScreen.KEY_ENTER);
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_ENTER); // install "Lua Mod" (201)
        actions.workshop.states.put(201L, Workshop.SUBSCRIBED | Workshop.INSTALLED);
        frame(); settle();
        assertTrue(actions.activated.isEmpty(), "variants are not all switched on");
        assertTrue(game.drew("Car 80 %"));
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_ENTER);
        settle();
        assertEquals(List.of("Car80"), actions.activated);
    }

    @Test
    void twoModsPatchingTheSameMethodAreShownInTheDetails() {
        actions.states.put("Verified", "on");
        actions.states.put("Unknown", "on");
        actions.states.put("Shady", "off");
        var update = new io.github.jumpstartpz.core.PatchTargets.Target("zombie.iso.IsoWorld", "update", true);
        var render = new io.github.jumpstartpz.core.PatchTargets.Target("zombie.core.Core", "EndFrame", false);
        actions.targets.put("Verified", java.util.Set.of(update, render));
        actions.targets.put("Unknown", java.util.Set.of(update));
        actions.targets.put("Shady", java.util.Set.of(render));
        openScreen();
        settle();
        selectInstalled("Verified");
        frame();
        assertTrue(game.drew("Unknown patches zombie.iso.IsoWorld.update too: both run, their order can matter."));
        assertFalse(game.drew("Conflict: Shady"), "a mod that is off clashes with nothing");
        actions.states.put("Shady", "on");
        tap(SecureScreen.KEY_ESCAPE);
        openScreen();
        settle();
        selectInstalled("Verified");
        frame();
        assertTrue(game.drew("Conflict: Shady patches zombie.core.Core.EndFrame too and one of them replaces the method; only one can work."));
    }

    @Test
    void updatesAreMarkedFilteredAndTheVerifiedChannelHoldsUnverifiedOnesBack() {
        actions.states.put("Verified", "on");
        actions.states.put("Unknown", "on");
        actions.updates.addAll(List.of("Verified", "Unknown"));
        openScreen();
        settle();
        assertTrue(game.drew("update ready"));
        tap(SecureScreen.KEY_N);
        assertTrue(screen.describe().contains("Only mods with an update ready"), screen.describe());
        tap(SecureScreen.KEY_V);
        assertTrue(actions.verifiedOnly);
        selectInstalled("Unknown");
        tap(SecureScreen.KEY_ENTER);
        settle();
        assertTrue(screen.describe().contains("unknown mod: this update is not verified by BiteCheck"), screen.describe());
        assertTrue(actions.calls.isEmpty(), "held back");
        selectInstalled("Verified");
        tap(SecureScreen.KEY_ENTER);
        settle();
        assertEquals(List.of("update Verified NONE"), actions.calls, "a verified update loads");
    }

    @Test
    void hListsTheKeptVersionsAndEnterGoesBackToOne() {
        actions.states.put("Verified", "on");
        openScreen();
        settle();
        selectInstalled("Unknown");
        tap(SecureScreen.KEY_H);
        assertTrue(screen.describe().contains("No kept versions of this mod yet"), screen.describe());
        selectInstalled("Verified");
        tap(SecureScreen.KEY_H);
        frame();
        assertTrue(game.drew("2026-11-20 09:00   old   verified"));
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_ENTER);
        settle();
        assertEquals(List.of("update Verified NONE"), actions.calls, "the kept version loads like an update, through BiteCheck");
    }

    @Test
    void lTwiceLoadsTheSaveAgainAndClosesTheScreen() {
        actions.states.put("Verified", "on, next load");
        openScreen();
        settle();
        selectInstalled("Verified");
        frame();
        assertFalse(game.drew("L: load the save again now"), "not outside a single player game");
        tap(SecureScreen.KEY_L);
        assertTrue(game.drew("works in a single player game only"));
        assertEquals(0, actions.reloads);

        actions.singlePlayer = true;
        game.drawn.clear();
        frame();
        assertTrue(game.drew("L: load the save again now"), "a mod waits for the next load");
        tap(SecureScreen.KEY_L);
        assertTrue(game.drew("Press L again"));
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_UP);
        tap(SecureScreen.KEY_L);
        assertEquals(0, actions.reloads, "another key in between: L asks again");
        frame();
        frame();
        tap(SecureScreen.KEY_L);
        assertEquals(1, actions.reloads);
        for (int i = 0; i < 5; i++) {
            frame();
        }
        assertFalse(SecureScreen.blocksInput(), "the screen is closed: the game saves and goes to the menu");
    }

    @Test
    void anOffModWhoseCodeIsStillCalledSaysSo() {
        actions.states.put("Verified", "off");
        actions.held.put("Verified", 7L);
        openScreen();
        settle();
        selectInstalled("Verified");
        frame();
        assertTrue(game.drew("Off, but something still calls its Java code (7 times since"));
        actions.states.put("Verified", "on");
        tap(SecureScreen.KEY_ESCAPE);
        openScreen();
        settle();
        selectInstalled("Verified");
        game.drawn.clear();
        frame();
        assertFalse(game.drew("something still calls"), "only for a mod that is off");
    }

    @Test
    void aLibraryAnotherModBroughtInAnotherVersionIsShown() {
        actions.clashes.put("Verified", java.util.List.of(new Actions.ClassClash("OWN_COPY", "OtherMod", 3, "libshared.Lib")));
        openScreen();
        settle();
        selectInstalled("Verified");
        frame();
        assertTrue(game.drew("Library also in OtherMod, in another version (libshared.Lib, +2): this mod uses its own copy."));
    }

    @Test
    void theForksClashLinesAreRead() {
        java.util.List<Actions.ClassClash> clashes = GameActions.parseClashes(
                "own-copy\tOtherMod\t3\tlibshared.Lib\tjslib1.\nrequired\tBaseMod\t1\tlib.X\t\nnew-kind\tX\t1\ty\t");
        assertEquals(java.util.List.of(new Actions.ClassClash("OWN_COPY", "OtherMod", 3, "libshared.Lib"),
                new Actions.ClassClash("REQUIRED", "BaseMod", 1, "lib.X")), clashes, "an unknown kind is left out");
        assertTrue(GameActions.parseClashes("").isEmpty());
        assertTrue(GameActions.parseClashes(null).isEmpty());
    }

    @Test
    void pMeasuresWhileThePlayerPlaysAndTheResultsShowWhenTheScreenOpensAgain() {
        openScreen();
        settle();
        tap(SecureScreen.KEY_P);
        assertEquals(1, actions.measures);
        frame();
        assertFalse(SecureScreen.blocksInput(), "closed: the game runs while it measures");

        actions.measuringNow = true;
        openScreen();
        settle();
        frame();
        assertTrue(game.drew("Measuring: play on"));
        tap(SecureScreen.KEY_P);
        assertEquals(1, actions.measures, "one at a time");
        tap(SecureScreen.KEY_ESCAPE);

        actions.measuringNow = false;
        actions.measured = new PerfSampler.Result(10_000, 500, 1000, java.util.Map.of("Verified", new PerfSampler.Share(30, 20)));
        openScreen();
        settle();
        selectInstalled("Verified");
        frame();
        assertTrue(game.drew("Measured 10 s while you played, 500 frames (20.0 ms each). All mods together: 1.0 ms per frame. Most: Verified, 1.00 ms."));
        assertTrue(game.drew("Last measurement: 1.00 ms per frame, 5.0 % of the main thread (Java 3.0 %, Lua 2.0 %)."));
    }

    @Test
    void pOutsideAGameSaysWhy() {
        actions.measureRefusal = "not in a game";
        openScreen();
        settle();
        tap(SecureScreen.KEY_P);
        frame();
        assertTrue(game.drew("Cannot measure: not in a game."));
        assertTrue(SecureScreen.blocksInput(), "the screen stays open");
    }

    @Test
    void leftAndRightSortTheLibrary() {
        openScreen();
        tap(SecureScreen.KEY_TAB);
        tap(SecureScreen.KEY_ENTER);
        tap(SecureScreen.KEY_RIGHT);
        tap(SecureScreen.KEY_RIGHT);
        tap(SecureScreen.KEY_RIGHT);
        tap(SecureScreen.KEY_LEFT);
        assertEquals(List.of(LibraryClient.Sort.POPULAR, LibraryClient.Sort.RECENT, LibraryClient.Sort.TREND,
                LibraryClient.Sort.POPULAR, LibraryClient.Sort.TREND), actions.sorts);
        assertTrue(game.drew("Sort: trending this week (Left/Right)"));
    }

    @Test
    void aLuaModSaysBiteCheckDoesNotCheckLua() {
        openScreen();
        tap(SecureScreen.KEY_TAB);
        tap(SecureScreen.KEY_TAB);
        tap(SecureScreen.KEY_ENTER);
        tap(SecureScreen.KEY_DOWN);
        frame();
        assertTrue(game.drew("BiteCheck checks Java jars only: Lua runs unchecked, as with every Lua mod in the game."));
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
        frame(); settle();
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
        frame(); settle();
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
        frame(); settle();
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
    void theScreenSaysWhatRunsBeforeTheGameStandsStill() {
        actions.luaMods.add("Minimap");
        actions.luaStates.put("Minimap", "on");
        openScreen();
        selectInstalled("Minimap");
        game.down.add(SecureScreen.KEY_A);
        frame();
        game.down.remove(SecureScreen.KEY_A);
        assertTrue(game.drew("Switching off Minimap (Lua)..."), "the notice is the picture while the work runs");
        assertTrue(game.drew("the game stands still"));
        assertTrue(actions.deactivated.isEmpty(), "the work waits for the next frame");
        game.down.add(SecureScreen.KEY_A);
        frame(); // a second A meanwhile does nothing
        game.down.remove(SecureScreen.KEY_A);
        assertEquals(List.of("Minimap"), actions.deactivated);
        frame();
        frame();
        assertEquals(List.of("Minimap"), actions.deactivated, "the second A was not taken");
        assertEquals(null, screen.busyText());
        assertTrue(screen.describe().contains("event handlers stopped"), screen.describe());
    }

    @Test
    void aDownloadShowsItsProgressOnEveryTabAndWithTheScreenClosed() {
        openScreen();
        tap(SecureScreen.KEY_TAB);
        tap(SecureScreen.KEY_TAB);
        tap(SecureScreen.KEY_ENTER);
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_ENTER);
        actions.workshop.progress.put(201L, new long[] {450, 1000});
        frame();
        assertTrue(game.drew("Downloading Lua Mod  45 %"), String.valueOf(game.drawn));
        tap(SecureScreen.KEY_ESCAPE);
        frame();
        assertTrue(game.drew("Jumpstart: Downloading Lua Mod  45 %"), "a line at the top while it is closed");
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

    /** damnlib is the library; car1 and car2 need it. */
    private void carsAndTheirLibrary() {
        for (String id : List.of("damnlib", "car1", "car2")) {
            actions.luaMods.add(id);
            actions.luaStates.put(id, "on");
            actions.lua.put(id, LuaLive.Result.of(LuaLive.Status.RESUMED));
        }
        actions.luaRequires.put("car1", List.of("damnlib"));
        actions.luaRequires.put("car2", List.of("damnlib"));
    }

    @Test
    void aLibraryGoesOffOnlyTogetherWithTheModsThatNeedItAndTheyComeBackWithIt() {
        carsAndTheirLibrary();
        openScreen();
        selectInstalled("damnlib");
        assertTrue(game.drew("Needed by: car1 (Lua), car2 (Lua)"), "the detail pane names who needs it");
        tap(SecureScreen.KEY_A);
        assertEquals(SecureScreen.Mode.DEPENDENCY, screen.mode(), "asks first");
        assertTrue(actions.deactivated.isEmpty(), "nothing goes off before the answer");
        tap(SecureScreen.KEY_N);
        assertTrue(actions.deactivated.isEmpty(), "No: nothing goes off");
        assertEquals(SecureScreen.Mode.LIST, screen.mode());

        tap(SecureScreen.KEY_A);
        tap(SecureScreen.KEY_J);
        assertEquals(List.of("car1", "car2", "damnlib"), actions.deactivated, "the mods that need it first, then it");
        assertTrue(screen.describe().contains("and with it: car1 (Lua), car2 (Lua)"), screen.describe());

        tap(SecureScreen.KEY_A); // the library on again: the two are offered back
        assertEquals(List.of("damnlib"), actions.activated);
        assertEquals(SecureScreen.Mode.DEPENDENCY, screen.mode());
        tap(SecureScreen.KEY_J);
        assertEquals(List.of("damnlib", "car1", "car2"), actions.activated);
        assertEquals("on", actions.luaStates.get("car2"));
    }

    @Test
    void aModComesOnWithItsLibraryAndTheUnneededLibraryIsOffered() {
        carsAndTheirLibrary();
        for (String id : List.of("damnlib", "car1", "car2")) {
            actions.luaStates.put(id, "off");
        }
        openScreen();
        selectInstalled("car1");
        assertTrue(game.drew("Needs: damnlib (Lua)"));
        tap(SecureScreen.KEY_A);
        assertEquals(List.of("damnlib", "car1"), actions.activated, "the library first, without asking");
        assertTrue(screen.describe().contains("Switched on first, as it needs them: damnlib (Lua)"), screen.describe());
        assertEquals(SecureScreen.Mode.LIST, screen.mode());

        tap(SecureScreen.KEY_A); // car1 off: nothing else that is on needs damnlib now
        assertEquals(List.of("car1"), actions.deactivated);
        assertEquals(SecureScreen.Mode.DEPENDENCY, screen.mode(), "offers to switch the library off too");
        tap(SecureScreen.KEY_J);
        assertEquals(List.of("car1", "damnlib"), actions.deactivated);
    }

    @Test
    void aModWhoseLibraryIsNotInstalledStaysOff() {
        actions.luaMods.add("car1");
        actions.luaRequires.put("car1", List.of("damnlib"));
        openScreen();
        selectInstalled("car1");
        tap(SecureScreen.KEY_A);
        assertTrue(actions.activated.isEmpty());
        assertTrue(screen.describe().contains("needs damnlib, which is not installed"), screen.describe());
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
        frame(); settle();
    }

    @Test
    void aLibraryItemThatNeedsOtherItemsOffersToInstallThemFirst() {
        actions.luaModRequires = List.of(555L, 556L);
        actions.titles.put(555L, "damnlib");
        actions.workshop.states.put(556L, Workshop.SUBSCRIBED | Workshop.INSTALLED); // already there
        openScreen();
        tap(SecureScreen.KEY_TAB);
        tap(SecureScreen.KEY_TAB);
        tap(SecureScreen.KEY_ENTER);
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_ENTER);
        assertEquals(SecureScreen.Mode.DEPENDENCY, screen.mode(), "asks before downloading anything");
        assertTrue(game.drew("needs these Workshop items, which are not installed: damnlib."), "only the missing one, by name");
        assertTrue(actions.workshop.subscribed.isEmpty());
        tap(SecureScreen.KEY_J);
        assertEquals(List.of(555L), actions.workshop.subscribed, "the required item first");
        actions.workshop.states.put(555L, Workshop.SUBSCRIBED | Workshop.INSTALLED);
        frame(); settle(); // 555 is in: its mods go on, then the item itself downloads
        frame();
        assertEquals(List.of(555L, 201L), actions.workshop.subscribed);
    }

    @Test
    void noToTheRequiredItemsInstallsOnlyTheItem() {
        actions.luaModRequires = List.of(555L);
        openScreen();
        tap(SecureScreen.KEY_TAB);
        tap(SecureScreen.KEY_TAB);
        tap(SecureScreen.KEY_ENTER);
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_ENTER);
        assertTrue(game.drew("not installed: Workshop 555."), "no title from Steam: its number");
        tap(SecureScreen.KEY_N);
        assertEquals(List.of(201L), actions.workshop.subscribed);
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
    void joiningTheClientFollowsTheServersSyncStepsAtOnce() {
        actions.states.put("Verified", "on");
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "0", "mods", ""));
        screen.onServerCommand("sync", Map.of("action", "off", "mod", "Verified", "sha", "verified.jar", "workshop", "",
                "java", "1", "seconds", "60"));
        frame(); settle();
        assertEquals("off", actions.states.get("Verified"), "the server has it off: off here at once, no commit");
        assertEquals("applied {mod=Verified, sync=1, token=T}", link.sent.get(link.sent.size() - 1));
        assertNull(screen.session().update, "the step is over");

        screen.onServerCommand("sync", Map.of("action", "update", "mod", "Unknown", "sha", "unknown.jar", "workshop", "",
                "java", "1", "seconds", "60"));
        frame();
        frame(); settle();
        assertEquals(SecureScreen.Mode.CLOSED, screen.mode(), "BiteCheck's question waits for the player");
        openScreen();
        assertEquals(SecureScreen.Mode.CONFIRM_ONCE, screen.mode());
        now += SecureScreen.FIRST_DELAY_MS;
        tap(SecureScreen.KEY_J);
        frame(); settle();
        assertEquals(List.of("install Unknown CONFIRMED"), actions.calls, "loads at once: the server runs it already");
        assertEquals("applied {mod=Unknown, sync=1, token=T}", link.sent.get(link.sent.size() - 1));

        screen.onServerCommand("synced", Map.of());
        frame();
        assertTrue(screen.describe().contains("match the server"), screen.describe());
    }

    @Test
    void aServerChangeMustBeAgreedToAndNoIsReported() {
        link.connected = true;
        frame();
        assertEquals(1, link.sent.size(), "hello once when the multiplayer game starts");
        assertTrue(link.sent.get(0).startsWith("hello {mods=Verified\t"), link.sent.get(0));
        assertTrue(link.sent.get(0).contains("Unknown\t") && link.sent.get(0).endsWith("v=2}"), "with its Java mods for the server");
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "0", "mods", "Unknown\t\told\tunknown.jar\ton\n"));
        frame();
        assertTrue(screen.session().connected());

        screen.onServerCommand("prepare", Map.of("action", "update", "mod", "Unknown", "sha", "unknown.jar", "workshop", "",
                "seconds", "60", "by", "admin", "yours", "0"));
        frame();
        assertEquals(SecureScreen.Mode.CLOSED, screen.mode(), "the game goes on, nothing pops up over it");
        assertFalse(link.paused);
        assertTrue(game.drew("Jumpstart: admin wants to load the server's version Unknown for everybody. Ctrl+Shift+J to answer ("));
        now += SecureScreen.CONSENT_DELAY_MS; // a while later the player opens Jumpstart
        openScreen();
        assertEquals(SecureScreen.Mode.SERVER_CONSENT, screen.mode(), "the player is asked");
        assertEquals(SecureScreen.Tab.SERVER, screen.tab());
        assertTrue(game.drew("Admin admin changes a mod for everybody"));
        tap(SecureScreen.KEY_J); // too early: the delay starts when the question shows
        assertEquals(SecureScreen.Mode.SERVER_CONSENT, screen.mode());
        tap(SecureScreen.KEY_N);
        assertEquals("failed {mod=Unknown, reason=the player said no, token=T}", link.sent.get(link.sent.size() - 1));
        assertTrue(actions.calls.isEmpty());

        screen.onServerCommand("done", Map.of("action", "update", "mod", "Unknown", "ok", "1", "message", ""));
        frame();
        assertFalse(link.paused);
    }

    @Test
    void anAgreedUpdateGoesThroughBiteCheckAndIsReady() {
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "0", "mods", ""));
        screen.onServerCommand("prepare", Map.of("action", "update", "mod", "Unknown", "sha", "unknown.jar", "workshop", "",
                "seconds", "60", "by", "admin", "yours", "0"));
        frame();
        openScreen();
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
        assertTrue(actions.calls.isEmpty(), "phase 1 changes nothing");
        assertEquals("ready {mod=Unknown, sha=unknown.jar, token=T}", link.sent.get(link.sent.size() - 1));

        screen.onServerCommand("abort", Map.of("action", "update", "mod", "Unknown", "ok", "0", "message", "server: FAILED no"));
        frame();
        assertNull(screen.session().update);
        screen.onServerCommand("commit", Map.of("action", "update", "mod", "Unknown", "sha", "unknown.jar"));
        frame(); settle();
        assertTrue(actions.calls.isEmpty(), "after an abort nothing is done, not even on a late commit");
    }

    @Test
    void anAdminInstallsFromTheLibraryForEverybodyAndOthersCannot() {
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "1", "mods", ""));
        openScreen();
        assertEquals(SecureScreen.Tab.SERVER, screen.tab(), "on a server Jumpstart opens on its tab");
        assertEquals(List.of(SecureScreen.Tab.SERVER, SecureScreen.Tab.INSTALLED, SecureScreen.Tab.JAVA_LIBRARY,
                SecureScreen.Tab.LUA_LIBRARY, SecureScreen.Tab.SHARING), screen.tabOrder());
        tap(SecureScreen.KEY_TAB);
        tap(SecureScreen.KEY_TAB);
        type("x");
        tap(SecureScreen.KEY_ENTER);
        tap(SecureScreen.KEY_ENTER);
        assertEquals("install {item=111, token=T}", link.sent.get(link.sent.size() - 1));
        assertTrue(actions.workshop.subscribed.isEmpty(), "the server downloads first; nothing here yet");
        assertTrue(screen.describe().contains("install Java Mod One for everybody"), screen.describe());

        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "0", "mods", ""));
        link.sent.clear();
        tap(SecureScreen.KEY_ENTER);
        assertTrue(link.sent.isEmpty(), "a player who is not an admin installs nothing");
        assertTrue(screen.describe().contains("only an admin installs"), screen.describe());
    }

    @Test
    void aPlayerTakesPartInALuaInstallRound() {
        actions.folders.put("/workshop/201", List.of(new Actions.FolderMod("LuaMod", "Lua Mod", null)));
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "0", "mods", ""));
        screen.onServerCommand("prepare", Map.of("action", "install", "mod", "LuaMod", "sha", "", "workshop", "201",
                "java", "0", "seconds", "60", "by", "admin", "yours", "0"));
        frame();
        openScreen();
        assertEquals(SecureScreen.Mode.SERVER_CONSENT, screen.mode());
        now += SecureScreen.CONSENT_DELAY_MS;
        tap(SecureScreen.KEY_J);
        tap(SecureScreen.KEY_ESCAPE); // back to the game while Steam works
        frame();
        assertEquals(List.of(201L), actions.workshop.subscribed, "Steam brings the item");
        assertTrue(actions.luaLoaded.isEmpty(), "nothing loads before Steam has it");
        actions.workshop.states.put(201L, Workshop.SUBSCRIBED | Workshop.INSTALLED);
        now += 2500;
        frame(); settle();
        assertTrue(actions.luaLoaded.isEmpty(), "nothing loads before everybody is ready");
        assertEquals("ready {mod=LuaMod, sha=, token=T}", link.sent.get(link.sent.size() - 1));
        assertTrue(game.drew("Jumpstart: ready for the server's change; waiting for the other players"));
        screen.onServerCommand("commit", Map.of("action", "install", "mod", "LuaMod", "sha", ""));
        frame(); settle();
        assertEquals(List.of("server:LuaMod"), actions.luaLoaded, "its Lua joins the running game");
        assertEquals("applied {mod=LuaMod, token=T}", link.sent.get(link.sent.size() - 1));
    }

    @Test
    void aJavaInstallRoundGoesThroughBiteCheckThenLoadsTheLua() {
        actions.folders.put("/workshop/111", List.of(new Actions.FolderMod("Unknown", "B unknown mod", UNKNOWN)));
        actions.workshop.states.put(111L, Workshop.SUBSCRIBED | Workshop.INSTALLED);
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "1", "mods", ""));
        screen.onServerCommand("prepare", Map.of("action", "install", "mod", "Unknown", "sha", "unknown.jar", "workshop", "111",
                "java", "1", "seconds", "60", "by", "me", "yours", "1"));
        frame();
        frame();
        assertEquals(SecureScreen.Mode.CLOSED, screen.mode(), "the game goes on: the question waits for the player");
        assertTrue(game.drew("Jumpstart: BiteCheck has a question about Unknown. Ctrl+Shift+J to answer ("));
        assertFalse(link.paused);
        openScreen();
        assertEquals(SecureScreen.Mode.CONFIRM_ONCE, screen.mode(), "BiteCheck asks about the unknown jar");
        now += SecureScreen.FIRST_DELAY_MS;
        tap(SecureScreen.KEY_J);
        assertTrue(actions.calls.isEmpty(), "phase 1 changes nothing");
        assertEquals("ready {mod=Unknown, sha=unknown.jar, token=T}", link.sent.get(link.sent.size() - 1));

        screen.onServerCommand("commit", Map.of("action", "install", "mod", "Unknown", "sha", "unknown.jar"));
        frame(); settle();
        assertEquals(List.of("install Unknown CONFIRMED"), actions.calls);
        assertEquals(List.of("server:Unknown"), actions.luaLoaded, "then its Lua part");
        assertEquals("applied {mod=Unknown, token=T}", link.sent.get(link.sent.size() - 1));
    }

    @Test
    void theServerListScrollsWithTheSelection() {
        link.connected = true;
        frame();
        StringBuilder mods = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            mods.append("Mod").append(i).append("\t\t\t\tlua\n");
        }
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "1", "mods", mods.toString()));
        openScreen();
        for (int i = 0; i < 59; i++) {
            tap(SecureScreen.KEY_DOWN);
        }
        game.drawn.clear();
        frame();
        assertTrue(game.drew("Mod59"), "the last one is on screen when it is selected");
        assertFalse(game.drew("Mod0"), "the first ones scrolled away");
        assertTrue(game.drew("more above"));
    }

    @Test
    void anAdminRemovesAModForEverybody() {
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "1", "mods", "Minimap\t201\t\t\tlua\n"));
        openScreen();
        assertEquals(SecureScreen.Tab.SERVER, screen.tab());
        tap(SecureScreen.KEY_A);
        assertTrue(screen.describe().contains("removed with U"), "a Lua mod has no off/on for everybody: " + screen.describe());
        tap(SecureScreen.KEY_U);
        assertEquals("change {action=remove, mod=Minimap, token=T}", link.sent.get(link.sent.size() - 1));

        screen.onServerCommand("prepare", Map.of("action", "remove", "mod", "Minimap", "sha", "", "workshop", "201",
                "java", "0", "seconds", "60", "by", "me", "yours", "1"));
        frame();
        assertTrue(actions.unloaded.isEmpty(), "nothing changes before everybody is ready");
        assertEquals("ready {mod=Minimap, sha=, token=T}", link.sent.get(link.sent.size() - 1));
        screen.onServerCommand("commit", Map.of("action", "remove", "mod", "Minimap", "sha", ""));
        frame(); settle();
        assertEquals(List.of("server:Minimap"), actions.unloaded, "its Lua stops here too");
        assertEquals("applied {mod=Minimap, token=T}", link.sent.get(link.sent.size() - 1));
    }

    @Test
    void aVerifiedServerJarGetsReadyWithoutInterruptingTheGame() {
        actions.states.put("Verified", "on");
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "1", "mods", ""));
        screen.onServerCommand("prepare", Map.of("action", "update", "mod", "Verified", "sha", "verified.jar", "workshop", "",
                "seconds", "60", "by", "me", "yours", "1"));
        frame();
        frame(); settle();
        assertEquals(SecureScreen.Mode.CLOSED, screen.mode(), "BiteCheck has no question: nothing opens");
        assertFalse(link.paused);
        assertTrue(actions.calls.isEmpty());
        assertEquals("ready {mod=Verified, sha=verified.jar, token=T}", link.sent.get(link.sent.size() - 1));
        now += 61_000; // the deadline passes while the server still waits for others: nothing more to do here
        frame();
        assertEquals("ready {mod=Verified, sha=verified.jar, token=T}", link.sent.get(link.sent.size() - 1));
        assertEquals(SecureScreen.Mode.CLOSED, screen.mode());
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
        openScreen();
        now += SecureScreen.CONSENT_DELAY_MS;
        tap(SecureScreen.KEY_J);
        assertEquals("on", actions.states.get("Verified"), "nothing changes before everybody is ready");
        assertEquals("ready {mod=Verified, sha=s, token=T}", link.sent.get(link.sent.size() - 1));
        screen.onServerCommand("commit", Map.of("action", "off", "mod", "Verified", "sha", "s"));
        frame(); settle();
        assertTrue(link.paused, "paused while it changes");
        assertEquals("off", actions.states.get("Verified"));
        assertEquals("applied {mod=Verified, token=T}", link.sent.get(link.sent.size() - 1));
        screen.onServerCommand("done", Map.of("action", "off", "mod", "Verified", "ok", "1", "message", ""));
        frame();
        assertFalse(link.paused);
    }

    @Test
    void anEmergencySwitchOffAsksNobody() {
        actions.states.put("Verified", "on");
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "0", "mods", ""));
        screen.onServerCommand("prepare", Map.of("action", "off", "mod", "Verified", "sha", "s", "workshop", "",
                "seconds", "20", "by", "server", "yours", "1", "urgent", "1", "reason", "BiteCheck blocks Verified (blocked)"));
        frame();
        assertEquals(SecureScreen.Mode.CLOSED, screen.mode(), "no question, the game goes on");
        assertEquals("ready {mod=Verified, sha=s, token=T}", link.sent.get(link.sent.size() - 1));
        openScreen();
        assertTrue(game.drew("Emergency switch-off: Verified (BiteCheck blocks Verified (blocked))"));
        screen.onServerCommand("commit", Map.of("action", "off", "mod", "Verified", "sha", "s"));
        frame(); settle();
        assertEquals("off", actions.states.get("Verified"));
        assertEquals("applied {mod=Verified, token=T}", link.sent.get(link.sent.size() - 1));
    }

    @Test
    void anAdminPressesXTwiceForAnEmergencySwitchOff() {
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "1", "mods", "Mod\t1\ta\ta\ton\nMinimap\t2\t\t\tlua\n"));
        openScreen();
        link.sent.clear();
        tap(SecureScreen.KEY_X);
        assertTrue(link.sent.isEmpty(), "the first X only asks");
        assertTrue(screen.describe().contains("X again: Mod goes off for everybody"), screen.describe());
        tap(SecureScreen.KEY_X);
        assertEquals("change {action=off, mod=Mod, token=T, urgent=1}", link.sent.get(link.sent.size() - 1));
        tap(SecureScreen.KEY_DOWN);
        link.sent.clear();
        tap(SecureScreen.KEY_X);
        tap(SecureScreen.KEY_X);
        assertTrue(link.sent.isEmpty(), "a Lua mod has nothing to switch off");
    }

    @Test
    void theAdminChoosesOneModOfAnItemWithSeveral() {
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "1", "mods", ""));
        openScreen();
        screen.onServerCommand("choose", Map.of("item", "789", "mods", "Car60\tCar (60 %)\nCar80\tCar (80 %)"));
        frame();
        assertEquals(SecureScreen.Tab.SERVER, screen.tab());
        assertTrue(game.drew("Workshop item 789 has several mods"));
        assertTrue(game.drew("Car (80 %)"));
        tap(SecureScreen.KEY_DOWN);
        tap(SecureScreen.KEY_ENTER);
        assertEquals("install {item=789, mod=Car80, token=T}", link.sent.get(link.sent.size() - 1));
        assertNull(screen.session().choice);
    }

    @Test
    void afterARemoveTheNextHelloCarriesTheLuaChecksum() {
        actions.states.put("Verified", "on");
        link.connected = true;
        frame();
        screen.onServerCommand("welcome", Map.of("token", "T", "admin", "1", "mods", ""));
        screen.onServerCommand("prepare", Map.of("action", "remove", "mod", "Verified", "sha", "s", "workshop", "",
                "seconds", "60", "by", "me", "yours", "1"));
        frame();
        screen.onServerCommand("commit", Map.of("action", "remove", "mod", "Verified", "sha", "s"));
        frame(); settle();
        assertEquals(List.of("Verified"), actions.dropped, "out of the game's mod list, like on the server");
        assertEquals(0, actions.luaSums, "worked out once the round is over");
        screen.onServerCommand("done", Map.of("action", "remove", "mod", "Verified", "ok", "1", "message", ""));
        frame();
        String hello = link.sent.get(link.sent.size() - 1);
        assertTrue(hello.startsWith("hello ") && hello.contains("lua=lua-sum"), hello);
        assertEquals(1, actions.luaSums);
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
        assertTrue(screen.describe().contains("only an admin installs or switches"), screen.describe());
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

    // --- layout preview ----------------------------------------------------------------------------

    /**
     * Draws the screen with Java2D into PNGs (mod/build/layout-preview), to look at the layout without
     * the game: the installed tab with a mod's icon and description, and a library item's page. Fonts
     * differ a little from the game's; sizes and positions are the same code.
     */
    @Test
    void layoutPreview() throws Exception {
        Path out = Path.of("build", "layout-preview");
        java.nio.file.Files.createDirectories(out);
        Path knox = Path.of("C:/Program Files (x86)/Steam/steamapps/workshop/content/108600/3816775774/mods/KnoxInventoryBeta/42");
        String icon = java.nio.file.Files.isRegularFile(knox.resolve("icon.png")) ? knox.resolve("icon.png").toString() : sample(out, "icon", 128, 128);
        String poster = java.nio.file.Files.isRegularFile(knox.resolve("poster.png")) ? knox.resolve("poster.png").toString() : sample(out, "poster", 512, 288);
        String text = "Knox Inventory 1.0 BETA: the whole Project Zomboid UI in the style of Escape From Tarkov - a grid inventory "
                + "around your live 3D character, rigs and bags in bags, its own right-click menu, a car dashboard and a main menu "
                + "that is a camp by the fire or a bar in a blizzard.\n\nFeatures:\n\u2022 Grid inventory\n\u2022 Rigs and bags in bags\n"
                + "\u2022 Its own right-click menu\n\u2022 Car dashboard\n\nRequires ZombieBuddy.";
        actions.withSelf = true;
        actions.luaMods.add("KnoxInventoryBeta");
        actions.details.put("KnoxInventoryBeta", new Actions.Details(text, icon));
        actions.details.put("Verified", new Actions.Details("A verified mod: reviewed by BiteCheck.", icon));
        actions.page = new Actions.Details((text + "\n\n").repeat(4).strip(), poster);
        for (int[] size : new int[][] {{1920, 1080}, {1366, 768}}) {
            PreviewGame pg = new PreviewGame(size[0], size[1]);
            SecureScreen s = new SecureScreen(pg, actions, false, Runnable::run, link);
            SecureScreen.install(s);
            long[] t = {now};
            Runnable frame = () -> { pg.clear(); s.frame(t[0]); t[0] += 16; };
            java.util.function.IntConsumer tap = key -> { pg.down.add(key); frame.run(); pg.down.remove(key); frame.run(); };
            pg.down.add(SecureScreen.KEY_LEFT_CONTROL);
            pg.down.add(SecureScreen.KEY_LEFT_SHIFT);
            tap.accept(SecureScreen.KEY_J);
            pg.down.clear();
            frame.run();
            for (int i = 0; i < 10 && !s.describe().contains("selected=KnoxInventoryBeta"); i++) {
                tap.accept(SecureScreen.KEY_DOWN);
            }
            frame.run();
            pg.save(out.resolve("installed-" + size[0] + "x" + size[1] + ".png"));
            tap.accept(SecureScreen.KEY_TAB);
            tap.accept(SecureScreen.KEY_ENTER);
            t[0] += 1000; // the page is asked for once the selection stays
            for (int i = 0; i < 5; i++) {
                frame.run();
            }
            pg.save(out.resolve("library-" + size[0] + "x" + size[1] + ".png"));
        }
        assertTrue(java.nio.file.Files.isRegularFile(out.resolve("library-1366x768.png")));
    }

    private static String sample(Path dir, String name, int w, int h) throws java.io.IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setPaint(new java.awt.GradientPaint(0, 0, new java.awt.Color(0x2E5A3A), w, h, new java.awt.Color(0x8A6A2A)));
        g.fillRect(0, 0, w, h);
        g.dispose();
        Path file = dir.resolve(name + ".png");
        javax.imageio.ImageIO.write(img, "png", file.toFile());
        return file.toAbsolutePath().toString();
    }

    /** A GameScreen drawing with Java2D into an image. */
    private static final class PreviewGame implements GameScreen {
        final Set<Integer> down = new HashSet<>();
        private final java.awt.image.BufferedImage img;
        private final java.awt.Graphics2D g;
        private final Map<String, java.awt.image.BufferedImage> images = new HashMap<>();

        PreviewGame(int w, int h) {
            img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            g = img.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }

        void clear() {
            g.setColor(new java.awt.Color(0x3A4A3A));
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
        }

        void save(Path file) throws java.io.IOException {
            javax.imageio.ImageIO.write(img, "png", file.toFile());
        }

        private static java.awt.Font font(Font f) {
            return new java.awt.Font(java.awt.Font.SANS_SERIF, f == Font.LARGE ? java.awt.Font.BOLD : java.awt.Font.PLAIN,
                    switch (f) { case SMALL -> 14; case MEDIUM -> 18; case LARGE -> 24; });
        }

        @Override public int width() { return img.getWidth(); }
        @Override public int height() { return img.getHeight(); }
        @Override public int fontHeight(Font font) { return g.getFontMetrics(font(font)).getHeight(); }
        @Override public int textWidth(Font font, String text) { return g.getFontMetrics(font(font)).stringWidth(text); }

        @Override
        public void text(Font font, int x, int y, String text, float r, float gr, float b, float a) {
            g.setFont(font(font));
            g.setColor(new java.awt.Color(r, gr, b, a));
            g.drawString(text, x, y + g.getFontMetrics().getAscent());
        }

        @Override
        public void rect(int x, int y, int w, int h, float r, float gr, float b, float a) {
            g.setColor(new java.awt.Color(r, gr, b, a));
            g.fillRect(x, y, w, h);
        }

        @Override
        public boolean image(String file, int x, int y, int w, int h) {
            java.awt.image.BufferedImage i = images.computeIfAbsent(file, f -> {
                try {
                    return javax.imageio.ImageIO.read(new java.io.File(f));
                } catch (java.io.IOException e) {
                    return null;
                }
            });
            if (i == null) {
                return false;
            }
            double f = Math.min((double) w / i.getWidth(), (double) h / i.getHeight());
            int dw = (int) Math.round(i.getWidth() * f);
            int dh = (int) Math.round(i.getHeight() * f);
            g.drawImage(i, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null);
            return true;
        }

        @Override public boolean physicalKeyDown(int glfwKey) { return down.contains(glfwKey); }
        @Override public String keyName(int glfwKey) { return String.valueOf((char) Character.toLowerCase(glfwKey)); }
        @Override public int[] mousePosition() { return null; }
        @Override public boolean physicalMouseDown(int button) { return false; }
        @Override public int wheel() { return 0; }
        @Override public boolean inGame() { return false; }
        @Override public void pause(boolean paused) { }
        @Override public String language() { return "DE"; }
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
        settle();
    }

    /** Frames until the work the screen announced ("Switching off ...") has run, as a player waits. */
    private void settle() {
        for (int i = 0; i < 6 && screen.busyText() != null; i++) {
            frame();
        }
    }

    /** Clicks the button (or tab, or row) whose label starts with {@code label}: down and up on it. */
    private void click(String label) {
        int[] p = game.at.entrySet().stream().filter(e -> e.getKey().startsWith(label)).map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new AssertionError("nothing drawn as " + label + ": " + game.drawn));
        clickAt(p[0] + 3, p[1] + 3);
    }

    private void clickAt(int x, int y) {
        game.mouse = new int[] {x, y};
        game.mouseDown = true;
        frame();
        game.mouseDown = false;
        frame();
        settle();
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
        /** Where each text was drawn last ({x, y}), to click on buttons by their label. */
        final Map<String, int[]> at = new HashMap<>();
        int[] mouse = {0, 0};
        boolean mouseDown;
        int wheel;
        boolean paused;

        boolean drew(String text) {
            return drawn.stream().anyMatch(t -> t.contains(text));
        }

        @Override public int width() { return 1920; }
        @Override public int height() { return 1080; }
        @Override public int fontHeight(Font font) { return 16; }
        @Override public int textWidth(Font font, String text) { return text.length() * 8; }
        @Override public void text(Font font, int x, int y, String text, float r, float g, float b, float a) { drawn.add(text); at.put(text, new int[] {x, y}); }
        @Override public int[] mousePosition() { return mouse; }
        @Override public boolean physicalMouseDown(int button) { return button == 0 && mouseDown; }
        @Override public int wheel() { int w = wheel; wheel = 0; return w; }
        @Override public void rect(int x, int y, int w, int h, float r, float g, float b, float a) { }
        @Override public boolean image(String file, int x, int y, int w, int h) { drawn.add("image:" + file); return true; }
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
        /** mod.info require= of the Lua mods. */
        final Map<String, List<String>> luaRequires = new HashMap<>();
        final Map<String, String> luaStates = new HashMap<>();
        final List<String> deactivated = new ArrayList<>();
        final List<String> unloaded = new ArrayList<>();
        final List<String> uninstalled = new ArrayList<>();
        final Map<String, Details> details = new HashMap<>();
        Details page = Details.NONE;
        /** The fork's guard: events (one line each) and status per mod; null lastSeq = no guard. */
        final List<String> guardEvents = new ArrayList<>();
        final Map<String, String> guardStatus = new HashMap<>();
        int guardPolls;
        int guardFetches;
        boolean noGuard;
        String snapshotState;
        final List<String> snapshotLabels = new ArrayList<>();
        final List<Integer> restored = new ArrayList<>();

        @Override
        public String snapshotState() {
            return snapshotState;
        }

        @Override
        public List<String> snapshots() {
            return List.copyOf(snapshotLabels);
        }

        @Override
        public String restoreSnapshot(int index) {
            restored.add(index);
            return "OK";
        }

        @Override
        public String guardLastSeq() {
            guardPolls++;
            return noGuard ? null : Integer.toString(guardEvents.size());
        }

        @Override
        public String guardEvents(String sinceSeq) {
            guardFetches++;
            int since = Integer.parseInt(sinceSeq);
            return String.join("\n", guardEvents.subList(Math.min(since, guardEvents.size()), guardEvents.size()));
        }

        @Override
        public String guardStatus(String modId) {
            return guardStatus.getOrDefault(modId, "");
        }

        @Override
        public Details details(String modId) {
            return details.getOrDefault(modId, Details.NONE);
        }

        @Override
        public Details workshopDetails(long workshopId) {
            return page;
        }

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
                    list.add(new InstalledMod(id, id + " (Lua)", null, 201, luaRequires.getOrDefault(id, List.of())));
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
        public LuaLive.Result loadLuaForServer(String modId) {
            luaLoaded.add("server:" + modId);
            return lua.getOrDefault(modId, new LuaLive.Result(LuaLive.Status.LOADED, 1, List.of(), List.of()));
        }

        @Override
        public int unloadLuaForServer(String modId) {
            unloaded.add("server:" + modId);
            return 2;
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

        final List<String> dropped = new ArrayList<>();
        int luaSums;

        @Override
        public boolean dropFromModList(String modId) {
            dropped.add(modId);
            return true;
        }

        @Override
        public String recomputeLuaChecksum(String why) {
            luaSums++;
            return "lua-sum";
        }

        // marketplace
        final List<String> searches = new ArrayList<>();
        final FakeWorkshop workshop = new FakeWorkshop();
        final Map<String, List<FolderMod>> folders = new HashMap<>();
        final List<String> activated = new ArrayList<>();

        final List<LibraryClient.Sort> sorts = new ArrayList<>();
        List<String[]> blockedNow = List.of();
        final Map<String, java.util.Set<io.github.jumpstartpz.core.PatchTargets.Target>> targets = new HashMap<>();
        final java.util.Set<String> updates = new java.util.HashSet<>();
        boolean verifiedOnly;

        @Override public boolean updateReady(ModEntry mod) { return updates.contains(mod.id()); }
        boolean singlePlayer;
        int reloads;
        @Override public boolean canReloadSave() { return singlePlayer; }
        @Override public String reloadSave() { reloads++; return null; }
        final Map<String, Long> held = new HashMap<>();
        int measures;
        String measureRefusal;
        boolean measuringNow;
        PerfSampler.Result measured;
        @Override public String startMeasure() { if (measureRefusal != null) { return measureRefusal; } measures++; return null; }
        @Override public PerfSampler.Result measurement() { return measured; }
        @Override public boolean measuring() { return measuringNow; }
        final Map<String, java.util.List<Actions.ClassClash>> clashes = new HashMap<>();
        @Override public java.util.List<Actions.ClassClash> classClashes(String modId) { return clashes.getOrDefault(modId, java.util.List.of()); }
        @Override public long callsWhileOff(String modId) { return held.getOrDefault(modId, 0L); }

        @Override
        public List<String[]> versions(ModEntry mod) {
            return mod.id().equals("Verified")
                    ? List.of(new String[] {"new", "2026-11-27 12:00   new   verified   *"}, new String[] {"old", "2026-11-20 09:00   old   verified"})
                    : List.of();
        }

        @Override
        public ModEntry version(ModEntry mod, String sha256) {
            return new ModEntry(mod.id(), mod.name(), mod.jar(), "kept-" + sha256);
        }
        @Override public boolean verifiedUpdatesOnly() { return verifiedOnly; }
        @Override public void setVerifiedUpdatesOnly(boolean on) { verifiedOnly = on; }

        @Override
        public java.util.Set<io.github.jumpstartpz.core.PatchTargets.Target> patchTargets(ModEntry mod) {
            return targets.getOrDefault(mod.id(), java.util.Set.of());
        }

        @Override
        public List<String[]> newlyBlocked() {
            List<String[]> b = blockedNow;
            blockedNow = List.of();
            return b;
        }

        @Override
        public LibraryClient.Page search(LibraryClient.Kind kind, String query, LibraryClient.Sort sort, String cursor) {
            sorts.add(sort);
            return search(kind, query, cursor);
        }

        @Override
        public LibraryClient.Page search(LibraryClient.Kind kind, String query, String cursor) {
            searches.add(kind + " '" + query + "' " + cursor);
            return new LibraryClient.Page(2, "", List.of(
                    new LibraryClient.Item(111, "Java Mod One", 500, 0, true, List.of("Build 42"), "java"),
                    new LibraryClient.Item(201, "Lua Mod", 7, 0, false, List.of(), "lua", luaModRequires)));
        }

        /** The Workshop items "Lua Mod" (201) needs, and the titles Steam has for them. */
        List<Long> luaModRequires = List.of();
        final Map<Long, String> titles = new HashMap<>();

        @Override
        public Map<Long, String> workshopTitles(List<Long> ids) {
            Map<Long, String> out = new HashMap<>();
            for (long id : ids) {
                if (titles.containsKey(id)) {
                    out.put(id, titles.get(id));
                }
            }
            return out;
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
        final Map<Long, long[]> progress = new HashMap<>();

        @Override public long[] progress(long itemId) { return progress.get(itemId); }
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
