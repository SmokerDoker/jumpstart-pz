package io.github.jumpstartpz.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerSnapshotsTest {
    @TempDir
    Path data;

    private Path zip(String time, Map<String, String> entries) throws IOException {
        Path dir = Files.createDirectories(data.resolve("jumpstart/snapshots/Multiplayer_build_42"));
        Path zip = dir.resolve(time + ".zip");
        try (OutputStream o = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(o, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void restoresTheLatestAndKeepsTheSaveItReplaced() throws IOException {
        zip("2026-11-26_10-00-00", Map.of("Multiplayer/build_42/map_t.bin", "old"));
        zip("2026-11-27_10-00-00", Map.of("Multiplayer/build_42/map_t.bin", "newer",
                "Multiplayer/build_42/../../../evil.txt", "outside", "Multiplayer/other/x.bin", "another server"));
        Path save = Files.createDirectories(data.resolve("Saves/Multiplayer/build_42"));
        Files.writeString(save.resolve("map_t.bin"), "now");
        ServerSnapshots s = new ServerSnapshots(data, "build_42");

        assertEquals("2026-11-27_10-00-00.zip", s.list().get(0).getFileName().toString());
        Path replaced = s.restore(s.find("latest").orElseThrow());
        assertEquals("newer", Files.readString(save.resolve("map_t.bin")));
        assertEquals("now", Files.readString(replaced.resolve("map_t.bin")), "nothing is deleted");
        assertFalse(Files.exists(data.resolve("evil.txt")));
        assertFalse(Files.exists(data.resolve("Saves/Multiplayer/other")));

        s.restore(s.find("2026-11-26_10-00-00.zip").orElseThrow());
        assertEquals("old", Files.readString(save.resolve("map_t.bin")));
    }

    @Test
    void aZipWithoutThisSaveChangesNothing() throws IOException {
        Path wrong = zip("2026-11-27_11-00-00", Map.of("Multiplayer/other/map.bin", "x"));
        Path save = Files.createDirectories(data.resolve("Saves/Multiplayer/build_42"));
        Files.writeString(save.resolve("map.bin"), "keep me");
        ServerSnapshots s = new ServerSnapshots(data, "build_42");
        assertThrows(IOException.class, () -> s.restore(wrong));
        assertEquals("keep me", Files.readString(save.resolve("map.bin")));
        assertTrue(s.find("nope.zip").isEmpty());
    }
}
