package io.github.jumpstartpz.installer;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import io.github.bitecheckpz.launcher.Platform;

/**
 * Jumpstart in the Steam launch options, before the game:
 *
 * <pre>
 * "…/Jumpstart" --wrap %command%                        (Windows)
 * "…/Jumpstart" --wrap %command% -javaagent:ZombieBuddy.jar=hotswap=true --   (macOS, Linux)
 * "…/Jumpstart" --wrap "…/BiteCheck" --wrap %command% …  (with BiteCheck: Jumpstart first, then BiteCheck checks)
 * </pre>
 *
 * Installs a newer signed release (nothing is locked before the game starts), puts hotswap=true back
 * after a game update, then runs the rest of the command line unchanged and waits for it. Whatever
 * goes wrong, the game still starts. A log is kept in Zomboid/jumpstart-update.log.
 */
final class Wrapper {
    private Wrapper() {
    }

    /** True if this installer can act as wrapper: it runs from a jar (app or JumpstartSetup.jar), not a build folder. */
    static boolean available() {
        return Bootstrap.ownJar().isPresent() || Files.isRegularFile(Bootstrap.bootJar());
    }

    /**
     * The launch option prefix: the game's own Java runs ~/.jumpstart/JumpstartSetup.jar (copied there
     * now). Nothing in it belongs to the installed app, so removing the app keeps the game starting.
     * Without the game's Java (not found), the Java this runs on.
     */
    static Optional<String> prefix(GameLocation loc) {
        if (!Bootstrap.placeBootJar()) {
            return Optional.empty();
        }
        Path java = loc.gameJava().orElseGet(() -> Path.of(System.getProperty("java.home"), "bin",
                loc.os() == Platform.Os.WINDOWS ? "javaw.exe" : "java"));
        return Optional.of(quote(java.toString()) + " -jar " + quote(Bootstrap.bootJar().toString()) + " --wrap");
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    /** Updates if there is something to update, then runs {@code command} (the game) and returns its exit code. */
    static int run(List<String> command, Texts t) {
        Path logFile = Path.of(System.getProperty("user.home"), "Zomboid", "jumpstart-update.log");
        Consumer<String> log = line -> append(logFile, line);
        log.accept("start: " + String.join(" ", command));
        try {
            update(t, log);
        } catch (Throwable e) {
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            log.accept("update failed, starting the game anyway: " + trace);
        }
        if (command.isEmpty()) {
            log.accept("nothing to start after --wrap");
            return 2;
        }
        List<String> run = runnable(command);
        if (!run.equals(command)) {
            log.accept("macOS app bundle: starting " + run.get(0));
        }
        try {
            return startAndWait(run, log);
        } catch (IOException e) {
            log.accept("cannot start " + run.get(0) + ": " + e);
            if (isAppBundle(command.get(0))) {
                // last resort: let macOS open the bundle, and wait for it
                List<String> open = new java.util.ArrayList<>(List.of("open", "-W", "-a", command.get(0), "--args"));
                open.addAll(command.subList(1, command.size()));
                try {
                    log.accept("trying: open -W -a ...");
                    return startAndWait(open, log);
                } catch (IOException e2) {
                    log.accept("cannot open " + command.get(0) + ": " + e2);
                } catch (InterruptedException e2) {
                    Thread.currentThread().interrupt();
                }
            }
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    private static int startAndWait(List<String> command, Consumer<String> log) throws IOException, InterruptedException {
        Process game = new ProcessBuilder(command).inheritIO().start();
        int code = game.waitFor();
        log.accept("game exited: " + code);
        return code;
    }

    static boolean isAppBundle(String path) {
        return path.endsWith(".app") && Files.isDirectory(Path.of(path));
    }

    /**
     * The command line Steam handed over, made runnable. On macOS Steam's %command% is the game's
     * .app bundle, a folder that cannot be run as a program (error 13); its program is
     * Contents/MacOS/&lt;CFBundleExecutable from Info.plist&gt;, started with the same arguments, as Steam
     * starts it without a wrapper.
     */
    static List<String> runnable(List<String> command) {
        if (command.isEmpty() || !isAppBundle(command.get(0))) {
            return command;
        }
        Optional<Path> exe = bundleExecutable(Path.of(command.get(0)));
        if (exe.isEmpty()) {
            return command;
        }
        List<String> out = new java.util.ArrayList<>(command);
        out.set(0, exe.get().toString());
        return out;
    }

    static Optional<Path> bundleExecutable(Path app) {
        Path contents = app.resolve("Contents");
        Path macos = contents.resolve("MacOS");
        String name = null;
        try {
            // an XML Info.plist (Steam games ship it so); a binary one is read by plutil below
            String plist = Files.readString(contents.resolve("Info.plist"), StandardCharsets.ISO_8859_1);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<key>CFBundleExecutable</key>\\s*<string>([^<]+)</string>").matcher(plist);
            if (m.find()) {
                name = m.group(1).strip();
            }
        } catch (IOException e) {
            // no Info.plist: look into Contents/MacOS below
        }
        if (name == null && Files.isRegularFile(contents.resolve("Info.plist"))) {
            try {
                Process p = new ProcessBuilder("plutil", "-extract", "CFBundleExecutable", "raw", "-o", "-",
                        contents.resolve("Info.plist").toString()).redirectErrorStream(true).start();
                String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                if (p.waitFor() == 0 && !s.isEmpty() && !s.contains("/")) {
                    name = s;
                }
            } catch (IOException e) {
                // no plutil (not macOS)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (name != null && !name.contains("/") && Files.isRegularFile(macos.resolve(name))) {
            return Optional.of(macos.resolve(name));
        }
        // the only program in Contents/MacOS
        try (java.util.stream.Stream<Path> files = Files.list(macos)) {
            List<Path> programs = files.filter(Files::isRegularFile).toList();
            return programs.size() == 1 ? Optional.of(programs.get(0)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void update(Texts t, Consumer<String> log) throws IOException {
        Optional<GameLocation> loc = GameLocation.find(Optional.empty());
        if (loc.isEmpty()) {
            log.accept("game not found; nothing to update");
            return;
        }
        Setup setup = new Setup(loc.get(), t, log, Optional.empty());
        if (!setup.isInstalled()) {
            log.accept("Jumpstart is not installed; nothing to update");
            return;
        }
        Optional<String> installed = setup.installedVersion();
        Updater updater = Updater.forJumpstart();
        Optional<Updater.Release> release = updater.latest();
        if (release.isPresent() && Versions.isUpdate(release.get().version(), release.get().builtDay(), installed)) {
            Updater.Release r = release.get();
            log.accept("update " + installed.orElse("?") + " -> " + r.version());
            Runnable close = progress(t.get("update.running", r.version()));
            try {
                setup.installFiles(updater.payload(r), false);
            } finally {
                close.run();
            }
            log.accept("updated to " + r.version());
        } else {
            log.accept("up to date (" + installed.orElse("?") + (release.isPresent() ? ", newest " + release.get().version() : ", no signed release yet") + ")");
        }
        // the installer itself: kept for the next start (this one may be locked while the game runs)
        release.ifPresent(r -> Bootstrap.fetch(updater, r, log));
        if (setup.repair()) {
            log.accept("hotswap=true put back into ProjectZomboid64.json (a game update had removed it)");
        }
    }

    /** A small window while an update downloads; returns what closes it. Nothing without a screen. */
    private static Runnable progress(String text) {
        if (GraphicsEnvironment.isHeadless()) {
            return () -> { };
        }
        JFrame[] frame = new JFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame f = new JFrame("Jumpstart");
                JPanel panel = new JPanel(new BorderLayout(0, 8));
                panel.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
                panel.add(new JLabel(text), BorderLayout.NORTH);
                JProgressBar bar = new JProgressBar();
                bar.setIndeterminate(true);
                panel.add(bar, BorderLayout.CENTER);
                f.setContentPane(panel);
                f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                f.pack();
                f.setLocationRelativeTo(null);
                f.setVisible(true);
                frame[0] = f;
            });
        } catch (Exception e) {
            return () -> { };
        }
        return () -> SwingUtilities.invokeLater(() -> frame[0].dispose());
    }

    private static void append(Path file, String line) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.isRegularFile(file) && Files.size(file) > 512 * 1024) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // no log: still start the game
        }
    }
}
