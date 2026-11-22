package io.github.jumpstartpz.core.gate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.bitecheckpz.core.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The generated constants are BiteCheck's keys, byte for byte, and parse as BiteCheck keys. */
class PinnedKeysTest {
    private static final Path KEYS = Path.of("..", "third_party", "bitecheck-pz", "keys");

    @TempDir
    Path tmp;

    @Test
    void registryKeyIsBiteChecks() throws Exception {
        assertSameKey(PinnedKeys.REGISTRY, KEYS.resolve("registry.pub"));
    }

    @Test
    void timestampKeyIsBiteChecks() throws Exception {
        assertSameKey(PinnedKeys.TIMESTAMP, KEYS.resolve("timestamp.pub"));
    }

    private void assertSameKey(String pinned, Path original) throws Exception {
        assertEquals(Files.readString(original, StandardCharsets.UTF_8).trim(), pinned);
        Path copy = tmp.resolve(original.getFileName());
        Files.writeString(copy, pinned + "\n", StandardCharsets.UTF_8);
        assertEquals(Keys.readPublic(original).id(), Keys.readPublic(copy).id());
    }
}
