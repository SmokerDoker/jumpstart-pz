package io.github.jumpstartpz.mod;

import java.util.Map;

/** The client's connection to a multiplayer server, as Jumpstart sees it. {@link GameLink} is the game. */
interface ServerLink {
    /** In a multiplayer game right now. */
    boolean connected();

    /** Sends a Jumpstart command to the server (Java channel, see net.Net). */
    void send(String command, Map<String, String> args);

    /** Pauses the local game like the server does while it saves. */
    void pause(boolean paused);
}
