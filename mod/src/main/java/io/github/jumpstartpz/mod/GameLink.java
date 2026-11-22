package io.github.jumpstartpz.mod;

import java.util.Map;

import io.github.jumpstartpz.mod.net.Net;

/** {@link ServerLink} in the game: zombie.network.GameClient, by reflection. */
final class GameLink implements ServerLink {
    @Override
    public boolean connected() {
        try {
            Class<?> client = Class.forName("zombie.network.GameClient");
            return client.getField("client").getBoolean(null) && client.getField("ingame").getBoolean(null)
                    && Class.forName("zombie.characters.IsoPlayer").getMethod("getInstance").invoke(null) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void send(String command, Map<String, String> args) {
        try {
            Net.sendToServer(command, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot send " + command + " to the server: " + e);
        }
    }

    @Override
    public void pause(boolean paused) {
        try {
            Class.forName("zombie.network.GameClient").getMethod("setIsClientPaused", boolean.class).invoke(null, paused);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot pause: " + e);
        }
    }
}
