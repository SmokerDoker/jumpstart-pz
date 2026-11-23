package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Icons from mod.info are only files inside the mod's own folder. */
class ModImageTest {
    @TempDir
    Path tmp;

    @Test
    void onlyFilesInsideTheModFolder() throws IOException {
        Path mod = Files.createDirectories(tmp.resolve("mods/Knox/42"));
        Files.writeString(mod.resolve("icon.png"), "png");
        Files.writeString(tmp.resolve("secret.png"), "png");
        String root = tmp.resolve("mods/Knox").toString();
        String inside = mod.resolve("icon.png").toRealPath().toString();

        assertEquals(inside, GameActions.imageOf(root, mod.resolve("icon.png").toString()), "the game's absolute path");
        assertEquals(inside, GameActions.imageOf(root, "42/icon.png"), "relative to the mod folder");
        assertNull(GameActions.imageOf(root, "../../secret.png"), "outside with ..");
        assertNull(GameActions.imageOf(root, tmp.resolve("secret.png").toString()), "outside, absolute");
        assertNull(GameActions.imageOf(root, "\\\\attacker.example\\share\\icon.png"), "a network path");
        assertNull(GameActions.imageOf(root, "//attacker.example/share/icon.png"), "a network path, other slashes");
        assertNull(GameActions.imageOf(root, "https://attacker.example/icon.png"));
        assertNull(GameActions.imageOf(root, "42/missing.png"));
        assertNull(GameActions.imageOf(root, ""));
    }
}
