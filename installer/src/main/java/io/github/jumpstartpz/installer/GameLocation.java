package io.github.jumpstartpz.installer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.bitecheckpz.launcher.Config;
import io.github.bitecheckpz.launcher.Platform;

/**
 * Where Project Zomboid and the player's files are: the game's Java folder (holds projectzomboid.jar,
 * gets Jumpstart.jar), Zomboid/ (mods, mod list), Steam's folders (launch options, Workshop).
 * Found like BiteCheck finds them, in every Steam library; BiteCheck's config.toml overrides apply.
 *
 * @param os           the system
 * @param javaDir      Windows: ...\ProjectZomboid; macOS: .../Project Zomboid.app/Contents/Java;
 *                     Linux: .../ProjectZomboid/projectzomboid
 * @param zomboidDir   ~/Zomboid
 * @param steamRoots   Steam's installation folders (userdata/ holds the launch options)
 * @param workshopDirs steamapps/workshop/content/108600 of every library
 */
public record GameLocation(Platform.Os os, Path javaDir, Path zomboidDir, List<Path> steamRoots, List<Path> workshopDirs) {

    /** ZombieBuddy's Workshop item; with the fork's mod folder in Zomboid/mods it is not needed. */
    public static final String ZOMBIEBUDDY_WORKSHOP_ID = "3619862853";

    /** Jumpstart: the ZombieBuddy fork with Jumpstart built in, the game's Java agent. */
    public Path jumpstartJar() {
        return javaDir.resolve("Jumpstart.jar");
    }

    /**
     * Windows: the small loader that starts Jumpstart.jar ({@code -agentlib:jsNative}). The game's
     * ProjectZomboid64.exe cannot start {@code -javaagent} itself, as instrument.dll's DLLs are not on
     * the load path; the loader (ZombieBuddy's zbNative, built for Jumpstart.jar) puts them there.
     */
    public Path nativeLoader() {
        return javaDir.resolve("jsNative.dll");
    }

    /** ZombieBuddy's own jar (the player's, or the fork from Jumpstart before 0.2); moved aside while Jumpstart is installed. */
    public Path zombieBuddyJar() {
        return javaDir.resolve("ZombieBuddy.jar");
    }

    /** Which entries of ProjectZomboid64.json Jumpstart replaced, to put them back on uninstall (Windows). */
    public Path replacedAgents() {
        return javaDir.resolve("jumpstart-replaced-agent.txt");
    }

    /** ProjectZomboid64.json: the game's JVM options on Windows (no Steam launch options needed). */
    public Path launchJson() {
        return javaDir.resolve("ProjectZomboid64.json");
    }

    public Path modsDir() {
        return zomboidDir.resolve("mods");
    }

    public Path modList() {
        return modsDir().resolve("default.txt");
    }

    /** Where a ZombieBuddy mod folder of the player's own is kept while the fork's is installed. */
    public Path backupDir() {
        return zomboidDir.resolve("jumpstart-backup");
    }

    /**
     * The Java that comes with the game (Java 25: window, HTTP and Ed25519 included). Steam's launch
     * options start Jumpstart's updater with it, so they never depend on the installed app.
     * Windows: jre64\bin\javaw.exe (no console window); Linux: jre64/bin/java; macOS: somewhere in
     * the game's app bundle.
     */
    public Optional<Path> gameJava() {
        Path direct = javaDir.resolve(os == Platform.Os.WINDOWS ? "jre64/bin/javaw.exe" : "jre64/bin/java");
        if (Files.isRegularFile(direct)) {
            return Optional.of(direct);
        }
        if (os != Platform.Os.MACOS || javaDir.getParent() == null) {
            return Optional.empty();
        }
        try (var files = Files.walk(javaDir.getParent(), 8)) { // Project Zomboid.app/Contents
            return files.filter(p -> p.endsWith(Path.of("bin", "java")) && Files.isRegularFile(p) && Files.isExecutable(p))
                    .sorted().findFirst();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** True if ZombieBuddy is subscribed in the Steam Workshop. */
    public boolean zombieBuddyFromWorkshop() {
        return workshopDirs.stream().anyMatch(d -> Files.isDirectory(d.resolve(ZOMBIEBUDDY_WORKSHOP_ID)));
    }

    /** Steam's localconfig.vdf of every Steam account on this computer. */
    public List<Path> localConfigs() {
        List<Path> result = new ArrayList<>();
        for (Path root : steamRoots) {
            Path userdata = root.resolve("userdata");
            if (!Files.isDirectory(userdata)) {
                continue;
            }
            try (var users = Files.list(userdata)) {
                for (Path user : users.sorted().toList()) {
                    Path vdf = user.resolve("config/localconfig.vdf");
                    if (Files.isRegularFile(vdf)) {
                        result.add(vdf);
                    }
                }
            } catch (IOException e) {
                // an unreadable Steam folder: no launch options from it
            }
        }
        return result;
    }

    /**
     * Finds the game in every Steam library, or in {@code override} (the Steam game folder or the
     * Java folder itself).
     */
    public static Optional<GameLocation> find(Optional<Path> override) {
        Config config;
        try {
            config = Config.load(Path.of(System.getProperty("user.home"), ".bitecheck", "config.toml"));
        } catch (IOException | RuntimeException e) {
            config = Config.empty();
        }
        Platform platform = new Platform(config);
        List<Path> candidates = new ArrayList<>();
        override.ifPresent(candidates::add);
        if (override.isEmpty()) {
            config.path("game_dir").ifPresent(candidates::add);
            for (Path lib : platform.steamLibraries()) {
                candidates.add(lib.resolve("steamapps/common/ProjectZomboid"));
            }
        }
        return javaDir(platform.os(), candidates).map(dir -> new GameLocation(platform.os(), dir,
                platform.zomboidDir(), platform.steamRoots(), platform.workshopContentDirs()));
    }

    /** The first candidate that holds the game, as its Java folder. */
    static Optional<Path> javaDir(Platform.Os os, List<Path> candidates) {
        for (Path candidate : candidates) {
            for (Path dir : List.of(candidate, candidate.resolve("Project Zomboid.app/Contents/Java"),
                    candidate.resolve("projectzomboid"))) {
                boolean game = Files.isRegularFile(dir.resolve("projectzomboid.jar"))
                        && (os != Platform.Os.WINDOWS || Files.isRegularFile(dir.resolve("ProjectZomboid64.json")));
                if (game) {
                    return Optional.of(dir.toAbsolutePath().normalize());
                }
            }
        }
        return Optional.empty();
    }
}
