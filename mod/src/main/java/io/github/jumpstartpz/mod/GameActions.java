package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import io.github.bitecheckpz.core.Verifier;
import io.github.bitecheckpz.launcher.Config;
import io.github.bitecheckpz.launcher.UsageSharing;
import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.ZombieBuddyFork;
import io.github.jumpstartpz.core.gate.BiteCheckGate;
import io.github.jumpstartpz.core.market.LibraryClient;

/** {@link Actions} in the game: BiteCheck's gate, the Jumpstart fork of ZombieBuddy, PZ's mod folders, Steam. */
final class GameActions implements Actions {
    static final String CLIENT = "jumpstart/0.2";
    /** ZombieBuddy runs as the Java agent, not as a mod; Jumpstart is listed (it cannot be switched). */
    private static final Set<String> SKIP = Set.of("ZombieBuddy");

    private final LiveInstaller installer;
    private final ZombieBuddyFork zombieBuddy;
    private final Path biteCheckDir;
    private final Workshop workshop = new SteamWorkshopAccess();
    private final ReflectiveLuaGame luaGame = new ReflectiveLuaGame();
    /** Mod id to its folder, for mods found in downloaded Workshop items and the mod folders. */
    private final Map<String, String> downloadedModDirs = new java.util.concurrent.ConcurrentHashMap<>();
    /** What each mod's Lua did in this session, to take back when it is switched off and give back when it comes on. */
    private final Map<String, ModJournal> journals = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A mod's Lua side in this session: the changes to Lua's globals it made while it loaded live
     * (none for a mod that came with the save), and while it is off, its parked event handlers.
     */
    static final class ModJournal {
        List<LuaJournal.Change> changes = List.of();
        Map<String, List<Object>> parkedHandlers;
        /** What could not be taken back on the last switch-off, for the player. */
        String leftovers = "";
        /**
         * The mod came with the save and has parts the game reads only while a save loads (items,
         * animations, ...): they stay in this session after its Lua is off.
         */
        boolean restartLeft;

        boolean parked() {
            return parkedHandlers != null;
        }

        /** The Lua classes (tables) the mod added: their windows close with it. */
        java.util.Set<Object> addedTables(LuaLive.Game game) {
            java.util.Set<Object> tables = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (LuaJournal.Change c : changes) {
                if (c.before() == LuaJournal.ABSENT && game.isTable(c.after())) {
                    tables.add(c.after());
                }
            }
            return tables;
        }
    }

    GameActions(LiveInstaller installer, ZombieBuddyFork zombieBuddy, Path biteCheckDir) {
        this.installer = installer;
        this.zombieBuddy = zombieBuddy;
        this.biteCheckDir = biteCheckDir;
    }

