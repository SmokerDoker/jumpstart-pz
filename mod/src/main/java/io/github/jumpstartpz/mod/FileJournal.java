package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Files mods write through the game's Lua file writers ({@code getFileWriter}, {@code
 * getModFileWriter}), credited to the Lua file that asked. The log ({@code
 * Zomboid/jumpstart/files/written.txt}, one line per file: path, whether it existed before, the Lua
 * file) lasts across sessions, so uninstalling a mod later still finds what it created. On uninstall
 * the files the mod created move to {@code Zomboid/jumpstart/archive/<mod>-<date>/}; files that
 * existed before it wrote them are never touched.
 */
public final class FileJournal {
    /** Paths logged in this session (the log keeps the first write: "existed before" must not flip). */
    private static final Set<String> seen = ConcurrentHashMap.newKeySet();
    private static volatile Path root;

    private FileJournal() {
    }

    /** Where the log and the archive live; set by Main (the game's data folder + /jumpstart). */
    static void setRoot(Path dir) {
        root = dir;
    }

    /** Called by the game hook at the start of LuaManager.GlobalObject.getFileWriter. */
    public static void onFileWriter(String filename) {
        try {
            String dir = (String) Class.forName("zombie.Lua.LuaManager").getMethod("getLuaCacheDir").invoke(null);
            record(Path.of(dir).resolve(stripSlash(filename)));
        } catch (Throwable t) { // called from inside the game: never throw there
            // a game without that method: nothing logged
        }
    }

    /** Called by the game hook at the start of LuaManager.GlobalObject.getModFileWriter. */
    public static void onModFileWriter(String modId, String filename) {
        try {
            Object mod = Class.forName("zombie.gameStates.ChooseGameInfo").getMethod("getModDetails", String.class).invoke(null, modId);
            String dir = mod == null ? null : (String) mod.getClass().getMethod("getCommonDir").invoke(mod);
            if (dir != null) {
                record(Path.of(dir).resolve(stripSlash(filename)));
            }
        } catch (Throwable t) { // called from inside the game: never throw there
            // nothing logged
        }
    }

    private static String stripSlash(String name) {
        String s = name == null ? "" : name.replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    private static void record(Path file) {
        Path log = log();
        String source = LuaSource.currentFile();
        if (log == null || source == null) {
            return; // the game itself, not a mod's Lua
        }
        String path = file.toAbsolutePath().normalize().toString();
        if (!seen.add(path)) {
            return;
        }
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, path + "\t" + Files.exists(file) + "\t" + source + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[Jumpstart] cannot log a file write: " + e);
        }
    }

    private static Path log() {
        Path r = root;
        return r == null ? null : r.resolve("files").resolve("written.txt");
    }

    /** A logged write: the file, whether it existed before, the Lua file (normalized). */
    record Write(Path file, boolean existedBefore, String source) {
    }

    /** Every logged write by Lua files under one of {@code modDirs} (normalized, ending in '/'), first write per file. */
    static List<Write> writesOf(List<String> modDirs) {
        Path log = log();
        Map<String, Write> writes = new LinkedHashMap<>();
        if (log == null || !Files.isRegularFile(log)) {
            return List.of();
        }
        try {
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length >= 3 && modDirs.stream().anyMatch(f[2]::startsWith)) {
                    writes.putIfAbsent(f[0], new Write(Path.of(f[0]), Boolean.parseBoolean(f[1]), f[2]));
                }
            }
        } catch (IOException e) {
            System.out.println("[Jumpstart] cannot read the file log: " + e);
        }
        return new ArrayList<>(writes.values());
    }

    /**
     * Moves the files a mod created (and that are still there) into the archive, outside its own
     * folders (those go with the mod). Writes the list to files/<mod>.txt. Returns how many moved.
     */
    static int archive(String modId, List<String> modDirs) {
        Path r = root;
        if (r == null) {
            return 0;
        }
        Path archive = r.resolve("archive").resolve(safe(modId) + "-" + LocalDate.now());
        // the game's Lua writers write only below its data folder; the log is a file anyone can edit,
        // so nothing outside that folder is ever moved
        Path dataDir = r.toAbsolutePath().normalize().getParent();
        List<String> moved = new ArrayList<>();
        for (Write w : writesOf(modDirs)) {
            String normalized = w.file().toString().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
            if (w.existedBefore() || !Files.isRegularFile(w.file()) || modDirs.stream().anyMatch(normalized::startsWith)
                    || dataDir == null || !w.file().toAbsolutePath().normalize().startsWith(dataDir)
                    || w.file().toAbsolutePath().normalize().startsWith(r.toAbsolutePath().normalize())) {
                continue;
            }
            Path target = archive.resolve(archiveName(w.file()));
            try {
                Files.createDirectories(target.getParent());
                Files.move(w.file(), target);
                moved.add(w.file() + " -> " + target);
            } catch (IOException e) {
                System.out.println("[Jumpstart] cannot archive " + w.file() + ": " + e);
            }
        }
        if (!moved.isEmpty()) {
            try {
                Files.createDirectories(r.resolve("files"));
                Files.writeString(r.resolve("files").resolve(safe(modId) + ".txt"), String.join("\n", moved) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.out.println("[Jumpstart] cannot write the archive list: " + e);
            }
        }
        return moved.size();
    }

    /** The file's path without the drive or root, so archived files keep their folders. */
    static Path archiveName(Path file) {
        Path p = file.toAbsolutePath().normalize();
        Path rootPart = p.getRoot();
        return rootPart == null ? p : rootPart.relativize(p);
    }

    static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
