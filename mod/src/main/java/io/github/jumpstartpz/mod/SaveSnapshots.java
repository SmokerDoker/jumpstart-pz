package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Copies of a single player save taken before Jumpstart switches mods, so a switch that breaks
 * the save can be undone. Kept in {@code Zomboid/jumpstart/snapshots/<mode>_<save>/<time>.zip}
 * (the last {@link #KEEP} per save), restored only from the main menu: the running game keeps
 * writing its save. The current folder is never deleted by a restore: it moves next to the snapshots.
 *
 * <p>Setting {@code snapshots=auto|on|off} in {@code Zomboid/jumpstart/settings.properties}; auto
 * (the default) skips saves of {@link #AUTO_LIMIT} bytes or more, which take long to copy.
 */
final class SaveSnapshots {
    static final long AUTO_LIMIT = 500L * 1024 * 1024;
    static final int KEEP = 5;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    enum Mode { AUTO, ON, OFF }

    /** One snapshot: the zip, the save it belongs to ("Sandbox/My save"), and when it was taken. */
    record Snapshot(Path zip, String save, String time) {
        String label() {
            return save + "   " + time.replace('_', ' ').replaceFirst("-(\\d\\d)-(\\d\\d)$", ":$1:$2");
        }
    }

    private final Path root;

    /** @param root Zomboid/jumpstart */
    SaveSnapshots(Path root) {
        this.root = root;
    }

    Mode mode() {
        Path file = root.resolve("settings.properties");
        if (Files.isRegularFile(file)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
                return Mode.valueOf(p.getProperty("snapshots", "auto").strip().toUpperCase(Locale.ROOT));
            } catch (IOException | IllegalArgumentException e) {
                return Mode.AUTO;
            }
        }
        return Mode.AUTO;
    }

    /** Whether a switch in this save gets a snapshot first. */
    boolean wanted(Path saveDir) throws IOException {
        return switch (mode()) {
            case OFF -> false;
            case ON -> true;
            case AUTO -> size(saveDir) < AUTO_LIMIT;
        };
    }

    static long size(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    /**
     * Zips the save folder (its path inside {@code savesRoot}, e.g. Sandbox/My save, becomes the
     * zip's top folder). Leaves out Jumpstart's own files in the save. Returns the zip.
     */
    Path take(Path saveDir, Path savesRoot) throws IOException {
        String save = relative(savesRoot, saveDir);
        Path dir = root.resolve("snapshots").resolve(folderName(save));
        Files.createDirectories(dir);
        Path zip = dir.resolve(LocalDateTime.now().format(TIME) + ".zip");
        Path tmp = dir.resolve(zip.getFileName() + ".part");
        try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8);
             Stream<Path> files = Files.walk(saveDir)) {
            z.setComment(save);
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                String rel = relative(saveDir, f);
                if (rel.startsWith("jumpstart/")) {
                    continue;
                }
                z.putNextEntry(new ZipEntry(save + "/" + rel));
                Files.copy(f, z);
                z.closeEntry();
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
        prune(dir);
        return zip;
    }

    /** Keeps the newest {@link #KEEP} snapshots of one save. */
    private static void prune(Path dir) throws IOException {
        List<Path> zips;
        try (Stream<Path> s = Files.list(dir)) {
            zips = s.filter(p -> p.getFileName().toString().endsWith(".zip")).sorted(Comparator.reverseOrder()).toList();
        }
        for (Path old : zips.subList(Math.min(KEEP, zips.size()), zips.size())) {
            Files.deleteIfExists(old);
        }
    }

    /** Every snapshot, newest first. */
    List<Snapshot> list() {
        List<Snapshot> result = new ArrayList<>();
        Path dir = root.resolve("snapshots");
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> s = Files.walk(dir, 2)) {
            for (Path zip : s.filter(p -> p.getFileName().toString().endsWith(".zip")).toList()) {
                String time = zip.getFileName().toString().replaceFirst("\\.zip$", "");
                result.add(new Snapshot(zip, saveOf(zip), time));
            }
        } catch (IOException e) {
            System.out.println("[Jumpstart] cannot list the snapshots: " + e);
        }
        result.sort(Comparator.comparing(Snapshot::time).reversed());
        return result;
    }

    /** The save a zip belongs to, from its comment (what {@link #take} wrote). */
    private static String saveOf(Path zip) {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            String c = z.getComment();
            return c == null ? "?" : c;
        } catch (IOException e) {
            return "?";
        }
    }

    /**
     * Puts the save back as it was in the snapshot. The current folder moves to
     * snapshots/<save>/replaced-<time>/ (nothing is deleted). Main menu only: the game must not
     * have this save open. Returns where the replaced folder went (null if there was none).
     */
    Path restore(Snapshot snapshot, Path savesRoot) throws IOException {
        String save = snapshot.save();
        Path target;
        try {
            target = save.isBlank() || save.equals("?") ? null : savesRoot.resolve(save).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            target = null; // the comment of a zip anyone can write
        }
        if (target == null || !target.startsWith(savesRoot.normalize()) || target.equals(savesRoot.normalize())) {
            throw new IOException("the snapshot does not name a save folder: " + save);
        }
        Path replaced = null;
        if (Files.exists(target)) {
            replaced = root.resolve("snapshots").resolve(folderName(save)).resolve("replaced-" + LocalDateTime.now().format(TIME));
            Files.createDirectories(replaced.getParent());
            Files.move(target, replaced);
        }
        Path base = savesRoot.normalize();
        try (InputStream in = Files.newInputStream(snapshot.zip()); ZipInputStream z = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                Path out = base.resolve(e.getName()).normalize();
                // only the save's own folder, nothing outside it (a zip can name any path)
                if (!out.startsWith(target) || out.equals(target)) {
                    continue;
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return replaced;
    }

    private static String relative(Path base, Path p) {
        return base.normalize().relativize(p.normalize()).toString().replace('\\', '/');
    }

    private static String folderName(String save) {
        return save.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