    @Override
    public List<ModEntry> javaMods() {
        Map<String, ModEntry> mods = new LinkedHashMap<>();
        try {
            Object fs = Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
            List<String> dirs = new ArrayList<>();
            fs.getClass().getMethod("getAllModFolders", List.class).invoke(fs, dirs);
            for (String dir : dirs) {
                FolderMod mod = readMod(dir);
                if (mod != null && mod.java() != null && !SKIP.contains(mod.id()) && !mods.containsKey(mod.id())) {
                    mods.put(mod.id(), mod.java());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot list the mod folders: " + e);
        }
        List<ModEntry> list = new ArrayList<>(mods.values());
        // Jumpstart itself first, then by name
        list.sort(Comparator.comparing((ModEntry m) -> !m.id().equals(SecureScreen.SELF))
                .thenComparing(m -> m.name().toLowerCase(Locale.ROOT)));
        return list;
    }

    @Override
    public List<InstalledMod> installedMods() {
        Map<String, InstalledMod> mods = new LinkedHashMap<>();
        try {
            Object fs = Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
            List<String> dirs = new ArrayList<>();
            fs.getClass().getMethod("getAllModFolders", List.class).invoke(fs, dirs);
            for (String dir : dirs) {
                if (!availableHere(dir)) {
                    continue; // a mod for another game version: it cannot load anyway
                }
                FolderMod mod = readMod(dir);
                if (mod == null || SKIP.contains(mod.id()) || mods.containsKey(mod.id())) {
                    continue;
                }
                downloadedModDirs.putIfAbsent(mod.id(), dir);
                mods.put(mod.id(), new InstalledMod(mod.id(), mod.name(), mod.java(), workshopIdOf(dir), requiresOf(dir)));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot list the mod folders: " + e);
        }
        List<InstalledMod> list = new ArrayList<>(mods.values());
        Map<String, Boolean> enabled = new java.util.HashMap<>();
        for (InstalledMod m : list) {
            enabled.put(m.id(), isEnabled(m.id()));
        }
        list.sort(Comparator.comparing((InstalledMod m) -> !m.id().equals(SecureScreen.SELF))
                .thenComparing(m -> !enabled.get(m.id()))
                .thenComparing(m -> m.name().toLowerCase(Locale.ROOT)));
        return list;
    }

    /** The mods this one requires (mod.info require=), cleaned; empty if the game cannot say. */
    @SuppressWarnings("unchecked")
    private static List<String> requiresOf(String dir) {
        try {
            Object info = Class.forName("zombie.gameStates.ChooseGameInfo").getMethod("readModInfo", String.class).invoke(null, dir);
            Object require = info == null ? null : info.getClass().getMethod("getRequire").invoke(info);
            return require instanceof List<?> list ? ModDeps.cleaned((List<String>) list) : List.of();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }

    private static boolean availableHere(String dir) {
        try {
            Object info = Class.forName("zombie.gameStates.ChooseGameInfo").getMethod("readModInfo", String.class).invoke(null, dir);
            return info != null && Boolean.TRUE.equals(info.getClass().getMethod("isAvailable").invoke(info));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true; // when in doubt, list it
        }
    }

    private final Map<String, Details> details = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Details> workshopDetails = new java.util.concurrent.ConcurrentHashMap<>();
    private final io.github.jumpstartpz.core.market.WorkshopPage workshopPage = new io.github.jumpstartpz.core.market.WorkshopPage();

    @Override
    public Map<Long, String> workshopTitles(List<Long> ids) {
        Map<Long, String> titles = new LinkedHashMap<>();
        for (long id : ids) {
            try {
                workshopPage.fetch(id).ifPresent(p -> titles.put(id, p.title()));
            } catch (IOException | RuntimeException e) {
                // shown as "Workshop <id>"
            }
        }
        return titles;
    }

    @Override
    public Details details(String modId) {
        return details.computeIfAbsent(modId, id -> {
            String dir = downloadedModDirs.get(id);
            if (dir == null) {
                return Details.NONE;
            }
            try {
                Object info = Class.forName("zombie.gameStates.ChooseGameInfo").getMethod("readModInfo", String.class).invoke(null, dir);
                if (info == null) {
                    return Details.NONE;
                }
                Object description = info.getClass().getMethod("getDescription").invoke(info);
                String image = imageOf(dir, (String) info.getClass().getMethod("getIcon").invoke(info));
                if (image == null && (Integer) info.getClass().getMethod("getPosterCount").invoke(info) > 0) {
                    image = imageOf(dir, (String) info.getClass().getMethod("getPoster", int.class).invoke(info, 0));
                }
                return new Details(richTextToPlain(description == null ? "" : description.toString()), image);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return Details.NONE;
            }
        });
    }

    /**
     * An icon or poster path from mod.info (a file anyone can write) as an absolute path, only if it
     * is a file inside the mod's own folder: no network paths (\\server\..., where Windows would log in
     * by itself), nothing outside the folder, also not through a link.
     */
    static String imageOf(String dir, String path) {
        if (path == null || path.isBlank() || dir == null) {
            return null;
        }
        String s = path.replace('\\', '/');
        if (s.startsWith("//") || s.contains("://")) {
            return null;
        }
        try {
            java.nio.file.Path given = java.nio.file.Path.of(dir).toAbsolutePath().normalize();
            java.nio.file.Path root = given.toRealPath();
            java.nio.file.Path p = java.nio.file.Path.of(path);
            p = (p.isAbsolute() ? p : given.resolve(p)).normalize();
            // first by name only, before anything is looked up: inside the folder as the game names it
            // (or as it really is: links, short names, /var -> /private/var)
            if (!p.startsWith(given) && !p.startsWith(root)) {
                return null;
            }
            if (!java.nio.file.Files.isRegularFile(p)) {
                return null;
            }
            java.nio.file.Path real = p.toRealPath(); // and no link out of the folder
            return real.startsWith(root) ? real.toString() : null;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** mod.info descriptions use the game's rich text: <LINE> and <BR> break lines, other tags go. */
    static String richTextToPlain(String text) {
        return text.replaceAll("(?i)\\s*<(LINE|BR)>\\s*", "\n").replaceAll("<[^<>]{1,40}>", "").strip();
    }

    @Override
    public Details workshopDetails(long workshopId) {
        Details known = workshopDetails.get(workshopId);
        if (known != null) {
            return known;
        }
        Details result = Details.NONE;
        try {
            var page = workshopPage.fetch(workshopId);
            if (page.isPresent()) {
                String image = null;
                try {
                    image = workshopPage.preview(page.get(), cacheDir().resolve("jumpstart-cache").resolve("workshop"))
                            .map(p -> p.toAbsolutePath().toString()).orElse(null);
                } catch (IOException | RuntimeException e) {
                    System.out.println("[Jumpstart] preview of " + workshopId + ": " + e.getMessage());
                }
                result = new Details(page.get().description(), image);
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[Jumpstart] Workshop page of " + workshopId + ": " + e.getMessage());
            return Details.NONE; // not kept: tried again next time
        }
        workshopDetails.put(workshopId, result);
        return result;
    }

    /** Leftover folders of uninstalled mods the game still held open (see {@link PendingRemoval}). */
    static Path pendingRemovalFile() {
        return cacheDir().resolve("jumpstart").resolve("pending-removal.txt");
    }

    /** At game start: removes what was left of mods uninstalled while the game held their files. */
    static void removeLeftovers() {
        PendingRemoval.cleanUp(pendingRemovalFile(), cacheDir().resolve("mods"));
    }

    /** The game's data folder (Zomboid, or what -cachedir says). */
    private static java.nio.file.Path cacheDir() {
        try {
            Object fs = Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
            return java.nio.file.Path.of((String) fs.getClass().getMethod("getCacheDir").invoke(fs));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return java.nio.file.Path.of(System.getProperty("user.home"), "Zomboid");
        }
    }

    /** The Workshop item of a mod folder (.../108600/ITEM/mods/MOD), 0 for a local mod. */
    static long workshopIdOf(String dir) {
        String[] parts = dir.replace('\\', '/').split("/");
        for (int i = 0; i + 2 < parts.length; i++) {
            if (parts[i].equals("108600") && parts[i + 2].equals("mods")) {
                try {
                    return Long.parseLong(parts[i + 1]);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /** A mod folder (with mod.info) as PZ reads it, with its Java part if it has one. */
    private FolderMod readMod(String dir) {
        try {
            Object info = Class.forName("zombie.gameStates.ChooseGameInfo").getMethod("readModInfo", String.class).invoke(null, dir);
            if (info == null) {
                return null;
            }
            String id = (String) info.getClass().getMethod("getId").invoke(info);
            if (id == null) {
                return null;
            }
            String name = (String) info.getClass().getMethod("getName").invoke(info);
            name = name == null || name.isBlank() ? id : name;
            String common = (String) info.getClass().getMethod("getCommonDir").invoke(info);
            String version = (String) info.getClass().getMethod("getVersionDir").invoke(info);
            // either folder may be missing (Staircast has no common folder); ZombieBuddy looks in both
            String java = common == null && version == null ? null : zombieBuddy.javaModOf(common, version);
            int tab = java == null ? -1 : java.indexOf('\t');
            ModEntry entry = tab < 0 ? null : new ModEntry(id, name, Path.of(java.substring(0, tab)), java.substring(tab + 1));
            return new FolderMod(id, name, entry);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The folder of a mod the game knows (Workshop or local), or null. */
    String modDirOf(String id) {
        String dir = downloadedModDirs.get(id);
        if (dir != null) {
            return dir;
        }
        try {
            Object info = Class.forName("zombie.gameStates.ChooseGameInfo").getMethod("getAvailableModDetails", String.class).invoke(null, id);
            return info == null ? null : (String) info.getClass().getMethod("getDir").invoke(info);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Tells the game where a mod is (a folder it has not listed yet, for example a fresh download). */
    void registerModDir(String id, String dir) {
        downloadedModDirs.put(id, dir);
        try {
            Object fs = Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
            fs.getClass().getMethod("setModIdToDir", String.class, String.class).invoke(fs, id, dir);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot register " + id + ": " + e);
        }
    }

    /** A mod by id with the jar a client loads (also on a server), or null. */
    FolderMod clientModById(String id) {
        try {
            Object info = Class.forName("zombie.gameStates.ChooseGameInfo").getMethod("getAvailableModDetails", String.class).invoke(null, id);
            if (info == null) {
                return null;
            }
            String name = (String) info.getClass().getMethod("getName").invoke(info);
            String common = (String) info.getClass().getMethod("getCommonDir").invoke(info);
            String version = (String) info.getClass().getMethod("getVersionDir").invoke(info);
            String java = common == null && version == null ? null : zombieBuddy.clientJavaModOf(common, version);
            int tab = java == null ? -1 : java.indexOf('\t');
            ModEntry entry = tab < 0 ? null : new ModEntry(id, name == null || name.isBlank() ? id : name,
                    Path.of(java.substring(0, tab)), java.substring(tab + 1));
            return new FolderMod(id, name, entry);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public String stateOf(String modId) {
        String state = zombieBuddy.stateOf(modId);
        return state == null ? "not loaded" : state;
    }

    @Override
    public BiteCheckGate.Check check(Path jar) throws IOException {
        return installer.gate().check(jar);
    }

    @Override
    public LiveInstaller.Outcome install(ModEntry mod, LiveInstaller.ConsentSource player) {
        return loaded(mod, installer.install(mod.jar(), mod.javaPackage(), mod.id(), UsageSharing.Jar.workshopIdOf(mod.jar()), player));
    }

    @Override
    public LiveInstaller.Outcome update(ModEntry mod, LiveInstaller.ConsentSource player) {
        return loaded(mod, installer.update(mod.jar(), mod.javaPackage(), mod.id(), UsageSharing.Jar.workshopIdOf(mod.jar()), player));
    }

    /** The hash of each Java mod's running version (see {@link Actions#loadedHash}). */
    private final Map<String, String> loadedHashes = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public String loadedHash(ModEntry mod) {
        return loadedHashes.computeIfAbsent(mod.id(), id -> Actions.super.loadedHash(mod));
    }

    private LiveInstaller.Outcome loaded(ModEntry mod, LiveInstaller.Outcome outcome) {
        if (outcome.kind() == LiveInstaller.Kind.DONE) {
            loadedHashes.put(mod.id(), Actions.super.loadedHash(mod));
        }
        return outcome;
    }

    @Override
    public String off(String modId) {
        String r = zombieBuddy.off(modId);
        return r == null ? "FAILED this ZombieBuddy cannot switch mods" : r;
    }

    @Override
    public String on(String modId) {
        String r = zombieBuddy.on(modId);
        return r == null ? "FAILED this ZombieBuddy cannot switch mods" : r;
    }

    @Override
    public String guardLastSeq() {
        return zombieBuddy.guardLastSeq();
    }

    @Override
    public String guardEvents(String sinceSeq) {
        String r = zombieBuddy.guardEvents(sinceSeq);
        return r == null ? "" : r;
    }

    @Override
    public String guardStatus(String modId) {
        String r = zombieBuddy.guardStatus(modId);
        return r == null ? "" : r;
    }

    @Override
    public String refreshIndex() {
        installer.gate().indexSource().refresh();
        return String.join("; ", installer.gate().indexSource().notes());
    }

    /**
     * Server: seconds players have to get ready for a change for everybody: -Djumpstart.countdown,
     * else {@code server_countdown_seconds} in BiteCheck's config.toml, else null (the default).
     */
    String serverCountdown() {
        String property = System.getProperty("jumpstart.countdown");
        if (property != null && !property.isBlank()) {
            return property;
        }
        try {
            Path file = biteCheckDir.resolve("config.toml");
            if (Files.isRegularFile(file)) {
                Object value = io.github.bitecheckpz.core.Toml.parse(file).get("server_countdown_seconds");
                return value == null ? null : String.valueOf(value);
            }
        } catch (IOException | io.github.bitecheckpz.core.Toml.TomlException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot read the countdown: " + e);
        }
        return null;
    }

    // --- marketplace ---------------------------------------------------------------------------------

    /** -Djumpstart.cloudUrl, else cloud_url in BiteCheck's config.toml, else BiteCheck's default. */
    Optional<URI> cloudUrl() {
        String property = System.getProperty("jumpstart.cloudUrl");
        if (property != null && !property.isBlank()) {
            return Optional.of(URI.create(property.strip()));
        }
        try {
            return UsageSharing.url(Config.load(biteCheckDir.resolve("config.toml")));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public LibraryClient.Page search(LibraryClient.Kind kind, String query, String cursor) throws IOException {
        URI url = cloudUrl().orElseThrow(() -> new IOException("no Jumpstart cloud set up yet (cloud_url)"));
        return new LibraryClient(url).search(kind, query, LibraryClient.Sort.POPULAR, cursor);
    }

    @Override
    public Workshop workshop() {
        return workshop;
    }

    @Override
    public List<FolderMod> modsInFolder(String itemFolder) {
        List<FolderMod> mods = new ArrayList<>();
        Path dir = Path.of(itemFolder, "mods");
        if (!Files.isDirectory(dir)) {
            return mods;
        }
        resetModFolders(); // a fresh download: the game lists its mod folders again
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.sorted().toList()) {
                FolderMod mod = Files.isDirectory(child) ? readMod(child.toString()) : null;
                if (mod != null) {
                    mods.add(mod);
                    downloadedModDirs.put(mod.id(), child.toString());
                }
            }
        } catch (IOException e) {
            System.out.println("[Jumpstart] cannot list " + dir + ": " + e);
        }
        return mods;
    }

    @Override
    public String activate(String modId) {
        return setActive(modId, true);
    }

    @Override
    public String deactivate(String modId) {
        return setActive(modId, false);
    }

    /** In a game: the running save's list and its mods.txt; in the main menu: the main menu's list. */
    private String setActive(String modId, boolean on) {
        try {
            Class<?> activeMods = Class.forName("zombie.modding.ActiveMods");
            if (luaGame.inGame()) {
                Object mods = activeMods.getMethod("getById", String.class).invoke(null, "currentGame");
                mods.getClass().getMethod("setModActive", String.class, boolean.class).invoke(mods, modId, on);
                Object fs = Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
                String file = (String) fs.getClass().getMethod("getFileNameInCurrentSave", String.class).invoke(fs, "mods.txt");
                Class<?> modsFile = Class.forName("zombie.modding.ActiveModsFile");
                Object writer = modsFile.getConstructor().newInstance();
                modsFile.getMethod("write", String.class, activeMods).invoke(writer, file, mods);
                return null;
            }
            Object mods = activeMods.getMethod("getById", String.class).invoke(null, "default");
            mods.getClass().getMethod("setModActive", String.class, boolean.class).invoke(mods, modId, on);
            Class.forName("zombie.Lua.LuaManager$GlobalObject").getMethod("saveModsFile").invoke(null);
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "cannot " + (on ? "enable " : "disable ") + modId + ": " + e;
        }
    }

    /** Whether the mod is in the list that counts now (the running save's, else the main menu's). */
    private boolean isEnabled(String modId) {
        try {
            Object mods = Class.forName("zombie.modding.ActiveMods").getMethod("getById", String.class)
                    .invoke(null, luaGame.inGame() ? "currentGame" : "default");
            return Boolean.TRUE.equals(mods.getClass().getMethod("isModActive", String.class).invoke(mods, modId));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String luaState(String modId) {
        boolean enabled = isEnabled(modId);
        if (!luaGame.inGame()) {
            return enabled ? "on" : "off"; // the main menu: what the next load of a save gets
        }
        boolean loaded = luaGame.isActive(modId);
        ModJournal journal = journals.get(modId);
        boolean parked = journal != null && journal.parked();
        if (parked || (loaded && !enabled)) {
            if (enabled) {
                return "on, next load";
            }
            // off: completely, if its Lua was taken back in full and nothing waits for the next load
            boolean complete = parked && journal.leftovers.isEmpty() && !journal.restartLeft;
            return complete ? "off" : "off, next load";
        }
        if (loaded) {
            return "on";
        }
        return enabled ? "on, next load" : "off";
    }

    @Override
    public LuaLive.Result loadLua(String modId) {
        return loadLua(modId, false);
    }

    @Override
    public LuaLive.Result loadLuaForServer(String modId) {
        return loadLua(modId, true);
    }

    private LuaLive.Result loadLua(String modId, boolean forServer) {
        ModJournal journal = journals.get(modId);
        if (journal != null && journal.parked()) {
            luaGame.giveBack(journal.parkedHandlers);
            journal.parkedHandlers = null;
            LuaJournal.Outcome redo = LuaJournal.redo(luaGame, journal.changes);
            System.out.println("[Jumpstart] Lua of " + modId + " back: " + redo.done() + " globals"
                    + (redo.kept().isEmpty() ? "" : "; changed meanwhile, left alone: " + redo.kept()));
            return LuaLive.Result.of(LuaLive.Status.RESUMED);
        }
        String dir = downloadedModDirs.get(modId);
        if (luaGame.inGame() && !luaGame.multiplayer() && !luaGame.isActive(modId)) {
            // tables kept aside when it was uninstalled come back first: its OnInitGlobalModData
            // handler then finds them instead of making new ones
            int back = ModDataJournal.restore(modId);
            if (back > 0) {
                System.out.println("[Jumpstart] " + modId + ": " + back + " ModData tables back in the save");
            }
        }
        LuaLive.Result result = LuaLive.load(luaGame, modId, dir, forServer);
        if (result.status() == LuaLive.Status.LOADED) {
            journals.computeIfAbsent(modId, k -> new ModJournal()).changes = result.changes();
        }
        System.out.println("[Jumpstart] Lua of " + modId + ": " + result);
        return result;
    }

    @Override
    public int unloadLua(String modId) {
        return unloadLua(modId, false);
    }

    @Override
    public int unloadLuaForServer(String modId) {
        return unloadLua(modId, true);
    }

    /**
     * Stops a mod's Lua: its event handlers are parked, what it changed in Lua's globals goes back
     * (only where nothing changed it again since), and windows of classes it added close. A mod that
     * came with the save has no record from before it loaded: its functions are only named.
     */
    private int unloadLua(String modId, boolean forServer) {
        ModJournal journal = journals.computeIfAbsent(modId, k -> new ModJournal());
        if (!luaGame.inGame() || (luaGame.multiplayer() && !forServer) || !luaGame.isActive(modId) || journal.parked()) {
            return 0;
        }
        List<String> dirs = luaGame.modDirs(modId);
        java.util.function.Predicate<String> modFile = file -> file != null && dirs.stream().anyMatch(file::startsWith);
        boolean fromStart = journal.changes.isEmpty();
        if (fromStart) {
            // it came with the save: what its Lua changed while the game loaded it (LuaStartJournal)
            journal.changes = LuaStartJournal.changesOf(luaGame, modFile);
            journal.restartLeft = !LuaLive.needsRestart(filesIn(luaGame.modFolders(modId))).isEmpty();
        }
        Map<String, List<Object>> taken = luaGame.takeHandlers(dirs);
        journal.parkedHandlers = taken;
        int windows = luaGame.closeWindows(journal.addedTables(luaGame));
        LuaJournal.Outcome undo = LuaJournal.undo(luaGame, journal.changes);
        if (touchesHotbar(journal.changes, luaTexts(luaGame.modFolders(modId)))) {
            boolean rebuilt = luaGame.rebuildHotbars();
            System.out.println("[Jumpstart] " + modId + " changed the hotbar: " + (rebuilt ? "built again" : "could not be built again"));
        }
        if (!undo.kept().isEmpty()) {
            journal.leftovers = "kept:" + undo.kept().size() + ":" + LuaJournal.shortList(undo.kept(), 5);
        } else if (fromStart) {
            // what the record does not have (deeper in tables, or set outside the load) is only named
            List<String> stays = LuaJournal.functionsFrom(luaGame, modFile);
            journal.leftovers = stays.isEmpty() ? "" : "start:" + stays.size() + ":" + LuaJournal.shortList(stays, 5);
        } else {
            journal.leftovers = "";
        }
        int count = taken.values().stream().mapToInt(List::size).sum();
        System.out.println("[Jumpstart] Lua of " + modId + " stopped: " + count + " event handlers " + taken.keySet()
                + ", " + undo.done() + " globals back, " + windows + " windows closed"
                + (journal.leftovers.isEmpty() ? "" : ", " + journal.leftovers));
        return count;
    }

    /**
     * Whether a mod changed the player's hotbar: its recorded changes name ISHotbar, or its Lua
     * replaces the hotbar object (playerHotbar).
     */
    static boolean touchesHotbar(List<LuaJournal.Change> changes, List<String> luaTexts) {
        for (LuaJournal.Change c : changes) {
            if (c.path() != null && (c.path().startsWith("ISHotbar") || c.path().equals("getPlayerHotbar"))) {
                return true;
            }
        }
        return luaTexts.stream().anyMatch(t -> t.contains("ISHotbar") || t.contains("playerHotbar"));
    }

    /** The text of the Lua files in a mod's folders (unreadable ones are left out). */
    static List<String> luaTexts(List<java.io.File> folders) {
        List<String> texts = new ArrayList<>();
        for (java.io.File folder : folders) {
            Path root = folder.toPath();
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, 12)) {
                walk.filter(f -> f.toString().toLowerCase(Locale.ROOT).endsWith(".lua")).forEach(f -> {
                    try {
                        texts.add(Files.readString(f, java.nio.charset.StandardCharsets.ISO_8859_1));
                    } catch (IOException | RuntimeException e) {
                        // unreadable: not counted
                    }
                });
            } catch (IOException | RuntimeException e) {
                // unreadable folder
            }
        }
        return texts;
    }

    /** The files in a mod's folders, relative (lower case, '/'), as {@link LuaLive#needsRestart} reads them. */
    static List<String> filesIn(List<java.io.File> folders) {
        List<String> files = new ArrayList<>();
        for (java.io.File folder : folders) {
            Path root = folder.toPath();
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, 12)) {
                walk.filter(Files::isRegularFile).forEach(f ->
                        files.add(root.relativize(f).toString().replace('\\', '/').toLowerCase(Locale.ROOT)));
            } catch (IOException | RuntimeException e) {
                // unreadable: counted as nothing left
            }
        }
        return files;
    }

    @Override
    public String luaLeftovers(String modId) {
        ModJournal journal = journals.get(modId);
        return journal == null ? "" : journal.leftovers;
    }

    @Override
    public String uninstall(String modId) {
        String dir = downloadedModDirs.get(modId);
        if (dir == null) {
            installedMods(); // fills the folders
            dir = downloadedModDirs.get(modId);
        }
        if (dir == null) {
            return "cannot find the folder of " + modId;
        }
        if ("on".equals(stateOf(modId))) {
            off(modId);
        }
        String forgot = zombieBuddy.forget(modId); // the fork drops its record (Lua exposures, callbacks)
        if (forgot != null && !forgot.startsWith("OK")) {
            System.out.println("[Jumpstart] ZombieBuddy could not forget " + modId + ": " + forgot);
        }
        List<String> dirs = luaGame.modDirs(modId); // before the folder goes
        unloadLua(modId);
        // what the mod left outside its folder: its ModData tables leave the save (back on reinstall),
        // files it created go to the archive; files that existed before stay where they are
        int tables = luaGame.inGame() && !luaGame.multiplayer() ? ModDataJournal.stash(modId, dirs) : 0;
        int files = FileJournal.archive(modId, dirs);
        if (tables + files > 0) {
            System.out.println("[Jumpstart] " + modId + ": " + tables + " ModData tables kept aside, " + files + " files archived");
        }
        String problem = setActive(modId, false);
        if (problem != null) {
            return problem;
        }
        try {
            // also out of the main menu's list when this ran in a game
            Object mods = Class.forName("zombie.modding.ActiveMods").getMethod("getById", String.class).invoke(null, "default");
            mods.getClass().getMethod("setModActive", String.class, boolean.class).invoke(mods, modId, false);
            Class.forName("zombie.Lua.LuaManager$GlobalObject").getMethod("saveModsFile").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot update the main menu's mod list: " + e);
        }
        long item = workshopIdOf(dir);
        if (item > 0) {
            List<String> sharing = otherModsOfItem(modId, item);
            if (!sharing.isEmpty()) {
                // ending the subscription would take these away too: the item stays
                System.out.println("[Jumpstart] uninstalled " + modId + "; Workshop item " + item + " stays for " + sharing);
                return "OK shared " + String.join(", ", sharing);
            }
            if (workshop().unsubscribe(item)) {
                System.out.println("[Jumpstart] uninstalled " + modId + "; unsubscribed from Workshop item " + item);
                return "OK unsubscribed " + item;
            }
            try {
                Class.forName("zombie.Lua.LuaManager$GlobalObject").getMethod("activateSteamOverlayToWorkshopItem", String.class)
                        .invoke(null, Long.toString(item));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "OK workshop " + item + " (open https://steamcommunity.com/sharedfiles/filedetails/?id=" + item + " to unsubscribe)";
            }
            System.out.println("[Jumpstart] uninstalled " + modId + "; Steam page of item " + item + " opened to unsubscribe");
            return "OK workshop " + item;
        }
        try {
            Path folder = Path.of(dir);
            Path target = folder.getParent().resolveSibling("mods-uninstalled")
                    .resolve(folder.getFileName() + "-" + LocalDate.now());
            String rest = PendingRemoval.moveOut(folder, target, pendingRemovalFile());
            downloadedModDirs.remove(modId);
            resetModFolders();
            System.out.println("[Jumpstart] uninstalled " + modId + "; folder moved to " + target
                    + (rest == null ? "" : " (" + rest + ")"));
            return "OK local " + target;
        } catch (IOException | RuntimeException e) {
            return "cannot move " + dir + ": " + e;
        }
    }

    /** Other mods of the same Workshop item that are still on (in a mod list). */
    private List<String> otherModsOfItem(String modId, long item) {
        List<String> others = new ArrayList<>();
        for (Map.Entry<String, String> e : downloadedModDirs.entrySet()) {
            if (!e.getKey().equals(modId) && workshopIdOf(e.getValue()) == item && luaGame.isActive(e.getKey())) {
                others.add(e.getKey());
            }
        }
        return others;
    }

    private static void resetModFolders() {
        try {
            Object fs = Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
            fs.getClass().getMethod("resetModFolders").invoke(fs);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot refresh the mod folders: " + e);
        }
    }

    // --- save snapshots ------------------------------------------------------------------------------

    private final SaveSnapshots saveSnapshots = new SaveSnapshots(Main.cacheDir().resolve("jumpstart"));
    private volatile String snapshotState;
    /** The save the last snapshot was taken of, and when: a new one only after {@link #SNAPSHOT_AGAIN_MS}. */
    private volatile Path snapshotOf;
    private volatile long snapshotAt;
    static final long SNAPSHOT_AGAIN_MS = 10 * 60 * 1000;

    /**
     * Saves the game on the main thread (the secure screen is open, so the game is paused), then
     * waits in the background until the chunk saver is done and zips the save. Switching waits for
     * it (the screen blocks its action keys meanwhile).
     */
    @Override
    public void beginSnapshot() {
        if (!luaGame.inGame() || luaGame.multiplayer() || luaGame.isServer() || "running".equals(snapshotState)) {
            return;
        }
        Path save = ModDataJournal.saveDir();
        if (save == null || (save.equals(snapshotOf) && System.currentTimeMillis() - snapshotAt < SNAPSHOT_AGAIN_MS)) {
            return; // a recent one of this save is good enough
        }
        try {
            if (!saveSnapshots.wanted(save)) {
                snapshotState = "skipped";
                snapshotOf = save;
                snapshotAt = System.currentTimeMillis();
                return;
            }
            snapshotState = "running";
            Class.forName("zombie.GameWindow").getMethod("save", boolean.class).invoke(null, true);
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            snapshotState = "failed " + e;
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                waitForChunkSaver();
                Path savesRoot = Path.of((String) fileSystemCall("getSaveDir"));
                Path zip = saveSnapshots.take(save, savesRoot);
                snapshotOf = save;
                snapshotAt = System.currentTimeMillis();
                snapshotState = "done";
                System.out.println("[Jumpstart] snapshot of the save: " + zip);
            } catch (Exception e) {
                snapshotState = "failed " + e;
                System.out.println("[Jumpstart] no snapshot of the save: " + e);
            }
        }, "Jumpstart snapshot");
        worker.setDaemon(true);
        worker.start();
    }

    /** The game writes changed map chunks on its own thread: the copy waits until that queue is empty. */
    private static void waitForChunkSaver() throws ReflectiveOperationException, InterruptedException {
        Class<?> worker = Class.forName("zombie.iso.ChunkSaveWorker");
        Object instance = worker.getField("instance").get(null);
        java.util.Collection<?> queue = (java.util.Collection<?>) worker.getField("toSaveQueue").get(instance);
        java.lang.reflect.Field saving = worker.getField("saving");
        long until = System.currentTimeMillis() + 120_000;
        while ((!queue.isEmpty() || saving.getBoolean(instance)) && System.currentTimeMillis() < until) {
            Thread.sleep(100);
        }
    }

    private static Object fileSystemCall(String method) throws ReflectiveOperationException {
        Object fs = Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
        return fs.getClass().getMethod(method).invoke(fs);
    }

    @Override
    public String snapshotState() {
        return snapshotState;
    }

    @Override
    public List<String> snapshots() {
        return saveSnapshots.list().stream().map(SaveSnapshots.Snapshot::label).toList();
    }

    @Override
    public String restoreSnapshot(int index) {
        if (luaGame.inGame()) {
            return "only from the main menu";
        }
        List<SaveSnapshots.Snapshot> all = saveSnapshots.list();
        if (index < 0 || index >= all.size()) {
            return "no such snapshot";
        }
        try {
            Path replaced = saveSnapshots.restore(all.get(index), Path.of((String) fileSystemCall("getSaveDir")));
            snapshotOf = null;
            return "OK" + (replaced == null ? "" : " " + replaced);
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            return "cannot restore: " + e;
        }
    }

    // --- voluntary sharing ---------------------------------------------------------------------------

    @Override
    public boolean sharingEnabled() {
        try {
            return UsageSharing.load(biteCheckDir).enabled();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void setSharing(boolean enabled) throws IOException {
        UsageSharing.load(biteCheckDir).setEnabled(enabled);
    }

    @Override
    public List<UsageSharing.Jar> unverifiedJars() {
        List<UsageSharing.Jar> jars = new ArrayList<>();
        for (ModEntry mod : javaMods()) {
            try {
                BiteCheckGate.Check c = check(mod.jar());
                if (c.status() == Verifier.Status.UNVERIFIED) {
                    jars.add(new UsageSharing.Jar(c.sha256(), UsageSharing.Jar.workshopIdOf(mod.jar()), mod.id(), Files.size(mod.jar())));
                }
            } catch (IOException | RuntimeException e) {
                // a jar that cannot be read is not reported
            }
        }
        return jars;
    }

    @Override
    public String shareIfDue(List<UsageSharing.Jar> jars) {
        try {
            UsageSharing sharing = UsageSharing.load(biteCheckDir);
            return sharing.sendIfDue(cloudUrl().orElse(null), CLIENT, jars, LocalDate.now(), UsageSharing.httpSender());
        } catch (IOException | RuntimeException e) {
            return "not sent: " + e.getMessage();
        }
    }

    /** {@link Workshop} on PZ's zombie.core.znet.SteamWorkshop, by reflection. */
    private static final class SteamWorkshopAccess implements Workshop {
        private Object callback;

        private Object instance() throws ReflectiveOperationException {
            return Class.forName("zombie.core.znet.SteamWorkshop").getField("instance").get(null);
        }

        @Override
        public String unavailableReason() {
            try {
                boolean steam = (Boolean) Class.forName("zombie.core.znet.SteamUtils").getMethod("isSteamModeEnabled").invoke(null);
                if (!steam || instance() == null) {
                    return "the game runs without Steam";
                }
                return null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "no Steam Workshop access: " + e;
            }
        }

        @Override
        public long state(long itemId) {
            try {
                Object ws = instance();
                return (Long) ws.getClass().getMethod("GetItemState", long.class).invoke(ws, itemId);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return 0;
            }
        }

        private Object callback(Class<?> cb) {
            if (callback == null) {
                // Steam reports back through this; Jumpstart polls the item state instead
                callback = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[] {cb}, (proxy, method, args) ->
                        method.getName().equals("hashCode") ? System.identityHashCode(proxy)
                                : method.getName().equals("equals") ? proxy == args[0]
                                : method.getName().equals("toString") ? "Jumpstart" : null);
            }
            return callback;
        }

        @Override
        public long[] progress(long itemId) {
            try {
                Object ws = instance();
                long[] info = new long[2];
                Boolean ok = (Boolean) ws.getClass().getMethod("GetItemDownloadInfo", long.class, long[].class).invoke(ws, itemId, info);
                return Boolean.TRUE.equals(ok) && info[1] > 0 ? info : null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public boolean download(long itemId) {
            try {
                Object ws = instance();
                Class<?> cb = Class.forName("zombie.core.znet.ISteamWorkshopCallback");
                Method download = ws.getClass().getMethod("DownloadItem", long.class, boolean.class, cb);
                return (Boolean) download.invoke(ws, itemId, true, callback(cb));
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.out.println("[Jumpstart] Steam download failed: " + e);
                return false;
            }
        }

        @Override
        public boolean subscribeAndDownload(long itemId) {
            try {
                Object ws = instance();
                Class<?> cb = Class.forName("zombie.core.znet.ISteamWorkshopCallback");
                callback(cb);
                Method subscribe = ws.getClass().getMethod("SubscribeItem", long.class, cb);
                Method download = ws.getClass().getMethod("DownloadItem", long.class, boolean.class, cb);
                boolean ok = (Boolean) subscribe.invoke(ws, itemId, callback);
                return (Boolean) download.invoke(ws, itemId, true, callback) || ok;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.out.println("[Jumpstart] Steam subscribe failed: " + e);
                return false;
            }
        }

        @Override
        public String installFolder(long itemId) {
            try {
                Object ws = instance();
                return (String) ws.getClass().getMethod("GetItemInstallFolder", long.class).invoke(ws, itemId);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        /**
         * The game's own Steam bridge can subscribe but not unsubscribe, so this calls Steam's flat C
         * API (ISteamUGC::UnsubscribeItem in steam_api) through JNA, which the game ships. Steam
         * answers later; the files go when the game no longer uses them.
         */
        @Override
        public boolean unsubscribe(long itemId) {
            try {
                Class<?> nativeLibrary = Class.forName("com.sun.jna.NativeLibrary");
                Class<?> function = Class.forName("com.sun.jna.Function");
                Object lib = null;
                for (String name : List.of("steam_api64", "steam_api")) {
                    try {
                        lib = nativeLibrary.getMethod("getInstance", String.class).invoke(null, name);
                        break;
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        // not this name on this system
                    }
                }
                if (lib == null) {
                    return false;
                }
                Method getFunction = nativeLibrary.getMethod("getFunction", String.class);
                Object ugc = null;
                for (int v = 30; v >= 14 && ugc == null; v--) {
                    try {
                        Object accessor = getFunction.invoke(lib, String.format(Locale.ROOT, "SteamAPI_SteamUGC_v%03d", v));
                        ugc = function.getMethod("invokePointer", Object[].class).invoke(accessor, (Object) new Object[0]);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        // this interface version is not in this steam_api
                    }
                }
                if (ugc == null) {
                    System.out.println("[Jumpstart] Steam's UGC interface is not available");
                    return false;
                }
                Object call = getFunction.invoke(lib, "SteamAPI_ISteamUGC_UnsubscribeItem");
                long handle = (Long) function.getMethod("invokeLong", Object[].class).invoke(call, (Object) new Object[] {ugc, itemId});
                System.out.println("[Jumpstart] Steam: unsubscribe from item " + itemId + (handle != 0 ? " sent" : " refused"));
                return handle != 0;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                System.out.println("[Jumpstart] Steam unsubscribe failed: " + e);
                return false;
            }
        }
    }
}
