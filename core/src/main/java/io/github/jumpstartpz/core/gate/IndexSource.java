package io.github.jumpstartpz.core.gate;

import java.util.List;
import java.util.Optional;

import io.github.bitecheckpz.core.Index;

/** Where the gate gets BiteCheck's signed registry index from. */
public interface IndexSource {

    /** The checked index, or empty if none is usable (then every jar counts as unverified). */
    Optional<Index> index();

    /** Fetches the index again, for example to notice a mod that was flagged in the meantime. */
    void refresh();

    /** What happened while getting the index, for the player and the log. */
    List<String> notes();
}
