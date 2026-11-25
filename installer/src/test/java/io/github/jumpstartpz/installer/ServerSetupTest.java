package io.github.jumpstartpz.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
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

/** Jumpstart on a fake dedicated server, Linux and Windows. */
class ServerSetupTest {
    @TempDir
    Path tmp;

    private final List<String> log = new ArrayList<>();

    private Payload payload() throws IOException {
        Path p = tmp.resolve("payload");
        fork(p.resolve("Jumpstart.jar"));
        Files.createDirectories(p.resolve("mods/ZombieBuddy/42"));
        Files.writeString(p.resolve("mods/ZombieBuddy/42/mod.info"), "id=ZombieBuddy\n");
        Files.writeString(p.resolve("mods/ZombieBuddy/" + Setup.FORK_MARKER), "fork");
        Files.writeString(p.resolve("VERSION"), "0.2.0\n");
        return Payload.of(p);
    }

    private static void fork(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("me/zed_0xff/zombie_buddy/Hotswap.class"));
            zip.closeEntry();
        }
    }

    private static final String JSON = "{\n\t\"mainClass\": \"zombie/network/GameServer\",\n\t\"vmArgs\": [\n\t\t\"-Xmx8g\"\n\t]\n}\n";

    @Test
    void linuxHeadlessServer() throws IOException {
        Path server = tmp.resolve("server");
        Files.createDirectories(server.resolve("java"));
        Files.writeString(server.resolve("ProjectZomboid64.json"), JSON);
        Path data = tmp.resolve("data");
        Files.createDirectories(data.resolve("Server"));
        Files.writeString(data.resolve("Server/build_42_mods.ini"), "PVP=true\nMods=\\Minimap\nWorkshopItems=123\n");
        Path zbConfig = tmp.resolve("zbconfig");
        ServerSetup.Options o = new ServerSetup.Options(server, Optional.empty(), data,
                Optional.of("hotswap=true,policy=deny-new,config_dir=" + zbConfig), true);
        ServerSetup s = new ServerSetup(Platform.Os.LINUX, o, new Texts(false), log::add);

        s.install(payload());
        assertTrue(s.isInstalled());
        assertEquals(Optional.of("0.2.0"), s.installedVersion());
        String json = Files.readString(server.resolve("ProjectZomboid64.json"));
        assertTrue(json.contains("/java/Jumpstart.jar=hotswap=true,policy=deny-new,config_dir="), json);
        assertTrue(Setup.isFork(server.resolve("java/Jumpstart.jar")));
        assertFalse(Files.exists(server.resolve("java/ZombieBuddy.jar")));
        assertFalse(Files.exists(data.resolve("mods/Jumpstart")), "Jumpstart is no mod any more");
        assertFalse(Files.exists(data.resolve("mods/ZombieBuddy/libs")));
        assertEquals("PVP=true\nMods=\\Minimap;\\ZombieBuddy\nWorkshopItems=123\n",
                Files.readString(data.resolve("Server/build_42_mods.ini")), "no Workshop item for ZombieBuddy");
        assertFalse(Files.exists(zbConfig.resolve("mod_approvals.json")), "the agent needs no approval");

        // SteamCMD's game update replaces the json
        Files.writeString(server.resolve("ProjectZomboid64.json"), JSON);
        assertTrue(s.repair());
        assertTrue(Files.readString(server.resolve("ProjectZomboid64.json")).contains("Jumpstart.jar"));
        assertFalse(s.repair());

        s.install(payload()); // an update: nothing doubled
        assertEquals(1, Files.readString(server.resolve("ProjectZomboid64.json")).split("Jumpstart.jar", -1).length - 1);
        assertEquals("PVP=true\nMods=\\Minimap;\\ZombieBuddy\nWorkshopItems=123\n",
                Files.readString(data.resolve("Server/build_42_mods.ini")));

        s.uninstall();
        assertFalse(s.isInstalled());
        assertEquals(JSON, Files.readString(server.resolve("ProjectZomboid64.json")));
        assertEquals("PVP=true\nMods=\\Minimap\nWorkshopItems=123\n", Files.readString(data.resolve("Server/build_42_mods.ini")));
    }

    @Test
    void windowsServerWithNewName() throws IOException {
        Path server = tmp.resolve("Project Zomboid Dedicated Server");
        Files.createDirectories(server);
        String bat = "@setlocal enableextensions\r\n@cd /d \"%~dp0\"\r\n\".\\jre64\\bin\\java.exe\" -Djava.awt.headless=true -Xmx8g zombie.network.GameServer %1\r\n";
        Files.writeString(server.resolve("StartServer64.bat"), bat);
        Path data = tmp.resolve("Zomboid");
        ServerSetup s = new ServerSetup(Platform.Os.WINDOWS,
                new ServerSetup.Options(server, Optional.of("servertest"), data, Optional.empty(), false), new Texts(false), log::add);
        s.install(payload());
        assertTrue(Files.readString(server.resolve("StartServer64.bat")).contains("java.exe\" -javaagent:Jumpstart.jar=hotswap=true -Djava"));
        assertTrue(Files.isRegularFile(server.resolve("Jumpstart.jar")));
        assertEquals("Mods=ZombieBuddy\nWorkshopItems=\n", Files.readString(data.resolve("Server/servertest.ini")));
        s.uninstall();
        assertEquals(bat, Files.readString(server.resolve("StartServer64.bat")));
        assertFalse(Files.exists(server.resolve("Jumpstart.jar")));
    }

    @Test
    void movesAServerFromBefore02() throws IOException {
        Path server = tmp.resolve("server");
        Files.createDirectories(server.resolve("java"));
        fork(server.resolve("java/ZombieBuddy.jar"));
        String old = JSON.replace("[\n", "[\n\t\t\"-javaagent:" + server.resolve("java/ZombieBuddy.jar").toAbsolutePath().toString().replace('\\', '/')
                + "=hotswap=true\",\n");
        Files.writeString(server.resolve("ProjectZomboid64.json"), old);
        Path data = tmp.resolve("data");
        Files.createDirectories(data.resolve("Server"));
        Files.createDirectories(data.resolve("mods/Jumpstart/42"));
        Files.writeString(data.resolve("mods/Jumpstart/" + Setup.VERSION_FILE), "0.1.0\n");
        Files.writeString(data.resolve("Server/s.ini"), "Mods=ZombieBuddy;Jumpstart\n");
        ServerSetup s = new ServerSetup(Platform.Os.LINUX, new ServerSetup.Options(server, Optional.empty(), data, Optional.empty(), false),
                new Texts(false), log::add);
        assertTrue(s.isInstalled(), "an update moves it");
        assertEquals(Optional.of("0.1.0"), s.installedVersion());

        s.install(payload());
        String json = Files.readString(server.resolve("ProjectZomboid64.json"));
        assertFalse(json.contains("ZombieBuddy.jar"), json);
        assertEquals(1, json.split("Jumpstart.jar", -1).length - 1, json);
        assertFalse(Files.exists(server.resolve("java/ZombieBuddy.jar")));
        assertFalse(Files.exists(data.resolve("mods/Jumpstart")));
        assertEquals("Mods=ZombieBuddy\n", Files.readString(data.resolve("Server/s.ini")));
        assertEquals(Optional.of("0.2.0"), s.installedVersion());

        s.uninstall();
        assertEquals(JSON, Files.readString(server.resolve("ProjectZomboid64.json")));
    }

    @Test
    void notAServer() {
        ServerSetup s = new ServerSetup(Platform.Os.LINUX, new ServerSetup.Options(tmp, Optional.empty(), tmp.resolve("d"), Optional.empty(), false),
                new Texts(false), log::add);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, s::repair);
    }
}
