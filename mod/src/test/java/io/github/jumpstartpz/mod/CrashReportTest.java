package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashReportTest {
    @TempDir
    Path tmp;

    @Test
    void oneZipWithTheLogsTheModsAndTheJvmCrashFiles() throws IOException {
        Path zomboid = Files.createDirectories(tmp.resolve("Zomboid"));
        Files.writeString(zomboid.resolve("console.txt"), "LOG : General  game started\n[Jumpstart] something\n");
        Path game = Files.createDirectories(tmp.resolve("game"));
        Files.writeString(game.resolve("hs_err_pid123.log"), "# A fatal error has been detected");
        Files.writeString(game.resolve("readme.log"), "not a crash file");

        Path zip = CrashReport.write(zomboid, game, "Guns\tJAVA\ton\n", "");

        Map<String, String> entries = new TreeMap<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (var e : java.util.Collections.list(z.entries())) {
                entries.put(e.getName(), new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(java.util.Set.of("README.txt", "mods.txt", "guard.txt", "system.txt", "console.txt", "jvm/hs_err_pid123.log"),
                entries.keySet());
        assertTrue(entries.get("console.txt").contains("[Jumpstart] something"));
        assertEquals("(nothing caught)\n", entries.get("guard.txt"));
        assertTrue(entries.get("system.txt").contains("java.version = "));
        assertTrue(zip.startsWith(zomboid.resolve("jumpstart").resolve("reports")));
    }

    @Test
    void aBigLogKeepsItsEnd() throws IOException {
        Path zomboid = Files.createDirectories(tmp.resolve("Zomboid"));
        StringBuilder big = new StringBuilder();
        while (big.length() < CrashReport.MAX_LOG + 1000) {
            big.append("filler line for the start of the log\n");
        }
        big.append("THE CRASH\n");
        Files.writeString(zomboid.resolve("console.txt"), big);
        Path zip = CrashReport.write(zomboid, null, "", "");
        try (ZipFile z = new ZipFile(zip.toFile())) {
            String console = new String(z.getInputStream(z.getEntry("console.txt")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(console.startsWith("[... the first "));
            assertTrue(console.endsWith("THE CRASH\n"));
            assertTrue(console.length() < CrashReport.MAX_LOG + 200);
        }
    }
}
