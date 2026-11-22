package io.github.jumpstartpz.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import io.github.jumpstartpz.core.gate.BiteCheckGate;

/**
 * Installs or updates a Java mod while the game runs, always through BiteCheck: check the jar, ask
 * for the player's confirmation where BiteCheck requires it, record the approval for that exact hash,
 * then hand the jar to the loader. The loader hashes the file again and loads it only if that hash
 * is approved, so a jar swapped after the check is refused.
 */
public final class LiveInstaller {

    public enum Kind {
        /** Loaded (or updated) and running. */
        DONE,
        /** BiteCheck wants the player to confirm (once or twice); nothing was changed. */
        NEEDS_CONFIRMATION,
        /** BiteCheck refuses the jar; nothing was loaded. */
        REFUSED,
        /** Approved, but the loader could not load it, or the check itself failed. */
        FAILED
    }

    /** {@code check} is null when the jar could not even be checked. */
    public record Outcome(Kind kind, BiteCheckGate.Check check, String message) {
    }

    private final BiteCheckGate gate;
    private final HotswapBackend backend;

    public LiveInstaller(BiteCheckGate gate, HotswapBackend backend) {
        this.gate = Objects.requireNonNull(gate);
        this.backend = Objects.requireNonNull(backend);
    }

    /**
     * The installer the game uses: BiteCheck's index and state from {@code ~/.bitecheck} (or the
     * folder in the system property {@code jumpstart.bitecheckDir}), approvals
     * in ZombieBuddy's config dir, mods loaded by the Jumpstart fork of ZombieBuddy.
     */
    public static LiveInstaller forGame() {
        HotswapBackend backend = ZombieBuddyFork.current();
        // -Djumpstart.bitecheckDir=... points it at another BiteCheck folder (tests)
        String dir = System.getProperty("jumpstart.bitecheckDir");
        Path biteCheckDir = dir != null && !dir.isBlank() ? Path.of(dir)
                : Path.of(System.getProperty("user.home"), ".bitecheck");
        BiteCheckGate gate = new BiteCheckGate(new io.github.jumpstartpz.core.gate.BiteCheckIndex(biteCheckDir, java.time.Instant::now),
                backend.approvalsDir(), biteCheckDir, java.time.Instant::now);
        return new LiveInstaller(gate, backend);
    }

    public BiteCheckGate gate() {
        return gate;
    }

    /** A mod that is not loaded yet. */
    public Outcome install(Path jar, String javaPackage, String modId, String workshopId, BiteCheckGate.Consent consent) {
        return run(jar, javaPackage, modId, workshopId, consent, false);
    }

    /** A new version of a mod that is loaded now. */
    public Outcome update(Path jar, String javaPackage, String modId, String workshopId, BiteCheckGate.Consent consent) {
        return run(jar, javaPackage, modId, workshopId, consent, true);
    }

    private Outcome run(Path jar, String javaPackage, String modId, String workshopId,
                        BiteCheckGate.Consent consent, boolean update) {
        String unavailable = backend.unavailableReason();
        if (unavailable != null) {
            return new Outcome(Kind.FAILED, null, unavailable);
        }

        BiteCheckGate.Check check;
        try {
            check = gate.check(jar);
        } catch (IOException e) {
            return new Outcome(Kind.FAILED, null, "cannot check " + jar.getFileName() + ": " + e.getMessage());
        }
        if (check.verdict() == BiteCheckGate.Verdict.REFUSE) {
            return new Outcome(Kind.REFUSED, check, "BiteCheck: " + check.status() + " (" + check.info() + ")");
        }
        if (!check.allows(consent)) {
            String ask = check.verdict() == BiteCheckGate.Verdict.CONFIRM_TWICE ? "confirm twice" : "confirm";
            return new Outcome(Kind.NEEDS_CONFIRMATION, check,
                    "BiteCheck: " + check.status() + " (" + check.info() + "); the player must " + ask);
        }

        try {
            gate.approve(check, modId, workshopId, consent);
        } catch (IOException e) {
            return new Outcome(Kind.FAILED, check, "cannot record the approval: " + e.getMessage());
        }
        String problem = update ? backend.update(jar, javaPackage, modId) : backend.load(jar, javaPackage, modId);
        return problem == null
                ? new Outcome(Kind.DONE, check, modId + (update ? " updated" : " loaded") + " (" + check.status() + ")")
                : new Outcome(Kind.FAILED, check, problem);
    }
}
