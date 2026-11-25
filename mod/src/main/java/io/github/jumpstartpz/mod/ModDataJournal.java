package io.github.jumpstartpz.mod;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The save's global ModData tables (GlobalModData, Lua's ModData.getOrCreate) credited to the
 * mod whose Lua created them, kept per save in {@code <save>/jumpstart/moddata-keys.txt}. When a
 * mod is uninstalled its tables leave the save into {@code <save>/jumpstart/moddata/<mod>/<key>.bin}
 * (the game's own table format); when it comes back they return. Data on single objects (items,
 * players) stays in the save: the game ignores keys nobody reads.
 */
public final class ModDataJournal {
    private static final Set<String> seen = ConcurrentHashMap.newKeySet();
    /** Our own calls while restoring are not a mod's. */
    private static final ThreadLocal<Boolean> restoring = ThreadLocal.withInitial(() -> false);
    private static final int FORMAT = 1;

    private ModDataJournal() {
    }

    /** Called by the game hooks at the start of GlobalModData.getOrCreate, create(String) and add. */
    public static void onKey(String key) {
        try {
            record(key);
        } catch (Throwable t) { // called from inside the game: never throw there
            System.out.println("[Jumpstart] ModData log: " + t);
        }
    }

    private static void record(String key) {
        if (key == null || restoring.get()) {
            return;
        }
        String source = LuaSource.currentFile();
        Path save = saveDir();
        if (source == null || save == null || !seen.add(save + "\t" + key)) {
            return;
        }
        Path file = save.resolve("jumpstart").resolve("moddata-keys.txt");
        try {
            if (keysOf(file).containsKey(key)) {
                return; // the first mod that made the table owns it
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, key + "\t" + source + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[Jumpstart] cannot log a ModData table: " + e);
        }
    }

    /** key -> Lua file, from the save's log. */
    static Map<String, String> keysOf(Path file) throws IOException {
        Map<String, String> keys = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    keys.putIfAbsent(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        }
        return keys;
    }

    /** Uninstall: the mod's tables leave the save into files. Returns how many. */
    static int stash(String modId, List<String> modDirs) {
        Path save = saveDir();
        if (save == null) {
            return 0;
        }
        int count = 0;
        try {
            Object data = globalModData();
            Class<?> gmd = data.getClass();
            Path target = save.resolve("jumpstart").resolve("moddata").resolve(FileJournal.safe(modId));
            for (Map.Entry<String, String> e : keysOf(save.resolve("jumpstart").resolve("moddata-keys.txt")).entrySet()) {
                if (modDirs.stream().noneMatch(e.getValue()::startsWith)) {
                    continue;
                }
                Object table = gmd.getMethod("get", String.class).invoke(data, e.getKey());
                if (table == null) {
                    continue;
                }
                Files.createDirectories(target);
                try (OutputStream out = Files.newOutputStream(target.resolve(FileJournal.safe(e.getKey()) + ".bin"));
                     DataOutputStream data2 = new DataOutputStream(out)) {
                    data2.writeInt(FORMAT);
                    data2.writeInt(worldVersion());
                    data2.writeUTF(e.getKey());
                    Class.forName("se.krka.kahlua.vm.KahluaTable").getMethod("save", DataOutputStream.class).invoke(table, data2);
                }
                gmd.getMethod("remove", String.class).invoke(data, e.getKey());
                count++;
            }
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot take the ModData of " + modId + " out of the save: " + e);
        }
        return count;
    }

    /** Reinstall (the mod's Lua loads again): its stashed tables come back into the save. Returns how many. */
    static int restore(String modId) {
        Path save = saveDir();
        if (save == null) {
            return 0;
        }
        Path dir = save.resolve("jumpstart").resolve("moddata").resolve(FileJournal.safe(modId));
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int count = 0;
        restoring.set(true);
        try (Stream<Path> files = Files.list(dir)) {
            Object data = globalModData();
            Class<?> gmd = data.getClass();
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".bin")).toList()) {
                try (InputStream in = Files.newInputStream(f); DataInputStream din = new DataInputStream(in)) {
                    if (din.readInt() != FORMAT) {
                        continue;
                    }
                    int version = din.readInt();
                    String key = din.readUTF();
                    if (Boolean.TRUE.equals(gmd.getMethod("exists", String.class).invoke(data, key))) {
                        continue; // the mod made a new one already: keep the stash, do not overwrite
                    }
                    Object table = gmd.getMethod("create", String.class).invoke(data, key);
                    Class.forName("se.krka.kahlua.vm.KahluaTable").getMethod("load", DataInputStream.class, int.class)
                            .invoke(table, din, version);
                }
                Files.delete(f);
                count++;
            }
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot bring the ModData of " + modId + " back: " + e);
        } finally {
            restoring.set(false);
        }
        return count;
    }

    private static Object globalModData() throws ReflectiveOperationException {
        return Class.forName("zombie.world.moddata.GlobalModData").getField("instance").get(null);
    }

    private static int worldVersion() {
        try {
            return Class.forName("zombie.iso.IsoWorld").getField("WorldVersion").getInt(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    /** The running save's folder, or null in the main menu. */
    static Path saveDir() {
        try {
            Object fs = Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
            String dir = (String) fs.getClass().getMethod("getCurrentSaveDir").invoke(fs);
            return dir == null || dir.isBlank() ? null : Path.of(dir);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
