package io.github.jumpstartpz.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import io.github.bitecheckpz.launcher.Platform;

/** The Steam client: is it running, close it, start the game (through steam:// links). */
final class Steam {
    private Steam() {
    }

    static boolean isRunning(Platform.Os os) {
        boolean process = ProcessHandle.allProcesses().anyMatch(p -> p.info().command().map(c -> isSteam(os, c)).orElse(false));
        return process || (os == Platform.Os.LINUX && pidFileAlive());
    }

    static boolean isSteam(Platform.Os os, String command) {
        String c = command.replace('\\', '/');
        String name = c.substring(c.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return switch (os) {
            case WINDOWS -> name.equals("steam.exe");
            case MACOS -> c.contains("/Steam.app/Contents/MacOS/") || name.equals("steam_osx");
            case LINUX -> name.equals("steam") || name.equals("steam.sh");
        };
    }

    /** Linux Steam (also Flatpak, whose processes may not be visible by name) writes its pid here. */
    private static boolean pidFileAlive() {
        Path home = Path.of(System.getProperty("user.home"));
        for (Path pidFile : List.of(home.resolve(".steam/steam.pid"), home.resolve(".var/app/com.valvesoftware.Steam/.steam/steam.pid"))) {
            try {
                if (Files.isRegularFile(pidFile)) {
                    long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).strip());
                    if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                        return true;
                    }
                }
            } catch (IOException | RuntimeException e) {
                // no readable pid: not running as far as we know
            }
        }
        return false;
    }

    /** Asks Steam to exit and waits until it is gone; true if it is closed. */
    static boolean close(Platform.Os os, Duration timeout) throws InterruptedException {
        if (!isRunning(os)) {
            return true;
        }
        open(os, "steam://exit");
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            Thread.sleep(500);
            if (!isRunning(os)) {
                Thread.sleep(1500); // Steam writes localconfig.vdf on its way out
                return true;
            }
        }
        return false;
    }

    /** Starts Project Zomboid through Steam (Steam starts first if needed). */
    static void startGame(Platform.Os os) {
        open(os, "steam://rungameid/" + LocalConfigVdf.APP_ID);
    }

    static void openWorkshopPage(Platform.Os os, String id) {
        open(os, "steam://url/CommunityFilePage/" + id);
    }

    static void open(Platform.Os os, String url) {
        List<String> command = switch (os) {
            case WINDOWS -> List.of("rundll32", "url.dll,FileProtocolHandler", url);
            case MACOS -> List.of("open", url);
            case LINUX -> List.of("xdg-open", url);
        };
        try {
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            // no handler for steam:// links: nothing to open
        }
    }
}
