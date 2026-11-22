package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.github.bitecheckpz.launcher.UsageSharing;
import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.gate.BiteCheckGate;
import io.github.jumpstartpz.core.market.LibraryClient;

/** What the secure screen can do. {@link GameActions} is the game; tests use a fake. */
interface Actions {
    /** Every Java mod in the mod folders, Jumpstart itself first; not ZombieBuddy (the Java agent). */
    List<ModEntry> javaMods();

    /** "on", "on, until restart", "off" or "not loaded". */
    String stateOf(String modId);

    BiteCheckGate.Check check(Path jar) throws IOException;

    LiveInstaller.Outcome install(ModEntry mod, LiveInstaller.ConsentSource player);

    LiveInstaller.Outcome update(ModEntry mod, LiveInstaller.ConsentSource player);

    String off(String modId);

    String on(String modId);

    /** Fetches BiteCheck's index again; returns what happened. */
    String refreshIndex();

    // --- marketplace ---

    /** Searches a library of the Jumpstart cloud; throws when there is none or it cannot be reached. */
    LibraryClient.Page search(LibraryClient.Kind kind, String query, String cursor) throws IOException;

    Workshop workshop();

    /** The mods in a downloaded Workshop item folder, with their Java part (null for Lua-only mods). */
    List<FolderMod> modsInFolder(String itemFolder);

    /** Marks a mod active: in the running save's mod list, or in the main menu's list. */
    String activate(String modId);

    /** Loads a just enabled mod's Lua part into the running single player game ({@link LuaLive}). */
    LuaLive.Result loadLua(String modId);

    record FolderMod(String id, String name, ModEntry java) {
    }

    // --- voluntary sharing (BiteCheck's UsageSharing) ---

    boolean sharingEnabled();

    void setSharing(boolean enabled) throws IOException;

    /** The unverified jars a report would list now (hashes the mods; call off the main thread). */
    List<UsageSharing.Jar> unverifiedJars();

    /** Sends today's report if sharing is on and it is due; returns what happened. */
    String shareIfDue(List<UsageSharing.Jar> jars);
}
