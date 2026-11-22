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
    private final LuaLive.Game luaGame = new ReflectiveLuaGame();
    /** Mod id to its folder, for mods found in downloaded Workshop items. */
    private final Map<String, String> downloadedModDirs = new java.util.concurrent.ConcurrentHashMap<>();

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
        return installer.install(mod.jar(), mod.javaPackage(), mod.id(), UsageSharing.Jar.workshopIdOf(mod.jar()), player);
    }

    @Override
    public LiveInstaller.Outcome update(ModEntry mod, LiveInstaller.ConsentSource player) {
        return installer.update(mod.jar(), mod.javaPackage(), mod.id(), UsageSharing.Jar.workshopIdOf(mod.jar()), player);
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
    public String refreshIndex() {
        installer.gate().indexSource().refresh();
        return String.join("; ", installer.gate().indexSource().notes());
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
        try {
            Class<?> activeMods = Class.forName("zombie.modding.ActiveMods");
            if (luaGame.inGame()) {
                // the running save's own list, written to its mods.txt like the game does when it loads
                Object mods = activeMods.getMethod("getById", String.class).invoke(null, "currentGame");
                mods.getClass().getMethod("setModActive", String.class, boolean.class).invoke(mods, modId, true);
                Object fs = Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
                String file = (String) fs.getClass().getMethod("getFileNameInCurrentSave", String.class).invoke(fs, "mods.txt");
                Class<?> modsFile = Class.forName("zombie.modding.ActiveModsFile");
                Object writer = modsFile.getConstructor().newInstance();
                modsFile.getMethod("write", String.class, activeMods).invoke(writer, file, mods);
                return null;
            }
            Object mods = activeMods.getMethod("getById", String.class).invoke(null, "default");
            mods.getClass().getMethod("setModActive", String.class, boolean.class).invoke(mods, modId, true);
            Class.forName("zombie.Lua.LuaManager$GlobalObject").getMethod("saveModsFile").invoke(null);
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "cannot enable " + modId + ": " + e;
        }
    }

    @Override
    public LuaLive.Result loadLua(String modId) {
        String dir = downloadedModDirs.get(modId);
        LuaLive.Result result = LuaLive.load(luaGame, modId, dir);
        System.out.println("[Jumpstart] Lua of " + modId + ": " + result);
        return result;
    }

    private static void resetModFolders() {
        try {
            Object fs = Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
            fs.getClass().getMethod("resetModFolders").invoke(fs);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot refresh the mod folders: " + e);
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

        @Override
        public boolean subscribeAndDownload(long itemId) {
            try {
                Object ws = instance();
                Class<?> cb = Class.forName("zombie.core.znet.ISteamWorkshopCallback");
                if (callback == null) {
                    // Steam reports back through this; Jumpstart polls the item state instead
                    callback = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[] {cb}, (proxy, method, args) ->
                            method.getName().equals("hashCode") ? System.identityHashCode(proxy)
                                    : method.getName().equals("equals") ? proxy == args[0]
                                    : method.getName().equals("toString") ? "Jumpstart" : null);
                }
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
    }
}
