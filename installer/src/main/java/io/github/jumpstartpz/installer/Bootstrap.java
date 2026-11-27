package io.github.jumpstartpz.installer;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Jumpstart's own folder {@code ~/.jumpstart} and the installer updating itself.
 *
 * <ul>
 *   <li>{@code JumpstartSetup.jar}: a copy of the installer that Steam's launch options start (with the
 *       game's own Java). It does not depend on the installed app, so removing the app never leaves
 *       Steam pointing at a missing program: the game just starts.</li>
 *   <li>{@code versions/JumpstartSetup-<version>.jar} and {@code current.txt}: newer installers from
 *       signed releases (SHA-256 from the signed manifest, checked again before every use). Every
 *       start, also of the installed app, hands over to the newest one, so the installer's logic is
 *       updated without a new .msi/.dmg and without replacing a running (on Windows locked) jar.</li>
 * </ul>
 */
final class Bootstrap {
    private static final String DELEGATED = "jumpstart.delegated";

    private Bootstrap() {
    }

    static Path dir() {
        String custom = System.getProperty("jumpstart.home");
        return custom != null && !custom.isBlank() ? Path.of(custom) : Path.of(System.getProperty("user.home"), ".jumpstart");
    }

    static Path bootJar() {
        return dir().resolve("JumpstartSetup.jar");
    }

    private static Path current() {
        return dir().resolve("current.txt");
    }

    /** The version of the code running now (the jar's Implementation-Version), or empty in a build folder. */
    static Optional<String> ownVersion() {
        return Optional.ofNullable(Bootstrap.class.getPackage().getImplementationVersion()).filter(v -> !v.isBlank());
    }

    /** The jar this code runs from, if it runs from a jar. */
    static Optional<Path> ownJar() {
        try {
            Path jar = Path.of(Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(jar) && jar.toString().endsWith(".jar") ? Optional.of(jar) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * The jar the process was started with ({@code java -jar X}): also when a newer installer from
     * {@code versions/} runs now, the path a start script should name stays this one.
     */
    static Optional<Path> launchJar() {
        String cp = System.getProperty("java.class.path", "");
        if (!cp.isBlank() && !cp.contains(java.io.File.pathSeparator) && cp.endsWith(".jar")) {
            Path jar = Path.of(cp).toAbsolutePath();
            if (Files.isRegularFile(jar)) {
                return Optional.of(jar);
            }
        }
        return ownJar();
    }

    /** A newer installer from a signed release, still matching its recorded SHA-256. */
    record Newer(String version, LocalDate built, Path jar) {
    }

    static Optional<Newer> newer() {
        try {
            if (!Files.isRegularFile(current())) {
                return Optional.empty();
            }
            Map<String, String> v = new HashMap<>();
            for (String line : Files.readAllLines(current(), StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    v.put(line.substring(0, eq), line.substring(eq + 1).strip());
                }
            }
            Path jar = dir().resolve("versions").resolve("JumpstartSetup-" + v.get("version") + ".jar");
            LocalDate built = Instant.parse(v.get("built")).atZone(ZoneOffset.UTC).toLocalDate();
            if (!Files.isRegularFile(jar) || !Updater.sha256(Files.readAllBytes(jar)).equals(v.get("sha256"))) {
                return Optional.empty();
            }
            return Optional.of(new Newer(v.get("version"), built, jar));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Runs the newest installer instead of this one, if a newer one is there. True if it did (its main
     * has run; the caller returns). Never from a build folder, and never twice.
     */
    static boolean delegate(String[] args) {
        if (System.getProperty(DELEGATED) != null || ownVersion().isEmpty()) {
            return false;
        }
        Optional<Newer> n = newer();
        if (n.isEmpty() || !Versions.isUpdate(n.get().version(), n.get().built(), ownVersion())) {
            return false;
        }
        try {
            System.setProperty(DELEGATED, n.get().version());
            // not closed: the newer installer's classes are used until the process ends
            @SuppressWarnings("resource")
            URLClassLoader loader = new URLClassLoader(new URL[] {n.get().jar().toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            Method main = loader.loadClass(InstallerMain.class.getName()).getMethod("main", String[].class);
            main.invoke(null, (Object) args);
            return true;
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            System.clearProperty(DELEGATED);
            return false; // the newer one is broken: this one carries on
        }
    }

    /**
     * Keeps the installer of a signed release for the next start, if it is newer than what runs now
     * and what is kept already.
     */
    static void fetch(Updater updater, Updater.Release r, Consumer<String> log) {
        try {
            Optional<Newer> kept = newer();
            if (!Versions.isUpdate(r.version(), r.builtDay(), ownVersion().or(() -> Optional.of("0.0.0")))
                    || kept.isPresent() && !Versions.isUpdate(r.version(), r.builtDay(), Optional.of(kept.get().version()))) {
                return;
            }
            Path versions = dir().resolve("versions");
            Path file = updater.download(r, r.setup(), r.setupSha256(), Files.createTempDirectory("jumpstart-setup"));
            Files.createDirectories(versions);
            Path target = versions.resolve("JumpstartSetup-" + r.version() + ".jar");
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            write(current(), "version=" + r.version() + "\nbuilt=" + r.built() + "\nsha256=" + r.setupSha256() + "\n");
            log.accept("installer " + r.version() + " kept for the next start");
            prune(versions, target);
        } catch (IOException | RuntimeException e) {
            log.accept("could not keep the new installer: " + e.getMessage());
        }
    }

    /** Copies this installer to {@code ~/.jumpstart/JumpstartSetup.jar}, the one Steam starts; true if it is there. */
    static boolean placeBootJar() {
        Optional<Path> own = ownJar();
        Path boot = bootJar();
        if (own.isEmpty()) {
            return Files.isRegularFile(boot);
        }
        try {
            if (own.get().toAbsolutePath().normalize().equals(boot.toAbsolutePath().normalize())) {
                return true;
            }
            Files.createDirectories(boot.getParent());
            Path tmp = boot.resolveSibling("JumpstartSetup.jar.tmp");
            Files.copy(own.get(), tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, boot, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            // in use (a game started through it is running): the one there stays
            return Files.isRegularFile(boot);
        }
    }

    /** Removes ~/.jumpstart (after the launch options no longer start it); what is in use stays. */
    static void remove() {
        if (!Files.isDirectory(dir())) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir())) {
            files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // in use
                }
            });
        } catch (IOException e) {
            // nothing to remove
        }
    }

    private static void prune(Path versions, Path keep) throws IOException {
        try (Stream<Path> files = Files.list(versions)) {
            for (Path p : files.toList()) {
                if (!p.equals(keep)) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        // in use: removed next time
                    }
                }
            }
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
