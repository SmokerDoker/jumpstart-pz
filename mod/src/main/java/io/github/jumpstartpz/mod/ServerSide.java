package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.launcher.UsageSharing;
import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.gate.BiteCheckGate;
import io.github.jumpstartpz.core.mp.ServerCoordinator;
import io.github.jumpstartpz.mod.net.Net;

/** Jumpstart on a server (dedicated or hosted): the game side of {@link ServerCoordinator}. */
final class ServerSide implements ServerCoordinator.Port<Object> {
    private final GameActions actions;
    private final ServerCoordinator<Object> coordinator;
    /** The hash each Java mod had when the server loaded it (or Jumpstart updated it). */
    private final Map<String, String> loadedSha = new HashMap<>();

    private ServerSide(GameActions actions) {
        this.actions = actions;
        this.coordinator = new ServerCoordinator<>(this, ServerCoordinator.DEFAULT_DEADLINE_MS);
        for (ModEntry mod : clientJars()) {
            loadedSha.put(mod.id(), hash(mod.jar()));
        }
    }

    /** The server's active mods with a Java part for the clients (client-only jars included). */
    private List<ModEntry> clientJars() {
        List<ModEntry> result = new ArrayList<>();
        try {
            List<?> ids = (List<?>) Class.forName("zombie.network.GameServer").getField("ServerMods").get(null);
            for (Object id : List.copyOf(ids)) {
                String modId = String.valueOf(id).replace("\\", "");
                if (modId.equals("ZombieBuddy") || modId.equals("Jumpstart")) {
                    continue;
                }
                Actions.FolderMod mod = actions.clientModById(modId);
                if (mod != null && mod.java() != null) {
                    result.add(mod.java());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot list the server's mods: " + e);
        }
        return result;
    }

    static void start(GameActions actions) {
        ServerSide side = new ServerSide(actions);
        Net.onServer((who, args) -> side.coordinator.onCommand(connectionOf(who[0]), (String) who[1], args, System.currentTimeMillis()),
                () -> side.coordinator.tick(System.currentTimeMillis()));
        System.out.println("[Jumpstart] multiplayer: server side ready; Java mods for the clients: " + side.loadedSha.keySet());
    }

    private static Object connectionOf(Object player) {
        try {
            return Class.forName("zombie.network.GameServer").getMethod("getConnectionFromPlayer",
                    Class.forName("zombie.characters.IsoPlayer")).invoke(null, player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void send(Object connection, String command, Map<String, String> args) {
        try {
            Net.sendToClient(connection, command, args);
        } catch (ReflectiveOperationException e) {
            System.out.println("[Jumpstart] cannot send " + command + ": " + e);
        }
    }

    @Override
    public void kick(Object connection, String reason) {
        try {
            connection.getClass().getMethod("forceDisconnect", String.class).invoke(connection, reason);
        } catch (ReflectiveOperationException e) {
            System.out.println("[Jumpstart] cannot kick: " + e);
        }
    }

    @Override
    public boolean isAdmin(Object connection) {
        try {
            Object[] players = (Object[]) connection.getClass().getField("players").get(connection);
            for (Object p : players) {
                if (p != null && (Boolean) p.getClass().getMethod("isAccessLevel", String.class).invoke(p, "admin")) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // not an admin then
        }
        return false;
    }

    @Override
    public List<Object> connections() {
        List<Object> result = new ArrayList<>();
        try {
            Object engine = Class.forName("zombie.network.GameServer").getField("udpEngine").get(null);
            List<?> all = (List<?>) engine.getClass().getField("connections").get(engine);
            Method full = Class.forName("zombie.core.raknet.UdpConnection").getMethod("isFullyConnected");
            for (Object c : List.copyOf(all)) {
                if ((Boolean) full.invoke(c)) {
                    result.add(c);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot list connections: " + e);
        }
        return result;
    }

    @Override
    public String name(Object connection) {
        try {
            return String.valueOf(connection.getClass().getMethod("getUserName").invoke(connection));
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    @Override
    public List<ServerCoordinator.ModInfo> manifest() {
        List<ServerCoordinator.ModInfo> mods = new ArrayList<>();
        for (ModEntry mod : clientJars()) {
            String state = actions.stateOf(mod.id());
            mods.add(new ServerCoordinator.ModInfo(mod.id(), UsageSharing.Jar.workshopIdOf(mod.jar()),
                    loadedSha.getOrDefault(mod.id(), ""), hash(mod.jar()), state.equals("not loaded") ? "client only" : state));
        }
        return mods;
    }

    @Override
    public ServerCoordinator.Candidate candidate(String modId) {
        ModEntry mod = find(modId);
        if (mod == null) {
            return null;
        }
        try {
            BiteCheckGate.Check check = actions.check(mod.jar());
            return new ServerCoordinator.Candidate(check.sha256(), UsageSharing.Jar.workshopIdOf(mod.jar()), check.verdict().name());
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String apply(ServerCoordinator.Action action, String modId, String sha256, boolean adminConfirmed) {
        ModEntry mod = find(modId);
        if (mod == null) {
            return modId + " is gone from the server";
        }
        String state = actions.stateOf(modId);
        if (state.equals("not loaded")) {
            // a client-only mod: the server does not run it; the clients have changed it
            if (action == ServerCoordinator.Action.UPDATE) {
                loadedSha.put(modId, sha256);
            }
            return null;
        }
        switch (action) {
            case OFF -> {
                String r = state.equals("off") ? "OK" : actions.off(modId);
                return r.startsWith("OK") ? null : r;
            }
            case ON -> {
                String r = state.equals("on") ? "OK" : actions.on(modId);
                return r.startsWith("OK") ? null : r;
            }
            default -> {
                BiteCheckGate.Consent consent = adminConfirmed ? BiteCheckGate.Consent.CONFIRMED_TWICE : BiteCheckGate.Consent.NONE;
                LiveInstaller.ConsentSource answer = check -> check.sha256().equals(sha256) ? consent : null;
                LiveInstaller.Outcome outcome = actions.update(mod, answer);
                if (outcome.kind() != LiveInstaller.Kind.DONE) {
                    return outcome.kind() + " " + outcome.message();
                }
                loadedSha.put(modId, sha256);
                return null;
            }
        }
    }

    private ModEntry find(String modId) {
        for (ModEntry mod : clientJars()) {
            if (mod.id().equals(modId)) {
                return mod;
            }
        }
        return null;
    }

    private static String hash(Path jar) {
        try {
            return Hashing.sha256Hex(jar);
        } catch (IOException e) {
            return "";
        }
    }
}
