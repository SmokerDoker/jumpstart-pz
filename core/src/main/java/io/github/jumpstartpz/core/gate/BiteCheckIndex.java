package io.github.jumpstartpz.core.gate;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        try {
            Config config = Config.load(biteCheckDir.resolve("config.toml"));
            LauncherState state = LauncherState.load(biteCheckDir.resolve("state.toml"));
            URI url = config.url("index_url").orElse(URI.create(IndexClient.DEFAULT_URL));
            IndexClient client = new IndexClient(url, IndexClient.registryKey(config),
                    IndexClient.timestampKey(config), biteCheckDir.resolve("cache"), state);
            IndexClient.Outcome outcome = client.fetch(clock.get());
            notes.addAll(outcome.notes());
            index = outcome.index();
            state.save(); // keeps the rollback protection shared with the launcher
        } catch (IOException e) {
            notes.add("BiteCheck index not available: " + e.getMessage());
            index = Optional.empty();
        }
    }

    @Override
    public synchronized List<String> notes() {
        return List.copyOf(notes);
    }
}
