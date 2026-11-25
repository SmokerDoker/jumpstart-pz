package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Moving an uninstalled mod's folder out of {@code Zomboid/mods}. On Windows the running game can
 * keep files of a mod folder open, so the move fails. Then the folder is copied to its archive place,
 * everything that can be deleted is deleted, and the rest is marked and removed at the next game
 * start, before anything of it is used again. The mod is already off in the mod list by then.
 */
final class PendingRemoval {
    /** Inside a leftover folder: only folders with it are removed at the next start. */
    static final String MARKER = ".jumpstart-uninstalled";

    private PendingRemoval() {
    }

    /**
     * Moves {@code folder} to {@code target}; if the game holds it open, copies it there and leaves
     * the rest for {@link #cleanUp}. Returns null when the folder is gone, else a note for the player.
     */
    static String moveOut(Path folder, Path target, Path pendingFile) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(folder, target);
            return null;
        } catch (IOException e) {
            copyTree(folder, target);
            deleteTree(folder);
            if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            Files.writeString(folder.resolve(MARKER), "uninstalled by Jumpstart; archived in " + target + "\n",
                    StandardCharsets.UTF_8);
            Files.createDirectories(pendingFile.getParent());
            Files.writeString(pendingFile, folder.toAbsolutePath().normalize() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return "the game still uses files of it; they are removed at the next game start";
        }
    }

    /**
     * At game start: removes the leftover folders listed in {@code pendingFile}, but only folders
     * directly inside {@code modsRoot} that still carry the marker (never anything else).
     */
    static void cleanUp(Path pendingFile, Path modsRoot) {
        if (!Files.isRegularFile(pendingFile)) {
            return;
        }
        List<String> keep = new ArrayList<>();
        try {
            Path root = modsRoot.toAbsolutePath().normalize();
            for (String line : Files.readAllLines(pendingFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                Path dir = Path.of(line.strip()).toAbsolutePath().normalize();
                if (!root.equals(dir.getParent()) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(dir.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) {
                    continue; // gone already, outside the mods folder, or not ours any more
                }
                deleteTree(dir);
                if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                    keep.add(line.strip());
                } else {
                    System.out.println("[Jumpstart] removed the rest of an uninstalled mod: " + dir);
                }
            }
            if (keep.isEmpty()) {
                Files.deleteIfExists(pendingFile);
            } else {
                Files.write(pendingFile, keep, StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot remove the rest of uninstalled mods: " + e);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path src : files.toList()) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(dst);
                } else if (Files.isRegularFile(src, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Deletes what it can, deepest first; files the game holds open stay. Never follows links. */
    private static void deleteTree(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // in use: removed at the next start
                }
            }
        } catch (IOException e) {
            // the folder is gone or cannot be read: nothing to do
        }
    }
}
