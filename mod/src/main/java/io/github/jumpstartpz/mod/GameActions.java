package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.ZombieBuddyFork;
import io.github.jumpstartpz.core.gate.BiteCheckGate;

/** {@link Actions} in the game: BiteCheck's gate, the Jumpstart fork of ZombieBuddy, PZ's mod folders. */
final class GameActions implements Actions {
    private static final Set<String> SKIP = Set.of("ZombieBuddy", "Jumpstart");

    private final LiveInstaller installer;
    private final ZombieBuddyFork zombieBuddy;

    GameActions(LiveInstaller installer, ZombieBuddyFork zombieBuddy) {
        this.installer = installer;
        this.zombieBuddy = zombieBuddy;
    }

    @Override
    public List<ModEntry> javaMods() {
        Map<String, ModEntry> mods = new LinkedHashMap<>();
        try {
            Object fs = Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
            List<String> dirs = new ArrayList<>();
            fs.getClass().getMethod("getAllModFolders", List.class).invoke(fs, dirs);
            Method readModInfo = Class.forName("zombie.gameStates.ChooseGameInfo").getMethod("readModInfo", String.class);
            for (String dir : dirs) {
                Object info;
                try {
                    info = readModInfo.invoke(null, dir);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    continue;
                }
                if (info == null) {
                    continue;
                }
                String id = (String) info.getClass().getMethod("getId").invoke(info);
                if (id == null || SKIP.contains(id) || mods.containsKey(id)) {
                    continue;
                }
                String common = (String) info.getClass().getMethod("getCommonDir").invoke(info);
                String version = (String) info.getClass().getMethod("getVersionDir").invoke(info);
                String java = common == null || version == null ? null : zombieBuddy.javaModOf(common, version);
                int tab = java == null ? -1 : java.indexOf('\t');
                if (tab < 0) {
                    continue;
                }
                String name = (String) info.getClass().getMethod("getName").invoke(info);
                mods.put(id, new ModEntry(id, name == null || name.isBlank() ? id : name,
                        Path.of(java.substring(0, tab)), java.substring(tab + 1)));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot list the mod folders: " + e);
        }
        List<ModEntry> list = new ArrayList<>(mods.values());
        list.sort(Comparator.comparing(m -> m.name().toLowerCase(Locale.ROOT)));
        return list;
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
        return installer.install(mod.jar(), mod.javaPackage(), mod.id(), null, player);
    }

    @Override
    public LiveInstaller.Outcome update(ModEntry mod, LiveInstaller.ConsentSource player) {
        return installer.update(mod.jar(), mod.javaPackage(), mod.id(), null, player);
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
}
