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
 * The save's global ModData tables (GlobalModData, Lua's ModData.getOrCreate) and which mod's Lua
 * created them, kept per save in {@code <save>/jumpstart/moddata-keys.txt}. A mod owns a table only
 * if its Lua made it (the key did not exist before the call); touching an existing table (the
 * game's, another mod's, or one from before Jumpstart kept this log) only records a use. When a mod
 * is uninstalled, the tables it owns and nobody else used leave the save into
 * {@code <save>/jumpstart/moddata/<mod>/<key>.bin} (the game's own table format) and come back
 * when it is installed again; tables another mod also used stay and are named in the log. Data on
 * single objects (items, players) stays in the save: the game ignores keys nobody reads.
 */
public final class ModDataJournal {
    /** (save, key, Lua file) logged in this session. */
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

    private static void record(String key) throws ReflectiveOperationException {
        if (key == null || restoring.get()) {
            return;
        }
        String source = LuaSource.currentFile();
        Path save = saveDir();
        if (source == null || save == null || !seen.add(save + "\t" + key + "\t" + source)) {
            return;
        }
        // at the start of the call: did the table exist before this mod asked for it?
        Object data = globalModData();
        boolean existed = Boolean.TRUE.equals(data.getClass().getMethod("exists", String.class).invoke(data, key));
        Path file = save.resolve("jumpstart").resolve("moddata-keys.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line(key, source, !existed), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[Jumpstart] cannot log a ModData table: " + e);
        }
    }

    static String line(String key, String source, boolean created) {
        return key + "\t" + source + "\t" + (created ? "created" : "used") + "\n";
    }

    /** Who made a table (null if no logged mod did) and every Lua file that touched it. */
    record Owner(String creator, Set<String> users) {
    }

    /** key -> owner, from the save's log. A line without a kind counts as a use (never as ownership). */
    static Map<String, Owner> ownersOf(Path file) throws IOException {
        Map<String, String> creators = new LinkedHashMap<>();
        Map<String, Set<String>> users = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 2 || f[0].isEmpty()) {
                    continue;
                }
                users.computeIfAbsent(f[0], k -> new java.util.LinkedHashSet<>()).add(f[1]);
                if (f.length >= 3 && f[2].equals("created")) {
                    creators.putIfAbsent(f[0], f[1]);
                }
            }
        }
        Map<String, Owner> owners = new LinkedHashMap<>();
        users.forEach((key, u) -> owners.put(key, new Owner(creators.get(key), u)));
        return owners;
    }

    /**
     * The keys an uninstall may take out of the save: made by the mod and used by no other mod.
     * Keys the mod made that others used too go to {@code shared} (they stay in the save).
     */
    static List<String> stashable(Map<String, Owner> owners, List<String> modDirs, List<String> shared) {
        List<String> keys = new java.util.ArrayList<>();
        for (Map.Entry<String, Owner> e : owners.entrySet()) {
            Owner o = e.getValue();
            if (o.creator() == null || modDirs.stream().noneMatch(o.creator()::startsWith)) {
                continue;
            }
            boolean others = o.users().stream().anyMatch(u -> modDirs.stream().noneMatch(u::startsWith));
            (others ? shared : keys).add(e.getKey());
        }
        return keys;
    }

    /** Uninstall: the tables the mod made and nobody else used leave the save into files. Returns how many. */
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
            List<String> shared = new java.util.ArrayList<>();
            List<String> keys = stashable(ownersOf(save.resolve("jumpstart").resolve("moddata-keys.txt")), modDirs, shared);
            if (!shared.isEmpty()) {
                System.out.println("[Jumpstart] " + modId + ": ModData tables other mods use too stay in the save: " + shared);
            }
            for (String key : keys) {
                Object table = gmd.getMethod("get", String.class).invoke(data, key);
                if (table == null) {
                    continue;
                }
                Files.createDirectories(target);
                try (OutputStream out = Files.newOutputStream(target.resolve(FileJournal.safe(key) + ".bin"));
                     DataOutputStream data2 = new DataOutputStream(out)) {
                    data2.writeInt(FORMAT);
                    data2.writeInt(worldVersion());
                    data2.writeUTF(key);
                    Class.forName("se.krka.kahlua.vm.KahluaTable").getMethod("save", DataOutputStream.class).invoke(table, data2);
                }
                gmd.getMethod("remove", String.class).invoke(data, key);
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
