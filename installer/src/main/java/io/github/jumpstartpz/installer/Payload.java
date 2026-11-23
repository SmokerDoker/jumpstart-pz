package io.github.jumpstartpz.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * What the installer puts in place, built into JumpstartSetup.jar as {@code payload.zip}:
 *
 * <pre>
 * VERSION
 * mods/ZombieBuddy/   the fork's mod folder, with libs/ZombieBuddy.jar (the Java agent)
 * mods/Jumpstart/     the Jumpstart mod
 * </pre>
 */
public final class Payload {
    private final Path dir;

    private Payload(Path dir) {
        this.dir = dir;
    }

    /** A payload unpacked in a folder (tests, or {@code --payload <folder>}). */
    public static Payload of(Path dir) throws IOException {
        Payload p = new Payload(dir.toAbsolutePath().normalize());
        for (Path required : new Path[] {p.zombieBuddyJar(), p.zombieBuddyMod().resolve("42/mod.info"),
                p.jumpstartMod().resolve("42/mod.info")}) {
            if (!Files.isRegularFile(required)) {
                throw new IOException("incomplete payload, missing " + required);
            }
        }
        return p;
    }

    /** The payload built into this installer, unpacked into a temporary folder; empty in a build without one. */
    public static Optional<Payload> bundled() throws IOException {
        try (InputStream in = Payload.class.getResourceAsStream("payload.zip")) {
            if (in == null) {
                return Optional.empty();
            }
            Path dir = Files.createTempDirectory("jumpstart-setup");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteQuietly(dir)));
            unzip(in, dir);
            return Optional.of(of(dir));
        }
    }

    static void unzip(InputStream in, Path dir) throws IOException {
        Path root = dir.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                Path target = root.resolve(e.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("payload entry outside its folder: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public Path zombieBuddyMod() {
        return dir.resolve("mods/ZombieBuddy");
    }

    /** The fork's Java agent; the same file as in the mod folder's libs/. */
    public Path zombieBuddyJar() {
        return zombieBuddyMod().resolve("libs/ZombieBuddy.jar");
    }

    public Path jumpstartMod() {
        return dir.resolve("mods/Jumpstart");
    }

    /** The kit version this payload was built as, or the Jumpstart mod's modversion. */
    public String version() {
        try {
            Path v = dir.resolve("VERSION");
            if (Files.isRegularFile(v)) {
                return Files.readString(v, StandardCharsets.UTF_8).strip();
            }
        } catch (IOException e) {
            // fall back to mod.info
        }
        return Setup.modVersion(jumpstartMod()).orElse("?");
    }

    static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // a temp file left behind
                }
            });
        } catch (IOException e) {
            // a temp folder left behind
        }
    }
}
