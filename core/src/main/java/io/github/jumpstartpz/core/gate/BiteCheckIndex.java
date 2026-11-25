package io.github.jumpstartpz.core.gate;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import io.github.bitecheckpz.core.Index;
import io.github.bitecheckpz.launcher.Config;
import io.github.bitecheckpz.launcher.IndexClient;
import io.github.bitecheckpz.launcher.LauncherState;

/**
 * BiteCheck's signed index, fetched and checked exactly like the BiteCheck launcher does it: same
 * pinned keys, same URL, same cache ({@code ~/.bitecheck/cache}) and the same rollback protection
 * ({@code newest_index} in {@code ~/.bitecheck/state.toml}). In game, Jumpstart extends what the
 * launcher checked before the start; it never trusts a weaker source.
 *
 * <p>The index is fetched once on first use and kept; {@link #refresh} fetches it again.
 */
public final class BiteCheckIndex implements IndexSource {
    private final Path biteCheckDir;
    private final Supplier<Instant> clock;
    private final List<String> notes = new ArrayList<>();
    private Optional<Index> index;

    public BiteCheckIndex(Path biteCheckDir, Supplier<Instant> clock) {
        this.biteCheckDir = biteCheckDir;
        this.clock = clock;
    }

    /** BiteCheck's folder of the current user, {@code ~/.bitecheck}. */
    public static BiteCheckIndex forUser() {
        return new BiteCheckIndex(Path.of(System.getProperty("user.home"), ".bitecheck"), Instant::now);
    }

    @Override
    public synchronized Optional<Index> index() {
        if (index == null) {
            refresh();
        }
        return index;
    }

    @Override
    public synchronized void refresh() {
        notes.clear();
        List<Path> keyFiles = new ArrayList<>();
        try {
            Config config = withPinnedKeys(Config.load(biteCheckDir.resolve("config.toml")), keyFiles);
            LauncherState state = LauncherState.load(biteCheckDir.resolve("state.toml"));
            URI url = config.url("index_url").orElse(URI.create(IndexClient.DEFAULT_URL));
            IndexClient client = new IndexClient(url, IndexClient.registryKey(config),
                    IndexClient.timestampKey(config), biteCheckDir.resolve("cache"), state);
            IndexClient.Outcome outcome = client.fetch(clock.get());
            notes.addAll(outcome.notes());
            index = outcome.index();
            // Keeps the rollback protection shared with the launcher. Not if the saved answers were
            // changed outside BiteCheck: saving would hide that, and the launcher warns about it.
            if (!state.tampered()) {
                state.save();
            }
        } catch (IOException e) {
            notes.add("BiteCheck index not available: " + e.getMessage());
            index = Optional.empty();
        } finally {
            for (Path file : keyFiles) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    // a temp file with a public key; nothing secret
                }
            }
        }
    }

    /**
     * The launcher reads its pinned keys as resources, which ZombieBuddy does not serve for mod
     * jars. Hand them over as files instead, unless BiteCheck's config names its own keys.
     */
    private static Config withPinnedKeys(Config config, List<Path> keyFiles) throws IOException {
        Map<String, String> pinned = new LinkedHashMap<>();
        if (config.string("registry_key").isEmpty()) {
            pinned.put("registry_key", writeKey(PinnedKeys.REGISTRY, keyFiles));
        }
        if (config.string("timestamp_key").isEmpty()) {
            pinned.put("timestamp_key", writeKey(PinnedKeys.TIMESTAMP, keyFiles));
        }
        return pinned.isEmpty() ? config : config.with(pinned);
    }

    private static String writeKey(String key, List<Path> keyFiles) throws IOException {
        Path file = Files.createTempFile("jumpstart-bitecheck", ".pub");
        keyFiles.add(file);
        Files.writeString(file, key + "\n", StandardCharsets.UTF_8);
        return file.toAbsolutePath().toString();
    }

    @Override
    public synchronized List<String> notes() {
        return List.copyOf(notes);
    }
}
