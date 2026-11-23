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

    private Payload payload() throws IOException {
        Path p = tmp.resolve("payload");
        zip(p.resolve("mods/ZombieBuddy/libs/ZombieBuddy.jar"), "me/zed_0xff/zombie_buddy/Hotswap.class", "fork");
        Files.createDirectories(p.resolve("mods/ZombieBuddy/42"));
        Files.writeString(p.resolve("mods/ZombieBuddy/42/mod.info"), "name=ZombieBuddy (Jumpstart fork)\nid=ZombieBuddy\n");
        Files.writeString(p.resolve("mods/ZombieBuddy/" + Setup.FORK_MARKER), "fork");
        Files.createDirectories(p.resolve("mods/Jumpstart/42/media/java"));
        Files.writeString(p.resolve("mods/Jumpstart/42/mod.info"), "name=Jumpstart\nid=Jumpstart\nmodversion=0.2.0\n");
        zip(p.resolve("mods/Jumpstart/42/media/java/Jumpstart.jar"), "io/github/jumpstartpz/mod/Main.class");
        Files.writeString(p.resolve("VERSION"), "test-1\n");
        return Payload.of(p);
    }

    private GameLocation windows() throws IOException {
        Path game = tmp.resolve("Steam/steamapps/common/ProjectZomboid");
        Files.createDirectories(game);
        Files.writeString(game.resolve("projectzomboid.jar"), "game");
        Files.writeString(game.resolve("ProjectZomboid64.json"),
                "{\r\n\t\"vmArgs\": [\r\n\t\t\"-Xmx16g\"\r\n\t]\r\n}\r\n");
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
        return new Setup(loc, new Texts(false), log::add, () -> steamRunning);
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
        assertEquals(Optional.of("0.2.0"), s.jumpstartVersion());
        assertTrue(Setup.isFork(loc.zombieBuddyJar()));
        assertTrue(Files.readString(loc.launchJson()).contains("ZombieBuddy.jar=hotswap=true"));
        assertTrue(Files.isRegularFile(loc.modsDir().resolve("ZombieBuddy/libs/ZombieBuddy.jar")));
        String list = Files.readString(loc.modList());
        assertTrue(list.contains("mod = ZombieBuddy,\r\n    mod = Jumpstart,"), list);

        setup.installFiles(payload()); // again: an update changes nothing else
        assertFalse(Files.exists(loc.javaDir().resolve("ZombieBuddy.jar" + Setup.BACKUP)), "the fork is never kept as the player's own jar");

        setup.uninstallFiles();
        assertFalse(setup.status().anything());
        assertFalse(Files.exists(loc.zombieBuddyJar()));
        assertFalse(Files.exists(loc.modsDir().resolve("ZombieBuddy")));
        assertEquals("{\r\n\t\"vmArgs\": [\r\n\t\t\"-Xmx16g\"\r\n\t]\r\n}\r\n", Files.readString(loc.launchJson()));
        assertFalse(Files.readString(loc.modList()).contains("Jumpstart"));
        assertFalse(Files.readString(loc.modList()).contains("ZombieBuddy"));
    }

    @Test
    void windowsKeepsThePlayersOwnZombieBuddy() throws IOException {
        GameLocation loc = windows();
        zip(loc.zombieBuddyJar(), "me/zed_0xff/zombie_buddy/ZombieBuddy.class"); // upstream: no Hotswap
        Files.writeString(loc.launchJson(), Files.readString(loc.launchJson()).replace("\"-Xmx16g\"", "\"-agentlib:zbNative\",\r\n\t\t\"-Xmx16g\""));
        Path own = loc.modsDir().resolve("ZombieBuddy");
        Files.createDirectories(own.resolve("42"));
        Files.writeString(own.resolve("42/mod.info"), "id=ZombieBuddy\n");
        Setup setup = setup(loc);

        setup.installFiles(payload());
        assertTrue(Setup.isFork(loc.zombieBuddyJar()));
        assertTrue(Files.readString(loc.launchJson()).contains("\"-agentlib:zbNative=hotswap=true\""));
        assertTrue(Files.isRegularFile(loc.backupDir().resolve("ZombieBuddy/42/mod.info")));
        assertTrue(Files.exists(own.resolve(Setup.FORK_MARKER)));

        setup.uninstallFiles();
        assertTrue(Files.isRegularFile(loc.zombieBuddyJar()));
        assertFalse(Setup.isFork(loc.zombieBuddyJar()), "the player's ZombieBuddy.jar is back");
        assertTrue(Files.readString(loc.launchJson()).contains("\"-agentlib:zbNative\""));
        assertFalse(Files.exists(own.resolve(Setup.FORK_MARKER)), "the player's mod folder is back");
        assertTrue(Files.readString(loc.modList()).contains("ZombieBuddy"), "ZombieBuddy stays on: the player has it");
    }

    @Test
    void linuxSetsTheLaunchOptionsWhenSteamIsClosed() throws IOException {
        GameLocation loc = linux("-javaagent:ZombieBuddy.jar --");
        Setup setup = setup(loc);
        setup.installFiles(payload());
        assertFalse(setup.status().installed());

        steamRunning = true;
        assertEquals(Setup.LaunchResult.STEAM_RUNNING, setup.launchOptions(true));
        assertEquals("-javaagent:ZombieBuddy.jar=hotswap=true --", setup.suggestedLaunchOptions(true));
        assertFalse(setup.status().launchReady());

        steamRunning = false;
        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(true));
        Setup.Status s = setup.status();
        assertTrue(s.installed(), s.toString());
        assertEquals(Optional.of("-javaagent:ZombieBuddy.jar=hotswap=true --"), s.launchOptions());
        assertTrue(Files.exists(loc.localConfigs().get(0).resolveSibling("localconfig.vdf" + Setup.BACKUP)));
        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(true), "already set");

        // uninstall: no ZombieBuddy.jar before Jumpstart, so the whole option goes, then the jar
        assertEquals(Setup.LaunchResult.DONE, setup.launchOptions(false));
        setup.uninstallFiles();
        assertEquals(Optional.of(""), setup.status().launchOptions());
        assertFalse(Files.exists(loc.zombieBuddyJar()));
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
        assertTrue(Files.exists(loc.zombieBuddyJar()), "removing it would stop the game from starting");
        assertTrue(log.stream().anyMatch(l -> l.contains("ZombieBuddy.jar stays")), log.toString());
    }

    @Test
    void noSteamAccountWithTheGame() throws IOException {
        GameLocation loc = linux(null);
        Files.delete(loc.localConfigs().get(0));
        Setup setup = setup(loc);
        setup.installFiles(payload());
        assertEquals(Setup.LaunchResult.NO_STEAM_CONFIG, setup.launchOptions(true));
        assertEquals("-javaagent:ZombieBuddy.jar=hotswap=true --", setup.suggestedLaunchOptions(true));
    }

    @Test
    void payloadFromZip() throws IOException {
        Path z = tmp.resolve("payload.zip");
        zip(z, "VERSION", "mods/Jumpstart/42/mod.info");
        Path out = tmp.resolve("out");
        try (var in = Files.newInputStream(z)) {
            Payload.unzip(in, out);
        }
        assertTrue(Files.isRegularFile(out.resolve("mods/Jumpstart/42/mod.info")));

        Path evil = tmp.resolve("evil.zip");
        zip(evil, "../outside.txt");
        try (var in = Files.newInputStream(evil)) {
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> Payload.unzip(in, tmp.resolve("out2")));
        }
    }
}
