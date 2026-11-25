package io.github.jumpstartpz.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.bitecheckpz.launcher.Platform;

/** Install and uninstall into a fake game, Zomboid folder and Steam, on Windows and on Linux. */
class SetupTest {
    private static final String JSON = "{\r\n\t\"vmArgs\": [\r\n\t\t\"-Xmx16g\"\r\n\t]\r\n}\r\n";

    @TempDir
    Path tmp;

    private final List<String> log = new ArrayList<>();
    private boolean steamRunning;

    private static void zip(Path file, String... entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String e : entries) {
                zip.putNextEntry(new ZipEntry(e));
                zip.write(e.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    /** The fork as Jumpstart before 0.2 put it into the game folder. */
    private static void oldFork(Path jar) throws IOException {
        zip(jar, "me/zed_0xff/zombie_buddy/Hotswap.class", "old fork");
    }

    /** A ZombieBuddy.jar of the player's own: upstream, without Hotswap. */
    private static void ownZombieBuddy(Path jar) throws IOException {
        zip(jar, "me/zed_0xff/zombie_buddy/ZombieBuddy.class");
    }

    private Payload payload() throws IOException {
        Path p = tmp.resolve("payload");
        zip(p.resolve("Jumpstart.jar"), "me/zed_0xff/zombie_buddy/Hotswap.class", "embedded-mod/io/github/jumpstartpz/mod/Main.class");
        Files.createDirectories(p.resolve("mods/ZombieBuddy/42"));
        Files.writeString(p.resolve("mods/ZombieBuddy/42/mod.info"), "name=ZombieBuddy (Jumpstart fork)\nid=ZombieBuddy\n");
        Files.writeString(p.resolve("mods/ZombieBuddy/" + Setup.FORK_MARKER), "fork");
        Files.writeString(p.resolve("VERSION"), "test-1\n");
        Files.writeString(p.resolve("jsNative.dll"), "loader");
        return Payload.of(p);
    }

    private GameLocation windows() throws IOException {
        Path game = tmp.resolve("Steam/steamapps/common/ProjectZomboid");
        Files.createDirectories(game);
        Files.writeString(game.resolve("projectzomboid.jar"), "game");
        Files.writeString(game.resolve("ProjectZomboid64.json"), JSON);
        assertEquals(Optional.of(game.toAbsolutePath().normalize()), GameLocation.javaDir(Platform.Os.WINDOWS, List.of(game)));
        return new GameLocation(Platform.Os.WINDOWS, game, tmp.resolve("Zomboid"), List.of(tmp.resolve("Steam")),
                List.of(tmp.resolve("Steam/steamapps/workshop/content/108600")));
    }

    private GameLocation linux(String launchOptions) throws IOException {
        Path game = tmp.resolve("Steam/steamapps/common/ProjectZomboid");
        Path java = game.resolve("projectzomboid");
        Files.createDirectories(java);
        Files.writeString(java.resolve("projectzomboid.jar"), "game");
        assertEquals(Optional.of(java.toAbsolutePath().normalize()), GameLocation.javaDir(Platform.Os.LINUX, List.of(game)));
        Path vdf = tmp.resolve("Steam/userdata/123/config/localconfig.vdf");
        Files.createDirectories(vdf.getParent());
        Files.writeString(vdf, "\"UserLocalConfigStore\"\n{\n\t\"Software\"\n\t{\n\t\t\"Valve\"\n\t\t{\n\t\t\t\"Steam\"\n\t\t\t{\n"
                + "\t\t\t\t\"apps\"\n\t\t\t\t{\n\t\t\t\t\t\"108600\"\n\t\t\t\t\t{\n\t\t\t\t\t\t\"LastPlayed\"\t\t\"1\"\n"
                + (launchOptions == null ? "" : "\t\t\t\t\t\t\"LaunchOptions\"\t\t\"" + LocalConfigVdf.escape(launchOptions) + "\"\n")
                + "\t\t\t\t\t}\n\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}\n", StandardCharsets.UTF_8);
        return new GameLocation(Platform.Os.LINUX, java, tmp.resolve("Zomboid"), List.of(tmp.resolve("Steam")),
                List.of(tmp.resolve("Steam/steamapps/workshop/content/108600")));
    }

    private Setup setup(GameLocation loc) {
        return new Setup(loc, new Texts(false), log::add, () -> steamRunning, Optional.empty());
    }

    /** What Jumpstart before 0.2 left: the fork as ZombieBuddy.jar and the mod folder Zomboid/mods/Jumpstart. */
    private void oldLayout(GameLocation loc) throws IOException {
        oldFork(loc.zombieBuddyJar());
        Path old = loc.modsDir().resolve("Jumpstart");
        Files.createDirectories(old.resolve("42/media/java"));
        Files.writeString(old.resolve("42/mod.info"), "id=Jumpstart\nmodversion=0.1.0\n");
        Files.writeString(old.resolve(Setup.VERSION_FILE), "test-0\n");
        Files.writeString(loc.modList(), "VERSION = 1,\n\nmods\n{\n    mod = ZombieBuddy,\n    mod = Jumpstart,\n}\n\nmaps\n{\n}\n");
    }

    @Test
    void windowsInstallAndUninstall() throws IOException {
        GameLocation loc = windows();
        Setup setup = setup(loc);
        assertFalse(setup.status().anything());

        setup.installFiles(payload());
        assertEquals(Setup.LaunchResult.NOT_NEEDED, setup.launchOptions(true));
        Setup.Status s = setup.status();
        assertTrue(s.installed(), s.toString());
        assertEquals(Optional.of("test-1"), s.jumpstartVersion(), "the version the installer put there");
        assertTrue(Setup.isFork(loc.jumpstartJar()));
        assertFalse(Files.exists(loc.zombieBuddyJar()), "one jar: Jumpstart.jar");
        String json = Files.readString(loc.launchJson());
        assertTrue(json.contains("\"-agentlib:jsNative=hotswap=true\",\r\n\t\t\"-Xmx16g\""), json);
        assertEquals("loader", Files.readString(loc.nativeLoader()), "the loader that starts Jumpstart.jar");
        assertFalse(Files.exists(loc.modsDir().resolve("ZombieBuddy/libs")), "the mod folder has no jar");
        assertTrue(Files.isRegularFile(loc.modsDir().resolve("ZombieBuddy/42/mod.info")));
        assertFalse(Files.exists(loc.modsDir().resolve("Jumpstart")));
        String list = Files.readString(loc.modList());
        assertTrue(list.contains("mod = ZombieBuddy,"), list);
        assertFalse(list.contains("Jumpstart"), list);
        assertFalse(Files.exists(loc.replacedAgents()), "nothing of the player's was replaced");

        setup.installFiles(payload()); // again: an update changes nothing else
        assertEquals(json, Files.readString(loc.launchJson()));
        assertFalse(Files.exists(loc.javaDir().resolve("ZombieBuddy.jar" + Setup.BACKUP)));

        setup.uninstallFiles();
        assertFalse(setup.status().anything());
        assertFalse(Files.exists(loc.jumpstartJar()));
        assertFalse(Files.exists(loc.nativeLoader()));
        assertFalse(Files.exists(loc.modsDir().resolve("ZombieBuddy")));
        assertEquals(JSON, Files.readString(loc.launchJson()));
        assertFalse(Files.readString(loc.modList()).contains("ZombieBuddy"));
    }

    @Test
    void windowsReplacesThePlayersZombieBuddyAndPutsItBackExactly() throws IOException {
        GameLocation loc = windows();
        ownZombieBuddy(loc.zombieBuddyJar());
        Files.writeString(loc.zombieBuddyJar().resolveSibling("ZombieBuddy.jar.new"), "waiting update");
        String original = JSON.replace("\"-Xmx16g\"", "\"-agentlib:zbNative=policy=deny-new\",\r\n\t\t\"-Xmx16g\"");
        Files.writeString(loc.launchJson(), original);
        Path own = loc.modsDir().resolve("ZombieBuddy");
        Files.createDirectories(own.resolve("42"));
        Files.writeString(own.resolve("42/mod.info"), "id=ZombieBuddy\n");
        Setup setup = setup(loc);

        setup.installFiles(payload());
        assertFalse(Files.exists(loc.zombieBuddyJar()), "only Jumpstart.jar in the game folder");
        assertFalse(Files.exists(loc.zombieBuddyJar().resolveSibling("ZombieBuddy.jar.new")));
        assertTrue(Files.isRegularFile(loc.javaDir().resolve("ZombieBuddy.jar" + Setup.BACKUP)));
        String json = Files.readString(loc.launchJson());
        assertFalse(json.contains("zbNative"), "two agents would load every mod twice");
        assertTrue(json.contains("\"-agentlib:jsNative=policy=deny-new,hotswap=true\""), "ZombieBuddy's options stay: " + json);
        assertEquals("-agentlib:zbNative=policy=deny-new\n", Files.readString(loc.replacedAgents()));
        assertTrue(setup.status().installed());
        assertTrue(Files.isRegularFile(loc.backupDir().resolve("ZombieBuddy/42/mod.info")));
        assertTrue(Files.exists(own.resolve(Setup.FORK_MARKER)));

        setup.installFiles(payload()); // an update keeps the record
        assertEquals("-agentlib:zbNative=policy=deny-new\n", Files.readString(loc.replacedAgents()));

        setup.uninstallFiles();
        assertEquals(original, Files.readString(loc.launchJson()));
        assertFalse(Files.exists(loc.replacedAgents()));
        assertTrue(Files.isRegularFile(loc.zombieBuddyJar()));
        assertFalse(Setup.isFork(loc.zombieBuddyJar()), "the player's ZombieBuddy.jar is back");
        assertEquals("waiting update", Files.readString(loc.zombieBuddyJar().resolveSibling("ZombieBuddy.jar.new")));
        assertFalse(Files.exists(loc.jumpstartJar()));
        assertFalse(Files.exists(own.resolve(Setup.FORK_MARKER)), "the player's mod folder is back");
        assertTrue(Files.readString(loc.modList()).contains("ZombieBuddy"), "ZombieBuddy stays on: the player has it");
    }

    @Test
    void windowsMovesAnInstallFromBefore02() throws IOException {
        GameLocation loc = windows();
        Files.createDirectories(loc.modsDir());
        oldLayout(loc);
        ownZombieBuddy(loc.javaDir().resolve("ZombieBuddy.jar" + Setup.BACKUP)); // kept by 0.1
        String agent = "-javaagent:" + loc.zombieBuddyJar().toAbsolutePath().toString().replace('\\', '/') + "=hotswap=true";
        Files.writeString(loc.launchJson(), JSON.replace("\"-Xmx16g\"", "\"" + agent + "\",\r\n\t\t\"-Xmx16g\""));
        Setup setup = setup(loc);
        assertTrue(setup.isInstalled(), "an update moves it to the new layout");
        assertEquals(Optional.of("test-0"), setup.installedVersion());
        assertTrue(setup.status().anything());

        setup.installFiles(payload(), false); // as the automatic update does it
        assertFalse(Files.exists(loc.zombieBuddyJar()), "the old fork is gone");
        assertFalse(Setup.isFork(loc.javaDir().resolve("ZombieBuddy.jar" + Setup.BACKUP)), "the player's own stays kept");
        String json = Files.readString(loc.launchJson());
        assertFalse(json.contains("ZombieBuddy.jar"), json);
        assertTrue(LaunchJson.hasJumpstart(json));
        assertFalse(Files.exists(loc.replacedAgents()), "0.1's own entry is not the player's");
        assertFalse(Files.exists(loc.modsDir().resolve("Jumpstart")));
        assertFalse(Files.readString(loc.modList()).contains("Jumpstart"), "its folder is gone, so is its entry");
        assertTrue(Files.readString(loc.modList()).contains("ZombieBuddy"));
        assertEquals(Optional.of("test-1"), setup.installedVersion());
        assertTrue(setup.status().installed());

        setup.uninstallFiles();
        assertEquals(JSON, Files.readString(loc.launchJson()));
        assertFalse(Setup.isFork(loc.zombieBuddyJar()), "the player's ZombieBuddy.jar is back");
        assertFalse(Files.exists(loc.jumpstartJar()));
    }

    @Test
    void windowsUninstallsAnInstallFromBefore02() throws IOException {
        GameLocation loc = windows();
        Files.createDirectories(loc.modsDir());
        oldLayout(loc);
        String agent = "-javaagent:" + loc.zombieBuddyJar().toAbsolutePath().toString().replace('\\', '/') + "=hotswap=true";
        Files.writeString(loc.launchJson(), JSON.replace("\"-Xmx16g\"", "\"" + agent + "\",\r\n\t\t\"-Xmx16g\""));
        Setup setup = setup(loc);
        setup.uninstallFiles();
        assertEquals(JSON, Files.readString(loc.launchJson()));
        assertFalse(Files.exists(loc.zombieBuddyJar()));
        assertFalse(Files.exists(loc.modsDir().resolve("Jumpstart")));
        assertFalse(setup.status().anything());
    }

    @Test
    void linuxSetsTheLaunchOptionsWhenSteamIsClosed() throws IOException {
        GameLocation loc = linux("-javaagent:ZombieBuddy.jar --");
        Setup setup = setup(loc);
        setup.installFiles(payload());
        assertFalse(setup.status().installed());

        steamRunning = true;
        assertEquals(Setup.LaunchResult.STEAM_RUNNING, setup.launchOptions(true));
        assertEquals("-javaagent:Jumpstart.jar=hotswap=true --", setup.suggestedLaunchOptions(true));
        assertFalse(setup.status().launchReady());

        steamRunning = false;
        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(true));
        Setup.Status s = setup.status();
        assertTrue(s.installed(), s.toString());
        assertEquals(Optional.of("-javaagent:Jumpstart.jar=hotswap=true --"), s.launchOptions());
        assertTrue(Files.exists(loc.localConfigs().get(0).resolveSibling("localconfig.vdf" + Setup.BACKUP)));
        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(true), "already set");

        // uninstall: no ZombieBuddy.jar before Jumpstart, so the whole option goes, then the jar
        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(false));
        setup.uninstallFiles();
        assertEquals(Optional.of(""), setup.status().launchOptions());
        assertFalse(Files.exists(loc.jumpstartJar()));
    }

    @Test
    void linuxPutsThePlayersZombieBuddyBack() throws IOException {
        GameLocation loc = linux("gamemoderun %command% -javaagent:ZombieBuddy.jar=policy=deny-new --");
        ownZombieBuddy(loc.zombieBuddyJar());
        Setup setup = setup(loc);
        setup.installFiles(payload());
        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(true));
        assertEquals(Optional.of("gamemoderun %command% -javaagent:Jumpstart.jar=policy=deny-new,hotswap=true --"),
                setup.status().launchOptions());
        assertFalse(Files.exists(loc.zombieBuddyJar()));

        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(false));
        setup.uninstallFiles();
        assertEquals(Optional.of("gamemoderun %command% -javaagent:ZombieBuddy.jar=policy=deny-new --"), setup.status().launchOptions());
        assertTrue(Files.isRegularFile(loc.zombieBuddyJar()));
        assertFalse(Files.exists(loc.jumpstartJar()));
    }

    @Test
    void linuxMovesAnInstallFromBefore02() throws IOException {
        GameLocation loc = linux("-javaagent:ZombieBuddy.jar=hotswap=true --");
        Files.createDirectories(loc.modsDir());
        oldLayout(loc);
        Setup setup = setup(loc);
        setup.installFiles(payload());
        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(true));
        assertEquals(Optional.of("-javaagent:Jumpstart.jar=hotswap=true --"), setup.status().launchOptions());
        assertFalse(Files.exists(loc.zombieBuddyJar()));
        assertTrue(setup.status().installed());
    }

    @Test
    void linuxUninstallsAnInstallFromBefore02() throws IOException {
        GameLocation loc = linux("-javaagent:ZombieBuddy.jar=hotswap=true --");
        Files.createDirectories(loc.modsDir());
        oldLayout(loc);
        Setup setup = setup(loc);
        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(false));
        setup.uninstallFiles();
        assertEquals(Optional.of(""), setup.status().launchOptions());
        assertFalse(Files.exists(loc.zombieBuddyJar()));
        assertFalse(setup.status().anything());
    }

    @Test
    void linuxKeepsTheJarWhileSteamStillStartsIt() throws IOException {
        GameLocation loc = linux(null);
        Setup setup = setup(loc);
        setup.installFiles(payload());
        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(true));
        assertTrue(setup.status().installed());

        steamRunning = true;
        assertEquals(Setup.LaunchResult.STEAM_RUNNING, setup.launchOptions(false));
        setup.uninstallFiles();
        assertTrue(Files.exists(loc.jumpstartJar()), "removing it would stop the game from starting");
        assertTrue(log.stream().anyMatch(l -> l.contains("Jumpstart.jar stays")), log.toString());
    }

    @Test
    void noSteamAccountWithTheGame() throws IOException {
        GameLocation loc = linux(null);
        Files.delete(loc.localConfigs().get(0));
        Setup setup = setup(loc);
        setup.installFiles(payload());
        assertEquals(Setup.LaunchResult.NO_STEAM_CONFIG, setup.launchOptions(true));
        assertEquals("-javaagent:Jumpstart.jar=hotswap=true --", setup.suggestedLaunchOptions(true));
    }

    private Path steamConfig(String launchOptions) throws IOException {
        Path vdf = tmp.resolve("Steam/userdata/123/config/localconfig.vdf");
        Files.createDirectories(vdf.getParent());
        Files.writeString(vdf, "\"UserLocalConfigStore\"\n{\n\t\"Software\"\n\t{\n\t\t\"Valve\"\n\t\t{\n\t\t\t\"Steam\"\n\t\t\t{\n"
                + "\t\t\t\t\"apps\"\n\t\t\t\t{\n\t\t\t\t\t\"108600\"\n\t\t\t\t\t{\n"
                + (launchOptions == null ? "" : "\t\t\t\t\t\t\"LaunchOptions\"\t\t\"" + LocalConfigVdf.escape(launchOptions) + "\"\n")
                + "\t\t\t\t\t}\n\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}\n", StandardCharsets.UTF_8);
        return vdf;
    }

    @Test
    void windowsWithAutomaticUpdatesKeepsBiteChecksWrapper() throws IOException {
        GameLocation loc = windows();
        String bitecheck = "\"C:\\Users\\me\\AppData\\Local\\BiteCheck\\BiteCheck.exe\" --wrap %command%";
        steamConfig(bitecheck + " -agentlib:zbNative --");
        String wrapper = "\"C:\\Users\\me\\AppData\\Local\\Jumpstart\\Jumpstart.exe\" --wrap";
        Setup setup = new Setup(loc, new Texts(false), log::add, () -> steamRunning, Optional.of(wrapper));
        setup.installFiles(payload());
        assertEquals(Optional.of("test-1"), setup.installedVersion());

        steamRunning = true;
        assertEquals(Setup.LaunchResult.STEAM_RUNNING, setup.launchOptions(true));
        steamRunning = false;
        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(true));
        Setup.Status s = setup.status();
        assertTrue(s.installed() && s.autoUpdate(), s.toString());
        assertEquals(Optional.of(wrapper + " " + bitecheck), s.launchOptions(),
                "Jumpstart first, then BiteCheck; ZombieBuddy's agent goes (Jumpstart starts from ProjectZomboid64.json)");

        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(false));
        setup.uninstallFiles();
        assertEquals(Optional.of(bitecheck), setup.status().launchOptions(), "BiteCheck's wrapper stays");
    }

    @Test
    void windowsTakesZombieBuddyOutOfSteamsOptions() throws IOException {
        GameLocation loc = windows();
        steamConfig("-agentlib:zbNative --");
        Setup setup = setup(loc);
        setup.installFiles(payload());
        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(true), "not NOT_NEEDED: a second agent must go");
        assertEquals(Optional.of(""), setup.status().launchOptions());
    }

    @Test
    void windowsWithoutSteamConfigStillInstalls() throws IOException {
        Setup setup = setup(windows());
        setup.installFiles(payload());
        assertEquals(Setup.LaunchResult.NOT_NEEDED, setup.launchOptions(true), "no wrapper wanted: nothing to set");
        assertEquals(Setup.LaunchResult.NOT_NEEDED, setup.launchOptions(false));
    }

    @Test
    void repairPutsJumpstartBackAfterAGameUpdate() throws IOException {
        GameLocation loc = windows();
        Setup setup = setup(loc);
        setup.installFiles(payload());
        Files.writeString(loc.launchJson(), JSON); // Steam's update
        assertTrue(setup.repair());
        assertTrue(LaunchJson.hasJumpstart(Files.readString(loc.launchJson())));
        assertFalse(setup.repair(), "nothing left to repair");
    }

    @Test
    void windowsInstallOf050GetsTheLoader() throws IOException {
        // 0.5.0 wrote -javaagent and no loader, so the game did not start; its automatic update
        // installs the new payload with 0.5.0's code, then the new installer repairs at the next start
        GameLocation loc = windows();
        Setup setup = setup(loc);
        setup.installFiles(payload());
        Files.delete(loc.nativeLoader());
        String jar = loc.jumpstartJar().toAbsolutePath().toString().replace('\\', '/');
        Files.writeString(loc.launchJson(), JSON.replace("\"-Xmx16g\"", "\"-javaagent:" + jar + "=hotswap=true\",\r\n\t\t\"-Xmx16g\""));
        assertFalse(setup.status().installed(), "the game does not start like this");

        assertFalse(setup.repair(Optional.empty()), "without a loader to hand the json stays");
        assertTrue(Files.readString(loc.launchJson()).contains("-javaagent:"));

        Path loader = Files.writeString(tmp.resolve("bundled-jsNative.dll"), "loader");
        assertTrue(setup.repair(Optional.of(loader)));
        assertEquals("loader", Files.readString(loc.nativeLoader()));
        String json = Files.readString(loc.launchJson());
        assertTrue(json.contains("\"-agentlib:jsNative=hotswap=true\""), json);
        assertFalse(json.contains("-javaagent:"), json);
        assertFalse(Files.exists(loc.replacedAgents()), "the entry was Jumpstart's own");
        assertTrue(setup.status().installed());
        assertFalse(setup.repair(Optional.of(loader)), "nothing left to repair");
    }

    @Test
    void windowsNeedsTheLoaderInThePayload() throws IOException {
        GameLocation loc = windows();
        Payload p = payload();
        Files.delete(p.nativeLoader());
        IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> setup(loc).installFiles(p));
        assertTrue(e.getMessage().contains("jsNative.dll"), e.getMessage());
        assertFalse(Files.exists(loc.jumpstartJar()), "nothing was changed");
        assertEquals(JSON, Files.readString(loc.launchJson()));
    }

    @Test
    void automaticUpdateKeepsTheModList() throws IOException {
        GameLocation loc = windows();
        Setup setup = setup(loc);
        setup.installFiles(payload());
        String off = ModList.disable(Files.readString(loc.modList()), "ZombieBuddy");
        Files.writeString(loc.modList(), off); // the player switched it off in the mod menu
        setup.installFiles(payload(), false);
        assertEquals(off, Files.readString(loc.modList()));
    }

    @Test
    void payloadFromZip() throws IOException {
        Path z = tmp.resolve("payload.zip");
        zip(z, "VERSION", "Jumpstart.jar", "mods/ZombieBuddy/42/mod.info");
        Path out = tmp.resolve("out");
        try (var in = Files.newInputStream(z)) {
            Payload.unzip(in, out);
        }
        assertTrue(Files.isRegularFile(out.resolve("mods/ZombieBuddy/42/mod.info")));
        assertEquals("VERSION", Payload.of(out).version());

        Path evil = tmp.resolve("evil.zip");
        zip(evil, "../outside.txt");
        try (var in = Files.newInputStream(evil)) {
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> Payload.unzip(in, tmp.resolve("out2")));
        }
        Files.delete(out.resolve("Jumpstart.jar"));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> Payload.of(out), "a payload needs Jumpstart.jar");
    }
}
