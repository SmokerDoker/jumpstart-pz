package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * What a mod brings, for its label: Java (a jar ZombieBuddy loads), Lua (Lua files the game runs) or
 * only data (item scripts, textures, maps, ...: nothing that runs code). BiteCheck checks only jars;
 * Lua runs unchecked, as with every Lua mod in the game.
 */
enum ModKind {
    JAVA, LUA, DATA;

    /** The folder of a mod (with its version folders), and whether it has a jar. */
    static ModKind of(Path modDir, boolean java) {
        if (java) {
            return JAVA;
        }
        return hasLua(modDir) ? LUA : DATA;
    }

    /** A .lua file under a media/lua folder of the mod (any version folder). */
    static boolean hasLua(Path modDir) {
        if (modDir == null || !Files.isDirectory(modDir)) {
            return true; // unknown: say Lua, the careful word
        }
        try (Stream<Path> files = Files.walk(modDir, 12)) {
            return files.anyMatch(f -> {
                String p = f.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                return p.endsWith(".lua") && p.contains("/media/lua/");
            });
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }
}
