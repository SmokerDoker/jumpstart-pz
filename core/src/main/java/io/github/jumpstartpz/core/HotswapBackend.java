package io.github.jumpstartpz.core;

import java.nio.file.Path;

/**
 * What Jumpstart needs from the mod loader: load a mod jar live, or replace the loaded version of a
 * mod. Every method returns null on success, otherwise why nothing was changed.
 */
public interface HotswapBackend {

    /** Null when the loader can switch mods live, otherwise why not. */
    String unavailableReason();

    String load(Path jar, String javaPackage, String modId);

    String update(Path jar, String javaPackage, String modId);

    /** The folder with ZombieBuddy's mod_approvals.json. */
    Path approvalsDir();
}
