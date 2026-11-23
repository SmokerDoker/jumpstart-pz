package io.github.jumpstartpz.installer;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Jumpstart's installer. Without arguments it opens a window with one button; on the command line:
 *
 * <pre>
 * JumpstartSetup --install   [--game-dir DIR] [--payload DIR] [--close-steam]
 * JumpstartSetup --uninstall [--game-dir DIR] [--close-steam]
 * JumpstartSetup --status    [--game-dir DIR]
 * </pre>
 *
 * {@code --close-steam} lets it close Steam on macOS and Linux to set the launch options.
 */
public final class InstallerMain {
    private InstallerMain() {
    }

    public static void main(String[] args) {
        String action = null;
        Optional<Path> gameDir = Optional.empty();
        Optional<Path> payloadDir = Optional.empty();
        boolean closeSteam = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--install", "--uninstall", "--status" -> action = args[i].substring(2);
                case "--game-dir" -> gameDir = Optional.of(Path.of(value(args, ++i)));
                case "--payload" -> payloadDir = Optional.of(Path.of(value(args, ++i)));
                case "--close-steam" -> closeSteam = true;
                case "--help", "-h" -> {
                    usage();
                    return;
                }
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    usage();
                    System.exit(2);
                }
            }
        }
        Texts t = Texts.forSystem();
        if (action == null && !GraphicsEnvironment.isHeadless()) {
            InstallerWindow.open(t, gameDir, payloadDir);
            return;
        }
        System.exit(run(action == null ? "status" : action, t, gameDir, payloadDir, closeSteam));
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            usage();
            System.exit(2);
        }
        return args[i];
    }

    private static void usage() {
        System.out.println("""
                JumpstartSetup                 opens the installer window
                JumpstartSetup --install       installs Jumpstart and the ZombieBuddy fork
                JumpstartSetup --uninstall     removes them
                JumpstartSetup --status        shows what is installed
                  --game-dir DIR   Project Zomboid's folder, if it is not found in Steam's libraries
                  --payload DIR    install these files instead of the ones built in (mods/ZombieBuddy, mods/Jumpstart)
                  --close-steam    macOS/Linux: close Steam if needed to set the launch options""");
    }

    static Optional<Payload> payload(Optional<Path> dir) throws IOException {
        return dir.isPresent() ? Optional.of(Payload.of(dir.get())) : Payload.bundled();
    }

    private static int run(String action, Texts t, Optional<Path> gameDir, Optional<Path> payloadDir, boolean closeSteam) {
        try {
            Optional<GameLocation> loc = GameLocation.find(gameDir);
            if (loc.isEmpty()) {
                System.err.println(t.get("error.noGame") + " (--game-dir)");
                return 1;
            }
            Setup setup = new Setup(loc.get(), t, System.out::println);
            System.out.println(t.get("game") + ": " + loc.get().javaDir());
            switch (action) {
                case "status" -> {
                    Setup.Status s = setup.status();
                    System.out.println(s);
                    return s.installed() ? 0 : 3;
                }
                case "install" -> {
                    Optional<Payload> payload = payload(payloadDir);
                    if (payload.isEmpty()) {
                        System.err.println(t.get("error.noPayload"));
                        return 1;
                    }
                    System.out.println(t.get("payload", payload.get().version()));
                    setup.installFiles(payload.get());
                    if (!launchOptions(setup, t, true, closeSteam)) {
                        return 4;
                    }
                    System.out.println(t.get("done.install"));
                    return 0;
                }
                default -> {
                    boolean launch = launchOptions(setup, t, false, closeSteam);
                    setup.uninstallFiles();
                    System.out.println(t.get("done.uninstall"));
                    return launch ? 0 : 4;
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println(t.get("error", e.getMessage()));
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    /** True when the launch options are right; otherwise prints what to set by hand. */
    private static boolean launchOptions(Setup setup, Texts t, boolean install, boolean closeSteam)
            throws IOException, InterruptedException {
        Setup.LaunchResult r = setup.launchOptions(install);
        if (r == Setup.LaunchResult.STEAM_RUNNING && closeSteam) {
            System.out.println(t.get("steam.closing"));
            if (Steam.close(setup.location().os(), Duration.ofSeconds(60))) {
                r = setup.launchOptions(install);
            } else {
                System.out.println(t.get("steam.notClosed"));
            }
        }
        if (r == Setup.LaunchResult.DONE || r == Setup.LaunchResult.NOT_NEEDED) {
            return true;
        }
        if (r == Setup.LaunchResult.NO_STEAM_CONFIG) {
            System.out.println(t.get("noSteamConfig"));
        }
        System.out.println(t.get(install ? "manual" : "manual.uninstall", setup.suggestedLaunchOptions(install)));
        if (r == Setup.LaunchResult.STEAM_RUNNING) {
            System.out.println("(--close-steam)");
        }
        return false;
    }
}
