package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModKindTest {
    @TempDir
    Path tmp;

    @Test
    void aJarMakesItJavaLuaFilesMakeItLuaElseItIsData() throws IOException {
        Path lua = tmp.resolve("Minimap");
        Files.createDirectories(lua.resolve("42/media/lua/client"));
        Files.writeString(lua.resolve("42/media/lua/client/ui.lua"), "print(1)");
        Path data = tmp.resolve("Guns");
        Files.createDirectories(data.resolve("common/media/scripts"));
        Files.writeString(data.resolve("common/media/scripts/items.txt"), "module X {}");
        Files.writeString(data.resolve("common/readme.lua"), "-- not under media/lua");

        assertEquals(ModKind.JAVA, ModKind.of(data, true));
        assertEquals(ModKind.LUA, ModKind.of(lua, false));
        assertEquals(ModKind.DATA, ModKind.of(data, false));
        assertEquals(ModKind.LUA, ModKind.of(tmp.resolve("missing"), false), "unknown: the careful word");
    }
}
