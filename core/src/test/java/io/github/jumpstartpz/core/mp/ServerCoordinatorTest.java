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
        server.onCommand("admin", "update", Map.of("token", token("admin"), "mod", "Mod"), 0);
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
        assertEquals(List.of("Mod " + NEW + " admin=true"), port.applied);
        assertTrue(port.kicked.isEmpty());
        assertEquals("1", port.last("anna", "done").get("ok"));
        assertFalse(server.updateRunning());
    }

    @Test
    void whoIsTooSlowOrSaysNoIsKicked() {
        server.onCommand("admin", "update", Map.of("token", token("admin"), "mod", "Mod"), 0);
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
        server.onCommand("admin", "update", Map.of("token", "guessed", "mod", "Mod"), 0);
        assertFalse(server.updateRunning());
        server.onCommand("admin", "update", Map.of("mod", "Mod"), 0);
        assertFalse(server.updateRunning());
    }

    @Test
    void onlyAdminsStartUpdates() {
        server.onCommand("anna", "update", Map.of("token", token("anna"), "mod", "Mod"), 0);
        assertFalse(server.updateRunning());
        assertEquals("only admins can start an update", port.last("anna", "note").get("text"));
    }

    @Test
    void readyForAnotherHashDoesNotCount() {
        server.onCommand("admin", "update", Map.of("token", token("admin"), "mod", "Mod"), 0);
        for (String c : List.of("admin", "anna", "ben")) {
            server.onCommand(c, "ready", Map.of("token", token(c), "mod", "Mod", "sha", "c".repeat(64)), 1);
        }
        server.tick(2);
        assertTrue(server.updateRunning());
    }

    @Test
    void whoLeavesDoesNotHoldTheOthersUp() {
        server.onCommand("admin", "update", Map.of("token", token("admin"), "mod", "Mod"), 0);
        ready("admin");
        ready("anna");
        port.connections.remove("ben");
        server.tick(5);
        assertFalse(server.updateRunning());
        assertTrue(port.kicked.isEmpty());
    }

    @Test
    void aBlockedJarIsNeverDistributed() {
        port.verdict = "REFUSE";
        server.onCommand("admin", "update", Map.of("token", token("admin"), "mod", "Mod"), 0);
        assertFalse(server.updateRunning());
        assertNull(port.last("anna", "prepare"));
    }

    private String token(String c) {
        return port.last(c, "welcome").get("token");
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
            return List.of(new ServerCoordinator.ModInfo("Mod", "123", "a".repeat(64), NEW, "on"));
        }
        @Override public ServerCoordinator.Candidate candidate(String modId) {
            return modId.equals("Mod") ? new ServerCoordinator.Candidate(NEW, "123", verdict) : null;
        }
        @Override public String apply(String modId, String sha256, boolean adminConfirmed) {
            applied.add(modId + " " + sha256 + " admin=" + adminConfirmed);
            return null;
        }
    }
}
