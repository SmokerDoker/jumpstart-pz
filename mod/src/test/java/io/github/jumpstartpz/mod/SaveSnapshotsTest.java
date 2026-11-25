package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Copies of a save before switching, and restoring one from the main menu. */
class SaveSnapshotsTest {
    @TempDir
    Path zomboid;

    private Path saves() throws IOException {
        return Files.createDirectories(zomboid.resolve("Saves"));
    }

    private Path save(String content) throws IOException {
        Path save = Files.createDirectories(saves().resolve("Sandbox").resolve("My save"));
        Files.writeString(save.resolve("map_t.bin"), content);
        Files.createDirectories(save.resolve("chunkdata"));
        Files.writeString(save.resolve("chunkdata").resolve("c1.bin"), content + "1");
        Files.createDirectories(save.resolve("jumpstart"));
        Files.writeString(save.resolve("jumpstart").resolve("moddata-keys.txt"), "not part of the copy");
        return save;
    }

    @Test
    void takesAndRestoresASaveKeepingTheReplacedFolder() throws Exception {
        SaveSnapshots s = new SaveSnapshots(zomboid.resolve("jumpstart"));
        Path save = save("before");
        Path zip = s.take(save, saves());
        assertTrue(Files.isRegularFile(zip));

        Files.writeString(save.resolve("map_t.bin"), "broken by a mod");
        List<SaveSnapshots.Snapshot> list = s.list();
        assertEquals(1, list.size());
        assertEquals("Sandbox/My save", list.get(0).save());

        Path replaced = s.restore(list.get(0), saves());
        assertEquals("before", Files.readString(save.resolve("map_t.bin")));
        assertEquals("before1", Files.readString(save.resolve("chunkdata").resolve("c1.bin")));
        assertFalse(Files.exists(save.resolve("jumpstart")), "Jumpstart's own files are not in the copy");
        assertEquals("broken by a mod", Files.readString(replaced.resolve("map_t.bin")), "nothing is deleted");
    }

    @Test
    void keepsTheNewestFivePerSave() throws Exception {
        SaveSnapshots s = new SaveSnapshots(zomboid.resolve("jumpstart"));
        Path save = save("x");
        Path dir = zomboid.resolve("jumpstart").resolve("snapshots").resolve("Sandbox_My_save");
        Files.createDirectories(dir);
        for (int i = 1; i <= 6; i++) {
            Files.writeString(dir.resolve("2020-01-0" + i + "_00-00-00.zip"), "old");
        }
        s.take(save, saves());
        try (var files = Files.list(dir)) {
            assertEquals(SaveSnapshots.KEEP, files.count());
        }
        assertFalse(Files.exists(dir.resolve("2020-01-01_00-00-00.zip")));
    }

    @Test
    void aSnapshotCannotWriteOutsideItsSave() throws Exception {
        SaveSnapshots s = new SaveSnapshots(zomboid.resolve("jumpstart"));
        Path dir = Files.createDirectories(zomboid.resolve("jumpstart").resolve("snapshots").resolve("x"));
        Path zip = dir.resolve("2026-01-01_00-00-00.zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            z.setComment("Sandbox/My save");
            z.putNextEntry(new ZipEntry("Sandbox/My save/ok.bin"));
            z.write("ok".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("Sandbox/My save/../../../evil.txt"));
            z.write("evil".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("Sandbox/Other/stolen.bin"));
            z.write("other".getBytes(StandardCharsets.UTF_8));
        }
        s.restore(s.list().get(0), saves());
        assertTrue(Files.exists(saves().resolve("Sandbox").resolve("My save").resolve("ok.bin")));
        assertFalse(Files.exists(zomboid.resolve("evil.txt")));
        assertFalse(Files.exists(saves().resolve("Sandbox").resolve("Other")));
    }

    @Test
    void refusesASnapshotThatNamesNoSaveFolder() throws Exception {
        SaveSnapshots s = new SaveSnapshots(zomboid.resolve("jumpstart"));
        assertThrows(IOException.class, () -> s.restore(new SaveSnapshots.Snapshot(zomboid.resolve("x.zip"), "../..", "t"), saves()));
        assertThrows(IOException.class, () -> s.restore(new SaveSnapshots.Snapshot(zomboid.resolve("x.zip"), "?", "t"), saves()));
    }

    @Test
    void theSettingDecidesAndAutoSkipsBigSaves() throws Exception {
        SaveSnapshots s = new SaveSnapshots(zomboid.resolve("jumpstart"));
        Path save = save("small");
        assertEquals(SaveSnapshots.Mode.AUTO, s.mode());
        assertTrue(s.wanted(save));
        Files.createDirectories(zomboid.resolve("jumpstart"));
        Files.writeString(zomboid.resolve("jumpstart").resolve("settings.properties"), "snapshots=off\n");
        assertFalse(s.wanted(save));
        Files.writeString(zomboid.resolve("jumpstart").resolve("settings.properties"), "snapshots=on\n");
        assertTrue(s.wanted(save));
    }
}
