package io.github.jumpstartpz.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import io.github.bitecheckpz.core.Hashing;

/**
 * Jumpstart's own copies of the Java mod jars that ran, by hash: {@code <root>/<mod id>/<sha256>.jar}
 * and {@code versions.tsv} (hash, when, where the jar came from), the newest {@link #KEEP}.
 *
 * <p>Jumpstart loads a mod live from its copy: Steam updates Workshop files whenever it likes, and a
 * jar swapped under the running game would hand its classes' resources out from another version.
 * The copies are also the versions a player can go back to.
 */
public final class JarCache {
    public static final int KEEP = 5;

    /** One version of a mod: its hash, when Jumpstart first saw it run, the jar it came from. */
    public record Version(String sha256, Instant seen, String source) {
    }

    private final Path root;

    public JarCache(Path root) {
        this.root = root;
    }

    /**
     * The copy of this jar, made now if there is none (atomically: a half copy is never used).
     * Checks the copy's hash. {@code sha256}: the jar's hash, or null to work it out.
     */
    public synchronized Path store(String modId, Path jar, String sha256) throws IOException {
        String sha = sha256 == null || sha256.isEmpty() ? Hashing.sha256Hex(jar) : sha256;
        Path dir = dir(modId);
        Files.createDirectories(dir);
        Path copy = dir.resolve(sha + ".jar");
        if (!Files.isRegularFile(copy) || !Hashing.sha256Hex(copy).equals(sha)) {
            Path tmp = dir.resolve(sha + ".jar.part");
            Files.copy(jar, tmp, StandardCopyOption.REPLACE_EXISTING);
            if (!Hashing.sha256Hex(tmp).equals(sha)) {
                Files.deleteIfExists(tmp);
                throw new IOException(jar + " changed while it was copied");
            }
            Files.move(tmp, copy, StandardCopyOption.REPLACE_EXISTING);
        }
        List<Version> versions = new ArrayList<>(versions(modId));
        if (versions.stream().noneMatch(v -> v.sha256().equals(sha))) {
            versions.add(new Version(sha, Instant.now(), jar.toAbsolutePath().toString()));
        }
        versions.sort(Comparator.comparing(Version::seen).reversed());
        List<Version> keep = versions.subList(0, Math.min(KEEP, versions.size()));
        for (Version old : versions.subList(keep.size(), versions.size())) {
            if (!old.sha256().equals(sha)) {
                Files.deleteIfExists(dir.resolve(old.sha256() + ".jar"));
            }
        }
        write(modId, keep);
        return copy;
    }

    /** The versions kept for a mod, newest first. */
    public List<Version> versions(String modId) {
        Path file = dir(modId).resolve("versions.tsv");
        List<Version> versions = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return versions;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", 3);
                if (f.length == 3 && f[0].matches("[0-9a-f]{64}") && Files.isRegularFile(dir(modId).resolve(f[0] + ".jar"))) {
                    try {
                        versions.add(new Version(f[0], Instant.ofEpochSecond(Long.parseLong(f[1])), f[2]));
                    } catch (NumberFormatException e) {
                        // a broken line
                    }
                }
            }
        } catch (IOException e) {
            return versions;
        }
        versions.sort(Comparator.comparing(Version::seen).reversed());
        return versions;
    }

    /** The kept copy of one version, if there is one (and it still has its hash). */
    public Optional<Path> copy(String modId, String sha256) {
        Path copy = dir(modId).resolve(sha256 + ".jar");
        try {
            return Files.isRegularFile(copy) && Hashing.sha256Hex(copy).equals(sha256) ? Optional.of(copy) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Whether a path is one of the copies (then {@link #sourceOf} names the jar it came from). */
    public boolean contains(Path jar) {
        return jar.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }

    /** The jar a copy came from, as it was recorded. */
    public Optional<String> sourceOf(String modId, String sha256) {
        return versions(modId).stream().filter(v -> v.sha256().equals(sha256)).map(Version::source).findFirst();
    }

    private void write(String modId, List<Version> versions) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Version v : versions) {
            sb.append(v.sha256()).append('\t').append(v.seen().getEpochSecond()).append('\t')
                    .append(v.source().replace('\t', ' ').replace('\n', ' ')).append('\n');
        }
        Path file = dir(modId).resolve("versions.tsv");
        Path tmp = file.resolveSibling("versions.tsv.part");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path dir(String modId) {
        return root.resolve(modId.replaceAll("[^A-Za-z0-9._-]", "_"));
    }
}
