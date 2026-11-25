package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uninstalling moves the files a mod created into the archive, and nothing else. */
class FileJournalTest {
    @TempDir
    Path zomboid;

    @AfterEach
    void reset() {
        FileJournal.setRoot(null);
    }

    private static String norm(Path p) {
        return p.toString().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    void archivesOnlyWhatTheModCreatedInsideTheDataFolder() throws Exception {
        Path root = zomboid.resolve("jumpstart");
        FileJournal.setRoot(root);
        Path lua = Files.createDirectories(zomboid.resolve("Lua"));
        Path created = Files.writeString(lua.resolve("jst_settings.txt"), "mine");
        Path existed = Files.writeString(lua.resolve("shared.txt"), "was here before");
        Path otherMods = Files.writeString(lua.resolve("other.txt"), "another mod's");
        Path outside = Files.writeString(Files.createTempFile("jst-outside", ".txt"), "not in Zomboid");
        String modSource = "/mods/jst/42/media/lua/client/jst.lua";
        Files.createDirectories(root.resolve("files"));
        Files.writeString(root.resolve("files").resolve("written.txt"), String.join("\n",
                created + "\tfalse\t" + modSource,
                existed + "\ttrue\t" + modSource,
                otherMods + "\tfalse\t/mods/other/media/lua/client/o.lua",
                outside + "\tfalse\t" + modSource) + "\n", StandardCharsets.UTF_8);

        List<String> dirs = List.of("/mods/jst/");
        assertEquals(3, FileJournal.writesOf(dirs).size());
        assertEquals(1, FileJournal.archive("JST", dirs));

        assertFalse(Files.exists(created), "the file the mod created left");
        assertTrue(Files.exists(existed), "a file that existed before stays");
        assertTrue(Files.exists(otherMods), "another mod's file stays");
        assertTrue(Files.exists(outside), "nothing outside the game's data folder is moved, whatever the log says");
        Path archived;
        try (var walk = Files.walk(root.resolve("archive"))) {
            archived = walk.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        assertEquals("mine", Files.readString(archived));
        assertTrue(norm(archived).endsWith("lua/jst_settings.txt"), archived.toString());
        assertTrue(Files.readString(root.resolve("files").resolve("JST.txt")).contains("jst_settings.txt"));
        Files.deleteIfExists(outside);
    }

    @Test
    void namesForTheArchiveAndModIdsStayInsideTheirFolder() {
        assertEquals("a_b_c", FileJournal.safe("a/b\\c"));
        assertFalse(FileJournal.archiveName(Path.of("C:/x/y.txt").toAbsolutePath()).isAbsolute());
    }

    @Test
    void readsTheModDataKeysOfASave() throws Exception {
        Path file = Files.writeString(zomboid.resolve("moddata-keys.txt"),
                "JSTest\t/mods/jst/media/lua/client/jst.lua\nJSTest\t/mods/other/x.lua\nOther\t/mods/other/x.lua\n");
        var keys = ModDataJournal.keysOf(file);
        assertEquals("/mods/jst/media/lua/client/jst.lua", keys.get("JSTest"), "the first mod that made the table owns it");
        assertEquals(2, keys.size());
    }
}
