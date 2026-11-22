package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.gate.BiteCheckGate;

/** What the secure screen can do. {@link GameActions} is the game; tests use a fake. */
interface Actions {
    /** Every Java mod in the mod folders, except ZombieBuddy and Jumpstart. */
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
}
