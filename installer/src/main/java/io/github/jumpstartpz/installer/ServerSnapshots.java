package io.github.jumpstartpz.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The save snapshots a server took before its changes for everybody (Jumpstart's SaveSnapshots:
 * {@code <data>/jumpstart/snapshots/Multiplayer_<name>/<time>.zip}, entries
 * {@code Multiplayer/<name>/...}), listed and restored from the setup while the server is stopped.
 */
final class ServerSnapshots {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Path zomboidDir;
    private final String name;

    ServerSnapshots(Path zomboidDir, String name) {
        this.zomboidDir = zomboidDir;
        this.name = name;
    }

    private Path dir() {
        return zomboidDir.resolve("jumpstart").resolve("snapshots").resolve(("Multiplayer/" + name).replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    private Path save() {
        return zomboidDir.resolve("Saves").resolve("Multiplayer").resolve(name);
    }

    /** The snapshots of this server, newest first. */
    List<Path> list() throws IOException {
        if (!Files.isDirectory(dir())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir())) {
            return files.filter(f -> f.getFileName().toString().matches("\\d{4}-\\d\\d-\\d\\d_\\d\\d-\\d\\d-\\d\\d\\.zip"))
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed()).toList();
        }
    }

    /** "latest", a file name in the snapshot folder, or a path to one of its zips. */
    Optional<Path> find(String which) throws IOException {
        List<Path> all = list();
        if (which == null || which.isBlank() || which.equals("latest")) {
            return all.stream().findFirst();
        }
        Path wanted = Path.of(which).getFileName();
        return all.stream().filter(f -> f.getFileName().equals(wanted)).findFirst();
    }

    /**
     * Puts the save back as it was in the snapshot. The current save folder moves next to the
     * snapshots ({@code replaced-<time>}); nothing is deleted. Only entries inside the server's save
     * folder are written (a zip can name any path). Returns where the replaced folder went, or null.
     */
    Path restore(Path snapshot) throws IOException {
        Path target = save().toAbsolutePath().normalize();
        Path savesRoot = zomboidDir.resolve("Saves").toAbsolutePath().normalize();
        // first make sure the zip has this save in it: the current folder moves only then
        boolean any = false;
        try (InputStream in = Files.newInputStream(snapshot); ZipInputStream z = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null && !any; ) {
                Path out = savesRoot.resolve(e.getName()).normalize();
                any = !e.isDirectory() && out.startsWith(target) && !out.equals(target);
            }
        }
        if (!any) {
            throw new IOException(snapshot + " has no files of the save Multiplayer/" + name);
        }
        Path replaced = null;
        if (Files.exists(target)) {
            String stamp = "replaced-" + LocalDateTime.now().format(TIME);
            replaced = dir().resolve(stamp);
            for (int n = 2; Files.exists(replaced); n++) {
                replaced = dir().resolve(stamp + "-" + n); // two restores in the same second
            }
            Files.createDirectories(replaced.getParent());
            Files.move(target, replaced);
        }
        int files = 0;
        try (InputStream in = Files.newInputStream(snapshot); ZipInputStream z = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                Path out = savesRoot.resolve(e.getName()).normalize();
                if (!out.startsWith(target) || out.equals(target)) {
                    continue;
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
                    files++;
                }
            }
        }
        if (files == 0) {
            throw new IOException(snapshot + " has no files of the save Multiplayer/" + name);
        }
        return replaced;
    }
}
