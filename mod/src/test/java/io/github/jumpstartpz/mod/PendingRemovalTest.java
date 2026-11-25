package io.github.jumpstartpz.mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingRemovalTest {
    @TempDir
    Path tmp;

    @Test
    void movesTheFolderWhenNothingHoldsIt() throws Exception {
        Path mods = Files.createDirectories(tmp.resolve("mods"));
        Path mod = Files.createDirectories(mods.resolve("MyMod/42"));
        Files.writeString(mod.resolve("mod.info"), "id=MyMod");
        Path target = tmp.resolve("mods-uninstalled/MyMod-today");

        assertNull(PendingRemoval.moveOut(mods.resolve("MyMod"), target, tmp.resolve("pending.txt")));
        assertFalse(Files.exists(mods.resolve("MyMod")));
        assertTrue(Files.isRegularFile(target.resolve("42/mod.info")));
        assertFalse(Files.exists(tmp.resolve("pending.txt")));
    }

    @Test
    void removesOnlyMarkedFoldersDirectlyInsideTheModsFolder() throws Exception {
        Path mods = Files.createDirectories(tmp.resolve("mods"));
        Path marked = Files.createDirectories(mods.resolve("Leftover"));
        Files.writeString(marked.resolve(PendingRemoval.MARKER), "x");
        Files.writeString(marked.resolve("locked.lua"), "x");
        Path unmarked = Files.createDirectories(mods.resolve("Reinstalled"));
        Files.writeString(unmarked.resolve("mod.info"), "id=Reinstalled");
        Path outside = Files.createDirectories(tmp.resolve("Saves/MySave"));
        Files.writeString(outside.resolve(PendingRemoval.MARKER), "x");
        Path nested = Files.createDirectories(mods.resolve("Other/Nested"));
        Files.writeString(nested.resolve(PendingRemoval.MARKER), "x");
        Path pending = tmp.resolve("pending.txt");
        Files.writeString(pending, String.join("\n", marked.toString(), unmarked.toString(), outside.toString(),
                nested.toString(), mods.resolve("Gone").toString()) + "\n", StandardCharsets.UTF_8);

        PendingRemoval.cleanUp(pending, mods);

        assertFalse(Files.exists(marked), "the marked leftover goes");
        assertTrue(Files.isRegularFile(unmarked.resolve("mod.info")), "a folder without the marker stays");
        assertTrue(Files.exists(outside), "nothing outside the mods folder is touched");
        assertTrue(Files.exists(nested), "only folders directly inside the mods folder");
        assertFalse(Files.exists(pending), "nothing left to do");
    }
}
