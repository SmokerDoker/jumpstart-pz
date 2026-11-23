package io.github.jumpstartpz.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.bitecheckpz.launcher.Platform;

/** The launch option starts ~/.jumpstart/JumpstartSetup.jar with the game's own Java, not the app. */
class StarterTest {
    @TempDir
    Path tmp;

    private GameLocation game(Platform.Os os, Path javaDir) {
        return new GameLocation(os, javaDir, tmp.resolve("Zomboid"), List.of(), List.of());
    }

    @Test
    void gameJavaOnWindowsAndLinux() throws IOException {
        Path win = tmp.resolve("ProjectZomboid");
        Files.createDirectories(win.resolve("jre64/bin"));
        Files.writeString(win.resolve("jre64/bin/javaw.exe"), "");
        assertEquals(Optional.of(win.resolve("jre64/bin/javaw.exe")), game(Platform.Os.WINDOWS, win).gameJava(), "javaw: no console window");

        Path linux = tmp.resolve("linux/projectzomboid");
        Files.createDirectories(linux.resolve("jre64/bin"));
        Files.writeString(linux.resolve("jre64/bin/java"), "");
        assertEquals(Optional.of(linux.resolve("jre64/bin/java")), game(Platform.Os.LINUX, linux).gameJava());
        assertEquals(Optional.empty(), game(Platform.Os.LINUX, tmp.resolve("none")).gameJava());
    }

    @Test
    void gameJavaOnMacIsFoundInTheAppBundle() throws IOException {
        Path contents = tmp.resolve("Project Zomboid.app/Contents");
        Path java = contents.resolve("PlugIns/jre.bundle/Contents/Home/bin/java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "");
        assertTrue(java.toFile().setExecutable(true));
        Files.createDirectories(contents.resolve("Java"));
        assertEquals(Optional.of(java), game(Platform.Os.MACOS, contents.resolve("Java")).gameJava());
    }

    @Test
    void theLaunchOptionDoesNotNeedTheApp() throws IOException {
        Path home = tmp.resolve("home/.jumpstart");
        System.setProperty("jumpstart.home", home.toString());
        try {
            Files.createDirectories(home);
            Files.writeString(home.resolve("JumpstartSetup.jar"), "jar"); // placed by the installer
            Path win = tmp.resolve("ProjectZomboid");
            Files.createDirectories(win.resolve("jre64/bin"));
            Files.writeString(win.resolve("jre64/bin/javaw.exe"), "");
            String prefix = Wrapper.prefix(game(Platform.Os.WINDOWS, win)).orElseThrow();
            assertEquals("\"" + win.resolve("jre64/bin/javaw.exe") + "\" -jar \"" + home.resolve("JumpstartSetup.jar") + "\" --wrap", prefix);
            String options = LaunchOptions.withWrapper("-agentlib:zbNative --", prefix);
            assertTrue(LaunchOptions.hasWrapper(options), options);
            // the app's own wrapper from an earlier version is replaced, not doubled
            String old = "\"C:\\Users\\me\\AppData\\Local\\Jumpstart\\Jumpstart.exe\" --wrap %command% -agentlib:zbNative --";
            assertEquals(prefix + " %command% -agentlib:zbNative --", LaunchOptions.withWrapper(old, prefix));
            assertEquals("-agentlib:zbNative --", LaunchOptions.withoutWrapper(options));
        } finally {
            System.clearProperty("jumpstart.home");
        }
    }
}
