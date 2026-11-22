package io.github.jumpstartpz.core.mp;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The server's side of Jumpstart in multiplayer, without any game code (see the mod's ServerSide).
 *
 * <ul>
 *   <li><b>hello</b> (client): the server answers <b>welcome</b> with a per-connection token, whether
 *       the player is an admin, and the server's Java mods (id, Workshop item, loaded hash, the hash
 *       of the jar on disk now, state). The token travels only through Jumpstart's Java channel,
 *       which Lua neither sees nor can fake, so every later command must carry it.</li>
 *   <li><b>update</b> (admin): every connected client gets <b>prepare</b> with the mod, the hash the
 *       server has on disk and a deadline. The game pauses; each client gets exactly that jar
 *       (Steam Workshop), checks it with BiteCheck (the player answers its questions) and loads it,
 *       then sends <b>ready</b>, or <b>failed</b> with a reason.</li>
 *   <li>When everybody is ready, or the deadline passed, clients that are not ready are kicked, the
 *       server loads the same jar itself and everybody gets <b>done</b>.</li>
 * </ul>
 *
 * Not thread-safe: the game calls it from its server thread only.
 *
 * @param <C> the game's connection type
 */
public final class ServerCoordinator<C> {
    public static final String MODULE = "Jumpstart";
    public static final long DEFAULT_DEADLINE_MS = 180_000;

    /** A Java mod of the server. {@code diskSha256} differs from {@code sha256} when a new version is waiting. */
    public record ModInfo(String id, String workshopId, String sha256, String diskSha256, String state) {
    }

    /** The jar on the server's disk for a mod: what clients must load. */
    public record Candidate(String sha256, String workshopId, String verdict) {
    }

    /** What the coordinator needs from the game. */
    public interface Port<C> {
        void send(C connection, String command, Map<String, String> args);

        void kick(C connection, String reason);

        boolean isAdmin(C connection);

        /** Fully connected clients. */
        List<C> connections();

        String name(C connection);

        List<ModInfo> manifest();

        /** The jar on disk for a mod id, or null. */
        Candidate candidate(String modId);

        /**
         * Loads the candidate on the server. {@code adminConfirmed}: the admin who started the update
         * loaded the same hash on their own computer, after BiteCheck's questions. Null when done.
         */
        String apply(String modId, String sha256, boolean adminConfirmed);
    }

    private record Update(String mod, String sha256, String workshopId, Object admin, long deadline,
                          Set<Object> expected, Set<Object> ready, Map<Object, String> failed) {
    }

    private final Port<C> port;
    private final long deadlineMs;
    private final Map<C, String> tokens = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private Update pending;

    public ServerCoordinator(Port<C> port, long deadlineMs) {
        this.port = Objects.requireNonNull(port);
        this.deadlineMs = deadlineMs;
    }

    public boolean updateRunning() {
        return pending != null;
    }

    /** A Jumpstart command from a client (already filtered to this module by the channel). */
    public void onCommand(C c, String command, Map<String, String> args, long now) {
        if (command.equals("hello")) {
            welcome(c);
            return;
        }
        String token = tokens.get(c);
        if (token == null || !token.equals(args.get("token"))) {
            return; // not from Jumpstart's Java side of this connection (for example a Lua mod)
        }
        switch (command) {
            case "update" -> startUpdate(c, args.getOrDefault("mod", ""), now);
            case "ready" -> {
                if (pending != null && pending.mod().equals(args.get("mod")) && pending.sha256().equals(args.get("sha"))) {
                    pending.ready().add(c);
                    pending.failed().remove(c);
                }
            }
            case "failed" -> {
                if (pending != null && pending.mod().equals(args.get("mod"))) {
                    pending.failed().put(c, args.getOrDefault("reason", "failed"));
                }
            }
            default -> {
                // newer commands are ignored
            }
        }
    }

    private void welcome(C c) {
        String token = tokens.computeIfAbsent(c, k -> {
            byte[] b = new byte[16];
            random.nextBytes(b);
            return HexFormat.of().formatHex(b);
        });
        StringBuilder mods = new StringBuilder();
        for (ModInfo m : port.manifest()) {
            mods.append(m.id()).append('\t').append(nz(m.workshopId())).append('\t').append(nz(m.sha256())).append('\t')
                    .append(nz(m.diskSha256())).append('\t').append(nz(m.state())).append('\n');
        }
        Map<String, String> args = new LinkedHashMap<>();
        args.put("token", token);
        args.put("admin", port.isAdmin(c) ? "1" : "0");
        args.put("mods", mods.toString());
        args.put("updating", pending == null ? "" : pending.mod());
        port.send(c, "welcome", args);
    }

    private void startUpdate(C admin, String mod, long now) {
        if (!port.isAdmin(admin)) {
            note(admin, "only admins can start an update");
            return;
        }
        if (pending != null) {
            note(admin, "an update of " + pending.mod() + " is running");
            return;
        }
        Candidate candidate = port.candidate(mod);
        if (candidate == null) {
            note(admin, mod + " has no Java jar on the server");
            return;
        }
        if ("REFUSE".equals(candidate.verdict())) {
            note(admin, "BiteCheck blocks " + mod + " on the server");
            return;
        }
        Set<Object> expected = new LinkedHashSet<>(port.connections());
        pending = new Update(mod, candidate.sha256(), nz(candidate.workshopId()), admin, now + deadlineMs,
                expected, new LinkedHashSet<>(), new LinkedHashMap<>());
        Map<String, String> args = new LinkedHashMap<>();
        args.put("mod", mod);
        args.put("sha", candidate.sha256());
        args.put("workshop", nz(candidate.workshopId()));
        args.put("seconds", String.valueOf(deadlineMs / 1000));
        args.put("by", port.name(admin));
        for (Object c : expected) {
            port.send(cast(c), "prepare", args);
        }
    }

    /** Every server tick: finishes an update when everybody answered or the deadline passed. */
    public void tick(long now) {
        if (pending == null) {
            return;
        }
        List<C> connected = port.connections();
        pending.expected().retainAll(connected); // who left does not hold the others up
        tokens.keySet().retainAll(connected);
        boolean allAnswered = true;
        for (Object c : pending.expected()) {
            if (!pending.ready().contains(c) && !pending.failed().containsKey(c)) {
                allAnswered = false;
            }
        }
        if (allAnswered || now >= pending.deadline()) {
            finish();
        }
    }

    private void finish() {
        Update u = pending;
        pending = null;
        List<String> kicked = new ArrayList<>();
        for (Object o : u.expected()) {
            if (!u.ready().contains(o)) {
                C c = cast(o);
                String why = u.failed().containsKey(o) ? u.failed().get(o) : "not ready in time";
                kicked.add(port.name(c) + " (" + why + ")");
                port.kick(c, "Jumpstart: " + u.mod() + " update: " + why);
            }
        }
        String problem = port.apply(u.mod(), u.sha256(), u.ready().contains(u.admin()));
        Map<String, String> args = new LinkedHashMap<>();
        args.put("mod", u.mod());
        args.put("ok", problem == null ? "1" : "0");
        args.put("message", problem != null ? "server: " + problem
                : kicked.isEmpty() ? "" : "kicked: " + String.join(", ", kicked));
        for (Object o : u.ready()) {
            port.send(cast(o), "done", args);
        }
    }

    private void note(C c, String text) {
        port.send(c, "note", Map.of("text", text));
    }

    @SuppressWarnings("unchecked")
    private C cast(Object o) {
        return (C) o;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
