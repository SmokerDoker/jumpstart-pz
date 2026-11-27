package io.github.jumpstartpz.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.bitecheckpz.core.Hashing;

class JarCacheTest {
    @TempDir
    Path tmp;

    @Test
    void keepsEachVersionOnceByHashTheNewestFive() throws IOException, InterruptedException {
        JarCache cache = new JarCache(tmp.resolve("cache"));
        Path steam = tmp.resolve("workshop/123/mods/Guns/42/media/java/Guns.jar");
        Files.createDirectories(steam.getParent());
        String first = null;
        for (int v = 1; v <= 7; v++) {
            Files.writeString(steam, "version " + v);
            Path copy = cache.store("Guns", steam, null);
            assertEquals(Hashing.sha256Hex(steam), copy.getFileName().toString().replace(".jar", ""));
            if (v == 1) {
                first = Hashing.sha256Hex(steam);
            }
            Thread.sleep(1100); // versions are ordered by the second they were seen
        }
        List<JarCache.Version> versions = cache.versions("Guns");
        assertEquals(JarCache.KEEP, versions.size());
        assertEquals(Hashing.sha256Hex(steam), versions.get(0).sha256(), "newest first");
        assertFalse(cache.copy("Guns", first).isPresent(), "the oldest went");
        assertEquals(steam.toAbsolutePath().toString(), versions.get(0).source());

        Files.writeString(steam, "version 7"); // the same again: no second entry
        cache.store("Guns", steam, null);
        assertEquals(JarCache.KEEP, cache.versions("Guns").size());
    }

    @Test
    void aCopyThatWasChangedIsNotUsed() throws IOException {
        JarCache cache = new JarCache(tmp.resolve("cache"));
        Path jar = tmp.resolve("Mod.jar");
        Files.writeString(jar, "real");
        Path copy = cache.store("M", jar, null);
        String sha = Hashing.sha256Hex(jar);
        assertTrue(cache.copy("M", sha).isPresent());
        assertTrue(cache.contains(copy));
        assertFalse(cache.contains(jar));
        Files.writeString(copy, "tampered");
        assertFalse(cache.copy("M", sha).isPresent());
        cache.store("M", jar, sha); // made again
        assertTrue(cache.copy("M", sha).isPresent());
    }
}
