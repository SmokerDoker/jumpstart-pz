package io.github.jumpstartpz.core.mp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerCoordinatorTest {
    private static final String NEW = "b".repeat(64);

    private FakePort port;
    private ServerCoordinator<String> server;

    @BeforeEach
    void setUp() {
        port = new FakePort();
        port.connections.addAll(List.of("admin", "anna", "ben"));
        server = new ServerCoordinator<>(port, 60_000);
        for (String c : port.connections) {
            server.onCommand(c, "hello", Map.of(), 0);
        }
    }

    @Test
    void welcomeCarriesATokenTheManifestAndTheAdminFlag() {
        Map<String, String> w = port.last("anna", "welcome");
        assertEquals(32, w.get("token").length());
        assertEquals("0", w.get("admin"));
        assertEquals("1", port.last("admin", "welcome").get("admin"));
        assertEquals("Mod\t123\t" + "a".repeat(64) + "\t" + NEW + "\ton\n", w.get("mods"));
        assertFalse(w.get("token").equals(port.last("ben", "welcome").get("token")));
    }

    @Test
    void anUpdateWaitsForEverybodyThenTheServerFollows() {
        server.onCommand("admin", "change", Map.of("token", token("admin"), "action", "update", "mod", "Mod"), 0);
        assertTrue(server.updateRunning());
        for (String c : List.of("admin", "anna", "ben")) {
            Map<String, String> p = port.last(c, "prepare");
            assertEquals(NEW, p.get("sha"));
            assertEquals("123", p.get("workshop"));
        }
        ready("admin");
        ready("anna");
        server.tick(1000);
        assertTrue(port.applied.isEmpty(), "ben is not ready yet");
        ready("ben");
        server.tick(2000);
        assertEquals(List.of("UPDATE Mod " + NEW + " admin=true"), port.applied);
        assertEquals("1", port.last("admin", "prepare").get("yours"), "the admin who started it is not asked again");
        assertEquals("0", port.last("anna", "prepare").get("yours"));
        assertTrue(port.kicked.isEmpty());
        assertEquals(List.of(true), port.pauses, "the world stops once everybody is ready, not before");
        assertNull(port.last("anna", "done"), "first everybody does the change");
        assertEquals(NEW, port.last("anna", "commit").get("sha"));
        assertTrue(server.updateRunning());
        applied("Mod", "admin", "anna", "ben");
        server.tick(3000);
        assertEquals("1", port.last("anna", "done").get("ok"));
        assertEquals(List.of(true, false), port.pauses);
        assertFalse(server.updateRunning());
    }

    @Test
    void whoIsTooSlowOrSaysNoIsKicked() {
        server.onCommand("admin", "change", Map.of("token", token("admin"), "action", "update", "mod", "Mod"), 0);
        ready("admin");
        server.onCommand("anna", "failed", Map.of("token", token("anna"), "mod", "Mod", "reason", "the player said no"), 10);
        server.tick(59_999);
        assertTrue(port.kicked.isEmpty(), "ben still has time");
        server.tick(60_000);
        assertEquals(Set.of("anna", "ben"), port.kicked.keySet());
        assertTrue(port.kicked.get("anna").contains("the player said no"));
        assertTrue(port.kicked.get("ben").contains("not ready in time"));
        assertEquals(1, port.applied.size());
        assertNull(port.last("anna", "done"));
    }

    @Test
    void commandsWithoutTheTokenDoNothing() {
        server.onCommand("admin", "change", Map.of("token", "guessed", "action", "update", "mod", "Mod"), 0);
        assertFalse(server.updateRunning());
        server.onCommand("admin", "change", Map.of("action", "update", "mod", "Mod"), 0);
        assertFalse(server.updateRunning());
    }

    @Test
    void onlyAdminsStartUpdates() {
        server.onCommand("anna", "change", Map.of("token", token("anna"), "action", "off", "mod", "Mod"), 0);
        assertFalse(server.updateRunning());
        assertEquals("only admins can switch mods in multiplayer", port.last("anna", "note").get("text"));
    }

    @Test
    void readyForAnotherHashDoesNotCount() {
        server.onCommand("admin", "change", Map.of("token", token("admin"), "action", "update", "mod", "Mod"), 0);
        for (String c : List.of("admin", "anna", "ben")) {
            server.onCommand(c, "ready", Map.of("token", token(c), "mod", "Mod", "sha", "c".repeat(64)), 1);
        }
        server.tick(2);
        assertTrue(server.updateRunning());
    }

    @Test
    void whoLeavesDoesNotHoldTheOthersUp() {
        server.onCommand("admin", "change", Map.of("token", token("admin"), "action", "update", "mod", "Mod"), 0);
        ready("admin");
        ready("anna");
        port.connections.remove("ben");
        server.tick(5);
        assertNull(port.last("ben", "commit"));
        applied("Mod", "admin");
        port.connections.remove("anna"); // leaves in phase 2 too
        server.tick(6);
        assertFalse(server.updateRunning());
        assertTrue(port.kicked.isEmpty());
    }

    @Test
    void aBlockedJarIsNeverDistributed() {
        port.verdict = "REFUSE";
        server.onCommand("admin", "change", Map.of("token", token("admin"), "action", "update", "mod", "Mod"), 0);
        assertFalse(server.updateRunning());
        assertNull(port.last("anna", "prepare"));
    }

    @Test
    void anAdminSwitchesAModOffForEverybody() {
        server.onCommand("admin", "change", Map.of("token", token("admin"), "action", "off", "mod", "Mod"), 0);
        Map<String, String> p = port.last("ben", "prepare");
        assertEquals("off", p.get("action"));
        assertEquals("a".repeat(64), p.get("sha"), "off and on name the loaded version");
        for (String c : List.of("admin", "anna", "ben")) {
            server.onCommand(c, "ready", Map.of("token", token(c), "mod", "Mod", "sha", "a".repeat(64)), 1);
        }
        server.tick(2);
        assertEquals(List.of("OFF Mod " + "a".repeat(64) + " admin=true"), port.applied);
        applied("Mod", "admin", "anna", "ben");
        server.tick(3);
        assertEquals("off", port.last("anna", "done").get("action"));
    }

    private String token(String c) {
        return port.last(c, "welcome").get("token");
    }

    @Test
    void anAdminInstallsAWorkshopModForEverybodyAfterTheServerDownloadedIt() {
        server.onCommand("admin", "install", Map.of("token", token("admin"), "item", "3827957976"), 0);
        assertEquals(List.of("3827957976"), port.fetching);
        assertTrue(server.updateRunning(), "busy while the server downloads");
        server.tick(1000);
        assertNull(port.last("anna", "prepare"), "nobody is asked before the server has the item");
        port.fetched = new ServerCoordinator.Fetched(null, "RealLimbs", NEW, "CONFIRM", true);
        server.tick(2000);
        Map<String, String> p = port.last("anna", "prepare");
        assertEquals("install", p.get("action"));
        assertEquals("RealLimbs", p.get("mod"));
        assertEquals("3827957976", p.get("workshop"));
        assertEquals("1", p.get("java"));
        for (String c : List.of("admin", "anna", "ben")) {
            server.onCommand(c, "ready", Map.of("token", token(c), "mod", "RealLimbs", "sha", NEW), 3);
        }
        server.tick(4000);
        assertEquals(List.of("INSTALL RealLimbs " + NEW + " admin=true"), port.applied);
        applied("RealLimbs", "admin", "anna", "ben");
        server.tick(4001);
        assertEquals("1", port.last("ben", "done").get("ok"));
    }

    @Test
    void aLuaModInstallsWithoutAHash() {
        server.onCommand("admin", "install", Map.of("token", token("admin"), "item", "456"), 0);
        port.fetched = new ServerCoordinator.Fetched(null, "Minimap", "", "", false);
        server.tick(1);
        assertEquals("0", port.last("anna", "prepare").get("java"));
        for (String c : List.of("admin", "anna", "ben")) {
            server.onCommand(c, "ready", Map.of("token", token(c), "mod", "Minimap", "sha", ""), 2);
        }
        server.tick(3);
        assertEquals(List.of("INSTALL Minimap  admin=true"), port.applied);
    }

    @Test
    void onlyAdminsInstallAndABlockedJarIsNotOffered() {
        server.onCommand("anna", "install", Map.of("token", token("anna"), "item", "1"), 0);
        assertTrue(port.fetching.isEmpty());
        assertTrue(port.last("anna", "note").get("text").contains("only admins"));
        server.onCommand("admin", "install", Map.of("token", token("admin"), "item", "not a number"), 0);
        assertTrue(port.fetching.isEmpty());
        server.onCommand("admin", "install", Map.of("token", token("admin"), "item", "9"), 0);
        port.fetched = new ServerCoordinator.Fetched(null, "Evil", NEW, "REFUSE", true);
        server.tick(1);
        assertNull(port.last("anna", "prepare"));
        assertTrue(port.last("admin", "note").get("text").contains("BiteCheck blocks Evil"));
        assertFalse(server.updateRunning());
    }

    @Test
    void anAdminRemovesALuaModForEverybody() {
        port.withLuaMod = true;
        server.onCommand("admin", "change", Map.of("token", token("admin"), "action", "off", "mod", "Minimap"), 0);
        assertNull(port.last("anna", "prepare"), "Lua mods are not switched off and on, only removed");
        server.onCommand("admin", "change", Map.of("token", token("admin"), "action", "remove", "mod", "Minimap"), 0);
        assertEquals("remove", port.last("anna", "prepare").get("action"));
        assertEquals("0", port.last("anna", "prepare").get("java"));
        for (String c : List.of("admin", "anna", "ben")) {
            server.onCommand(c, "ready", Map.of("token", token(c), "mod", "Minimap", "sha", ""), 1);
        }
        server.tick(2);
        assertEquals(List.of("REMOVE Minimap  admin=true"), port.applied);
    }

    @Test
    void whenTheServerCannotNobodyChangesAnything() {
        port.applyProblem = "FAILED the jar does not load";
        server.onCommand("admin", "change", Map.of("token", token("admin"), "action", "update", "mod", "Mod"), 0);
        ready("admin");
        ready("anna");
        ready("ben");
        server.tick(1);
        for (String c : List.of("admin", "anna", "ben")) {
            assertNull(port.last(c, "commit"), c + " must not change anything");
            Map<String, String> abort = port.last(c, "abort");
            assertEquals("0", abort.get("ok"));
            assertTrue(abort.get("message").contains("the jar does not load"));
        }
        assertEquals(List.of(true, false), port.pauses);
        assertTrue(port.kicked.isEmpty());
        assertFalse(server.updateRunning());
    }

    @Test
    void whoCannotFollowTheServerIsKickedTheOthersGoOn() {
        server.onCommand("admin", "change", Map.of("token", token("admin"), "action", "update", "mod", "Mod"), 0);
        ready("admin");
        ready("anna");
        ready("ben");
        server.tick(1);
        applied("Mod", "admin");
        server.onCommand("anna", "failed", Map.of("token", token("anna"), "mod", "Mod", "reason", "FAILED no memory"), 2);
        server.tick(3);
        assertTrue(server.updateRunning(), "ben still has time");
        assertEquals(List.of(true), port.pauses, "the world stays stopped meanwhile");
        server.tick(1 + ServerCoordinator.COMMIT_DEADLINE_MS);
        assertEquals(Set.of("anna", "ben"), port.kicked.keySet());
        assertTrue(port.kicked.get("anna").contains("no memory"));
        assertTrue(port.kicked.get("ben").contains("not done in time"));
        assertTrue(port.last("admin", "done").get("message").contains("anna"));
        assertEquals(List.of(true, false), port.pauses);
    }

    @Test
    void readyAfterTheCommitAndAppliedBeforeItDoNotCount() {
        server.onCommand("admin", "change", Map.of("token", token("admin"), "action", "update", "mod", "Mod"), 0);
        applied("Mod", "admin", "anna", "ben"); // too early: nobody was told to change yet
        ready("admin");
        server.tick(60_000); // anna and ben are kicked
        ready("anna"); // too late
        applied("Mod", "admin");
        server.tick(60_001);
        assertFalse(server.updateRunning());
        assertEquals(Set.of("anna", "ben"), port.kicked.keySet());
        assertNull(port.last("anna", "done"));
    }

    @Test
    void theCountdownComesFromTheServersSetting() {
        assertEquals(120_000, ServerCoordinator.deadlineMs("120"));
        assertEquals(30_000, ServerCoordinator.deadlineMs("5"));
        assertEquals(900_000, ServerCoordinator.deadlineMs("99999"));
        assertEquals(ServerCoordinator.DEFAULT_DEADLINE_MS, ServerCoordinator.deadlineMs(null));
        assertEquals(ServerCoordinator.DEFAULT_DEADLINE_MS, ServerCoordinator.deadlineMs("soon"));
    }

    private static final String OLD = "a".repeat(64);

    @Test
    void differencesBringAClientInLineWithTheServer() {
        List<ServerCoordinator.ModInfo> server = List.of(
                new ServerCoordinator.ModInfo("Same", "1", OLD, OLD, "on"),
                new ServerCoordinator.ModInfo("Other", "2", OLD, OLD, "on"),
                new ServerCoordinator.ModInfo("Missing", "3", OLD, OLD, "client only"),
                new ServerCoordinator.ModInfo("OffThere", "4", OLD, OLD, "off"),
                new ServerCoordinator.ModInfo("OffHere", "5", OLD, OLD, "on"),
                new ServerCoordinator.ModInfo("Minimap", "6", "", "", ServerCoordinator.LUA));
        List<ServerCoordinator.ClientMod> client = List.of(
                new ServerCoordinator.ClientMod("Same", OLD, "on"),
                new ServerCoordinator.ClientMod("Other", NEW, "on"),
                new ServerCoordinator.ClientMod("OffThere", OLD, "on"),
                new ServerCoordinator.ClientMod("OffHere", OLD, "off"),
                new ServerCoordinator.ClientMod("Cheat", NEW, "on"),
                new ServerCoordinator.ClientMod("Idle", NEW, "off"));
        List<String> steps = ServerCoordinator.differences(server, client).stream()
                .map(st -> st.action().wire() + " " + st.mod()).toList();
        assertEquals(List.of("update Other", "update Missing", "off OffThere", "on OffHere", "off Cheat"), steps);
    }

    @Test
    void aJoiningClientWithAnotherVersionIsBroughtInLine() {
        port.connections.add("cleo");
        server.onCommand("cleo", "hello", Map.of("mods", "Mod\t" + NEW + "\ton\n"), 0);
        Map<String, String> sync = port.last("cleo", "sync");
        assertEquals("update", sync.get("action"));
        assertEquals(OLD, sync.get("sha"), "the version the server runs");
        assertEquals("123", sync.get("workshop"));
        assertTrue(server.syncing("cleo"));
        server.onCommand("cleo", "applied", Map.of("token", token("cleo"), "mod", "Mod", "sync", "1"), 5);
        assertFalse(server.syncing("cleo"));
        assertTrue(port.sent.get("cleo").stream().anyMatch(e -> e.getKey().equals("synced")));
        assertTrue(port.kicked.isEmpty());
    }

    @Test
    void aJoiningClientThatCannotFollowIsKicked() {
        port.connections.add("cleo");
        port.connections.add("dan");
        server.onCommand("cleo", "hello", Map.of("mods", ""), 0);
        server.onCommand("dan", "hello", Map.of("mods", ""), 0);
        server.onCommand("cleo", "failed", Map.of("token", token("cleo"), "mod", "Mod", "sync", "1", "reason", "BiteCheck blocks the jar"), 1);
        assertTrue(port.kicked.get("cleo").contains("BiteCheck blocks the jar"));
        server.tick(59_999);
        assertFalse(port.kicked.containsKey("dan"));
        server.tick(60_000);
        assertTrue(port.kicked.get("dan").contains("not in line with the server in time"));
    }

    @Test
    void aClientInLineGetsNothingAndAnOlderClientIsLeftAlone() {
        port.connections.add("cleo");
        server.onCommand("cleo", "hello", Map.of("mods", "Mod\t" + OLD + "\ton\n"), 0);
        assertNull(port.last("cleo", "sync"));
        server.onCommand("ben", "hello", Map.of(), 0);
        assertNull(port.last("ben", "sync"), "a client without the list is not compared");
    }

    @Test
    void whoJoinsDuringPhaseOneTakesPartWhoJoinsDuringPhaseTwoIsCheckedAfter() {
        server.onCommand("admin", "change", Map.of("token", token("admin"), "action", "update", "mod", "Mod"), 0);
        port.connections.add("cleo");
        server.onCommand("cleo", "hello", Map.of("mods", ""), 1000);
        Map<String, String> p = port.last("cleo", "prepare");
        assertEquals(NEW, p.get("sha"), "phase 1: cleo takes part");
        assertEquals("59", p.get("seconds"), "with the time that is left");
        assertNull(port.last("cleo", "sync"), "no comparison in the middle of a round");
        for (String c : List.of("admin", "anna", "ben", "cleo")) {
            ready(c);
        }
        server.tick(2000); // phase 2
        port.connections.add("dan");
        server.onCommand("dan", "hello", Map.of("mods", ""), 2001);
        assertNull(port.last("dan", "prepare"), "too late for this round");
        applied("Mod", "admin", "anna", "ben", "cleo");
        server.tick(3000);
        assertEquals(Map.of(), port.last("dan", "check"), "dan says hello again and is compared with the new state");
        assertNull(port.last("cleo", "check"), "cleo got done and says hello again anyway");
    }

    private void applied(String mod, String... clients) {
        for (String c : clients) {
            server.onCommand(c, "applied", Map.of("token", token(c), "mod", mod), 2);
        }
    }

    private void ready(String c) {
        server.onCommand(c, "ready", Map.of("token", token(c), "mod", "Mod", "sha", NEW), 1);
    }

    private static final class FakePort implements ServerCoordinator.Port<String> {
        final List<String> connections = new ArrayList<>();
        final Map<String, List<Map.Entry<String, Map<String, String>>>> sent = new HashMap<>();
        final Map<String, String> kicked = new HashMap<>();
        final List<String> applied = new ArrayList<>();
        String verdict = "ALLOW";

        Map<String, String> last(String c, String command) {
            Map<String, String> found = null;
            for (Map.Entry<String, Map<String, String>> e : sent.getOrDefault(c, List.of())) {
                if (e.getKey().equals(command)) {
                    found = e.getValue();
                }
            }
            return found;
        }

        @Override public void send(String c, String command, Map<String, String> args) {
            sent.computeIfAbsent(c, k -> new ArrayList<>()).add(Map.entry(command, Map.copyOf(args)));
        }
        @Override public void kick(String c, String reason) { kicked.put(c, reason); connections.remove(c); }
        @Override public boolean isAdmin(String c) { return c.equals("admin"); }
        @Override public List<String> connections() { return List.copyOf(connections); }
        @Override public String name(String c) { return c; }
        @Override public List<ServerCoordinator.ModInfo> manifest() {
            List<ServerCoordinator.ModInfo> mods = new ArrayList<>();
            mods.add(new ServerCoordinator.ModInfo("Mod", "123", "a".repeat(64), NEW, "on"));
            if (withLuaMod) {
                mods.add(new ServerCoordinator.ModInfo("Minimap", "456", "", "", ServerCoordinator.LUA));
            }
            return mods;
        }
        @Override public ServerCoordinator.Candidate candidate(String modId) {
            return modId.equals("Mod") ? new ServerCoordinator.Candidate(NEW, "123", verdict) : null;
        }
        @Override public String apply(ServerCoordinator.Action action, String modId, String sha256, boolean adminConfirmed) {
            applied.add(action + " " + modId + " " + sha256 + " admin=" + adminConfirmed);
            return applyProblem;
        }

        String applyProblem;
        final List<Boolean> pauses = new ArrayList<>();

        @Override public void pauseWorld(boolean paused) {
            pauses.add(paused);
        }

        final List<String> fetching = new ArrayList<>();
        ServerCoordinator.Fetched fetched;
        boolean withLuaMod;

        @Override public String fetch(String workshopId) {
            fetching.add(workshopId);
            return null;
        }
        @Override public ServerCoordinator.Fetched fetched(String workshopId) {
            return fetched;
        }
    }
}
