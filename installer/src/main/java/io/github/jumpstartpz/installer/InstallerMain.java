package io.github.jumpstartpz.installer;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import io.github.bitecheckpz.launcher.Platform;

/**
 * Jumpstart's installer. Without arguments it opens a window with one button; on the command line:
 *
 * <pre>
 * JumpstartSetup --install   [--game-dir DIR] [--payload DIR] [--close-steam] [--no-auto-update] [--force]
 * JumpstartSetup --uninstall [--game-dir DIR] [--close-steam]
 * JumpstartSetup --update    [--game-dir DIR]       newest signed release now, if newer
 * JumpstartSetup --status    [--game-dir DIR]
 * JumpstartSetup --server DIR [--name NAME] [--zomboid-dir DIR] [--agent-args ARGS] [--headless] [--update] [--uninstall]
 * JumpstartSetup --wrap COMMAND...                  in Steam's launch options: update, then start the game
 * </pre>
 */
public final class InstallerMain {
    private InstallerMain() {
    }

    public static void main(String[] args) {
        // a newer installer from a signed release takes over (also for the installed app)
        if (Bootstrap.delegate(args)) {
            return;
        }
        Texts t = Texts.forSystem();
        if (args.length > 0 && args[0].equals("--wrap")) {
            System.exit(Wrapper.run(Arrays.asList(args).subList(1, args.length), t));
            return;
        }
        String action = null;
        Optional<Path> gameDir = Optional.empty();
        Optional<Path> payloadDir = Optional.empty();
        Optional<Path> serverDir = Optional.empty();
        Optional<String> name = Optional.empty();
        Optional<Path> zomboidDir = Optional.empty();
        Optional<String> agentArgs = Optional.empty();
        Optional<String> systemd = Optional.empty();
        Optional<String> restoreSnapshot = Optional.empty();
        boolean listSnapshots = false;
        boolean closeSteam = false;
        boolean autoUpdate = true;
        boolean force = false;
        boolean headless = false;
        boolean update = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--install", "--uninstall", "--status" -> action = args[i].substring(2);
                case "--update" -> update = true;
                case "--game-dir" -> gameDir = Optional.of(Path.of(value(args, ++i)));
                case "--payload" -> payloadDir = Optional.of(Path.of(value(args, ++i)));
                case "--server" -> serverDir = Optional.of(Path.of(value(args, ++i)));
                case "--name" -> name = Optional.of(value(args, ++i));
                case "--zomboid-dir" -> zomboidDir = Optional.of(Path.of(value(args, ++i)));
                case "--agent-args" -> agentArgs = Optional.of(value(args, ++i));
                case "--systemd" -> systemd = Optional.of(value(args, ++i));
                case "--snapshots" -> listSnapshots = true;
                case "--restore-snapshot" -> restoreSnapshot = Optional.of(value(args, ++i));
                case "--headless" -> headless = true;
                case "--close-steam" -> closeSteam = true;
                case "--no-auto-update" -> autoUpdate = false;
                case "--force" -> force = true;
                case "--help", "-h" -> {
                    usage();
                    return;
                }
                case "--version" -> {
                    System.out.println("JumpstartSetup " + Bootstrap.ownVersion().orElse("dev")
                            + Bootstrap.ownJar().map(j -> " (" + j + ")").orElse(""));
                    return;
                }
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    usage();
                    System.exit(2);
                }
            }
        }
        if (serverDir.isPresent()) {
            Path data = zomboidDir.orElse(Path.of(System.getProperty("user.home"), "Zomboid"));
            ServerSetup.Options o = new ServerSetup.Options(serverDir.get(), name, data, agentArgs, headless);
            if (listSnapshots || restoreSnapshot.isPresent()) {
                System.exit(snapshots(o, restoreSnapshot, t));
                return;
            }
            System.exit(server(o, "uninstall".equals(action), update, payloadDir, systemd, t));
            return;
        }
        if (action == null && update) {
            action = "update";
        }
        if (action == null && !GraphicsEnvironment.isHeadless()) {
            InstallerWindow.open(t, gameDir, payloadDir);
            return;
        }
        System.exit(run(action == null ? "status" : action, t, gameDir, payloadDir, closeSteam, autoUpdate, force));
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
                JumpstartSetup --install       installs Jumpstart (Jumpstart.jar, the ZombieBuddy fork inside)
                JumpstartSetup --uninstall     removes it
                JumpstartSetup --update        installs the newest signed release, if it is newer
                JumpstartSetup --status        shows what is installed
                  --game-dir DIR     Project Zomboid's folder, if it is not found in Steam's libraries
                  --payload DIR      install these files instead of the ones built in (Jumpstart.jar, mods/ZombieBuddy)
                  --close-steam      close Steam if needed to set the launch options
                  --no-auto-update   do not put Jumpstart's updater into the launch options
                  --force            install even if an older release than the installed one
                JumpstartSetup --server DIR    a dedicated server (run it with the server's own jre64/bin/java)
                  --name NAME        the server name (its .ini); default: the only .ini there is
                  --zomboid-dir DIR  the server's data folder (-cachedir=); default ~/Zomboid
                  --agent-args ARGS  the agent's (ZombieBuddy's) options; default hotswap=true
                  --headless         no console: policy=deny-new (new Java mods stay off)
                  --update           only if a newer signed release exists (else just repair); for ExecStartPre
                  --systemd UNIT     also a drop-in for this systemd unit that runs --update before every start (root)
                  --snapshots        list the save snapshots the server took before its changes (needs --name)
                  --restore-snapshot latest|FILE   put the save back as it was (server stopped; the current
                                     save folder is kept next to the snapshots)
                  --uninstall        remove it from the server
                Every install and update also writes StartServer64-Jumpstart.bat / start-server-jumpstart.sh next
                to the game's start script: start the server with it and a game update through SteamCMD
                does not switch Jumpstart off.""");
    }

    /** The bundled payload, or the newest signed release if that is newer; {@code --payload} wins. */
    static Payload choosePayload(Optional<Path> dir, Consumer<String> log, Texts t) throws IOException {
        if (dir.isPresent()) {
            return Payload.of(dir.get());
        }
        Optional<Payload> bundled = Payload.bundled();
        try {
            Updater updater = Updater.forJumpstart();
            Optional<Updater.Release> r = updater.latest();
            r.ifPresent(release -> Bootstrap.fetch(updater, release, log));
            if (r.isPresent() && Versions.isUpdate(r.get().version(), r.get().builtDay(), bundled.map(Payload::version))) {
                log.accept(t.get("update.newer", r.get().version()));
                return updater.payload(r.get());
            }
        } catch (IOException | RuntimeException e) {
            log.accept(t.get("update.unavailable", e.getMessage()));
        }
        return bundled.orElseThrow(() -> new IOException(t.get("error.noPayload")));
    }

    /** True if installing {@code chosen} over {@code installed} would go back to an older release. */
    static boolean isDowngrade(String chosen, Optional<String> installed) {
        return installed.isPresent() && Versions.isRelease(chosen) && Versions.isRelease(installed.get())
                && Versions.compareReleases(chosen, installed.get()) < 0;
    }

    private static int run(String action, Texts t, Optional<Path> gameDir, Optional<Path> payloadDir, boolean closeSteam,
            boolean autoUpdate, boolean force) {
        try {
            Optional<GameLocation> loc = GameLocation.find(gameDir);
            if (loc.isEmpty()) {
                System.err.println(t.get("error.noGame") + " (--game-dir)");
                return 1;
            }
            Optional<String> wrapper = autoUpdate ? Wrapper.prefix(loc.get()) : Optional.empty();
            Setup setup = new Setup(loc.get(), t, System.out::println, wrapper);
            System.out.println(t.get("game") + ": " + loc.get().javaDir());
            switch (action) {
                case "status" -> {
                    Setup.Status s = setup.status();
                    System.out.println(s);
                    return s.installed() ? 0 : 3;
                }
                case "update" -> {
                    if (!setup.isInstalled()) {
                        System.out.println(t.get("state.none"));
                        return 3;
                    }
                    Updater updater = Updater.forJumpstart();
                    Optional<Updater.Release> r = updater.latest();
                    Optional<String> installed = setup.installedVersion();
                    r.ifPresent(release -> Bootstrap.fetch(updater, release, System.out::println));
                    if (r.isPresent() && Versions.isUpdate(r.get().version(), r.get().builtDay(), installed)) {
                        System.out.println(t.get("update.running", r.get().version()));
                        setup.installFiles(updater.payload(r.get()), false);
                    } else {
                        System.out.println(t.get("update.none", installed.orElse("?")));
                    }
                    setup.repair();
                    return 0;
                }
                case "install" -> {
                    Payload payload = choosePayload(payloadDir, System.out::println, t);
                    if (!force && isDowngrade(payload.version(), setup.installedVersion())) {
                        System.err.println(t.get("error.downgrade", setup.installedVersion().orElse("?"), payload.version()));
                        return 5;
                    }
                    System.out.println(t.get("payload", payload.version()));
                    setup.installFiles(payload);
                    if (!launchOptions(setup, t, true, closeSteam)) {
                        return 4;
                    }
                    System.out.println(t.get("done.install"));
                    return 0;
                }
                default -> {
                    boolean launch = launchOptions(setup, t, false, closeSteam);
                    setup.uninstallFiles();
                    if (launch) {
                        Bootstrap.remove(); // nothing starts it any more
                    }
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
                Steam.open(setup.location().os(), "steam://open/main"); // and back
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

    /**
     * A dedicated server: install, or with {@code --update} only a newer signed release (and always
     * the repair after a game update). Never fails the server start over a missing network.
     */
    private static int server(ServerSetup.Options o, boolean uninstall, boolean update, Optional<Path> payloadDir,
                              Optional<String> systemd, Texts t) {
        Consumer<String> log = line -> System.out.println("[Jumpstart] " + line);
        ServerSetup s = new ServerSetup(Platform.detectOs(), o, t, log);
        runningServer(o).ifPresent(pid -> log.accept(t.get("server.running", pid)));
        try {
            if (uninstall) {
                s.uninstall();
                s.removeStartHooks(systemd, SYSTEMD_DIR);
                log.accept(t.get("done.uninstall"));
                return 0;
            }
            int result = installOrUpdate(s, o, update, payloadDir, log, t);
            if (result == 0) {
                startHooks(s, systemd, log, t);
            }
            return result;
        } catch (IOException | RuntimeException e) {
            log.accept(t.get("error", describe(e, t)));
            return 1;
        }
    }

    static final Path SYSTEMD_DIR = Path.of("/etc/systemd/system");

    /** --snapshots / --restore-snapshot: the server's save snapshots. */
    static int snapshots(ServerSetup.Options o, Optional<String> restore, Texts t) {
        Consumer<String> log = line -> System.out.println("[Jumpstart] " + line);
        if (o.name().isEmpty()) {
            log.accept(t.get("snapshots.name"));
            return 2;
        }
        ServerSnapshots snapshots = new ServerSnapshots(o.zomboidDir(), o.name().get());
        try {
            if (restore.isEmpty()) {
                List<Path> all = snapshots.list();
                if (all.isEmpty()) {
                    log.accept(t.get("snapshots.none"));
                }
                for (Path p : all) {
                    log.accept(p.getFileName() + "   " + (Files.size(p) / (1024 * 1024)) + " MB");
                }
                return 0;
            }
            Optional<Long> running = runningServer(o);
            if (running.isPresent()) {
                log.accept(t.get("snapshots.running", running.get()));
                return 1;
            }
            Optional<Path> snapshot = snapshots.find(restore.get());
            if (snapshot.isEmpty()) {
                log.accept(t.get("snapshots.notFound", restore.get()));
                return 1;
            }
            Path replaced = snapshots.restore(snapshot.get());
            log.accept(t.get("snapshots.restored", snapshot.get().getFileName(), replaced == null ? "-" : replaced.toString()));
            return 0;
        } catch (IOException | RuntimeException e) {
            log.accept(t.get("error", describe(e, t)));
            return 1;
        }
    }

    /**
     * After an install or update: Jumpstart's start script next to the game's, and with {@code --systemd}
     * the unit's drop-in (then systemctl daemon-reload). What fails here is only reported: the server
     * runs with Jumpstart all the same, until the next game update.
     */
    private static void startHooks(ServerSetup s, Optional<String> systemd, Consumer<String> log, Texts t) {
        Optional<Path> jar = Bootstrap.launchJar();
        if (jar.isEmpty()) {
            return; // run from a build folder: nothing a start script could name
        }
        try {
            s.writeStartWrapper(jar.get());
        } catch (IOException | RuntimeException e) {
            log.accept(t.get("server.wrapperFailed", describe(e, t)));
        }
        if (systemd.isPresent()) {
            try {
                s.writeSystemdDropIn(systemd.get(), jar.get(), SYSTEMD_DIR);
                Process p = new ProcessBuilder("systemctl", "daemon-reload").inheritIO().start();
                if (p.waitFor() != 0) {
                    log.accept(t.get("server.reload"));
                }
            } catch (IOException | RuntimeException e) {
                log.accept(t.get("server.dropInFailed", describe(e, t)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static int installOrUpdate(ServerSetup s, ServerSetup.Options o, boolean update, Optional<Path> payloadDir,
                                       Consumer<String> log, Texts t) {
        try {
            if (update && s.isInstalled()) {
                try {
                    Updater updater = Updater.forJumpstart();
                    Optional<Updater.Release> r = updater.latest();
                    Optional<String> installed = s.installedVersion();
                    if (r.isPresent() && Versions.isUpdate(r.get().version(), r.get().builtDay(), installed)) {
                        log.accept(t.get("update.running", r.get().version()));
                        s.install(updater.payload(r.get()));
                        selfUpdate(updater, r.get(), log, t);
                    } else {
                        log.accept(t.get("update.none", installed.orElse("?")));
                    }
                } catch (IOException | RuntimeException e) {
                    log.accept(t.get("update.unavailable", e.getMessage()));
                }
                if (s.repair()) {
                    log.accept(t.get("server.repaired"));
                }
                return 0;
            }
            Payload payload = choosePayload(payloadDir, log, t);
            log.accept(t.get("payload", payload.version()));
            s.install(payload);
            log.accept("First start: ZombieBuddy asks once per new Java mod jar" + (o.headless() ? "; with policy=deny-new they stay off." : " (answer y in the console)."));
            return 0;
        } catch (IOException | RuntimeException e) {
            log.accept(t.get("error", describe(e, t)));
            return 1;
        }
    }

    /** What went wrong, also when the exception's message is only a path. */
    static String describe(Exception e, Texts t) {
        if (e instanceof java.nio.file.AccessDeniedException a) {
            return t.get("error.access", a.getFile());
        }
        if (e instanceof java.nio.file.FileSystemException f) {
            String reason = f.getReason() == null ? e.getClass().getSimpleName() : f.getReason();
            return f.getFile() + ": " + reason;
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * A running server of this game folder or name: it keeps its mod list in memory and writes it
     * back into its .ini when it stops, over what the installer changed.
     */
    static Optional<Long> runningServer(ServerSetup.Options o) {
        String dir = o.serverDir().toAbsolutePath().normalize().toString();
        String name = o.name().map(n -> "-servername " + n).orElse(null);
        long self = ProcessHandle.current().pid();
        return ProcessHandle.allProcesses()
                .filter(p -> p.pid() != self)
                .filter(p -> {
                    String cmd = p.info().commandLine().orElse("");
                    boolean game = cmd.contains("ProjectZomboid") || cmd.contains("zombie.network.GameServer");
                    return game && (name != null ? cmd.contains(name) : cmd.contains(dir));
                })
                .map(ProcessHandle::pid)
                .findFirst();
    }

    /**
     * Replaces the JumpstartSetup.jar this runs from with the release's, for the next run (servers run it
     * from a plain jar). Not on Windows, where the running jar is locked, and not for installed apps.
     */
    private static void selfUpdate(Updater updater, Updater.Release r, Consumer<String> log, Texts t) {
        try {
            Path jar = Path.of(InstallerMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (System.getProperty("jpackage.app-path") != null || Platform.detectOs() == Platform.Os.WINDOWS
                    || !Files.isRegularFile(jar) || !jar.toString().endsWith(".jar")) {
                return;
            }
            Path fresh = updater.download(r, r.setup(), r.setupSha256(), Files.createTempDirectory("jumpstart-setup"));
            Path next = jar.resolveSibling(jar.getFileName() + ".new");
            Files.copy(fresh, next, StandardCopyOption.REPLACE_EXISTING);
            Files.move(next, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.accept(t.get("server.selfUpdate", r.version()));
        } catch (Exception e) {
            log.accept(t.get("update.unavailable", e.getMessage()));
        }
    }
}
