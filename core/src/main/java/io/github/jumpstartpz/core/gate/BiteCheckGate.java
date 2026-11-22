package io.github.jumpstartpz.core.gate;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Index;
import io.github.bitecheckpz.core.Scanner;
import io.github.bitecheckpz.core.Verifier;
import io.github.bitecheckpz.launcher.LauncherState;
import io.github.bitecheckpz.launcher.ZombieBuddyApprovals;

/**
 * Decides whether a Java mod jar may be loaded while the game runs, with the same rules as the
 * BiteCheck launcher at start, and records the decision where ZombieBuddy reads it.
 *
 * <table>
 *   <caption>Verdict per jar</caption>
 *   <tr><th>BiteCheck status</th><th>Verdict</th></tr>
 *   <tr><td>VERIFIED (index current)</td><td>ALLOW</td></tr>
 *   <tr><td>UNVERIFIED, local scan without hard findings</td><td>CONFIRM: the player confirms once</td></tr>
 *   <tr><td>UNVERIFIED with hard findings or unreadable, or FLAGGED</td><td>CONFIRM_TWICE</td></tr>
 *   <tr><td>BLOCKED, INVALID</td><td>REFUSE, whatever the player says</td></tr>
 * </table>
 *
 * An index older than BiteCheck allows still blocks, but no longer verifies. A jar the player
 * already allowed in mod_approvals.json (unverified) or allowed anyway in BiteCheck (flagged or
 * suspicious) is not asked about again.
 */
public final class BiteCheckGate {

    public enum Verdict { ALLOW, CONFIRM, CONFIRM_TWICE, REFUSE }

    /** What the player said about a jar that needs confirmation. */
    public enum Consent { NONE, CONFIRMED, CONFIRMED_TWICE }

    /** The checked facts about one jar. {@code scan} is set only for unverified jars. */
    public record Check(Path jar, String sha256, Verifier.Status status, String info, boolean suspicious,
            Scanner.Report scan, Verdict verdict) {

        /** Whether the given consent is enough to load this jar. */
        public boolean allows(Consent consent) {
            return switch (verdict) {
                case ALLOW -> true;
                case CONFIRM -> consent != Consent.NONE;
                case CONFIRM_TWICE -> consent == Consent.CONFIRMED_TWICE;
                case REFUSE -> false;
            };
        }
    }

    private final IndexSource indexSource;
    private final Path zombieBuddyDir;
    private final Path biteCheckDir;
    private final Supplier<Instant> clock;

    /**
     * @param zombieBuddyDir ZombieBuddy's config dir (holds mod_approvals.json)
     * @param biteCheckDir   BiteCheck's dir (holds state.toml with "allowed anyway" decisions)
     */
    public BiteCheckGate(IndexSource indexSource, Path zombieBuddyDir, Path biteCheckDir, Supplier<Instant> clock) {
        this.indexSource = indexSource;
        this.zombieBuddyDir = zombieBuddyDir;
        this.biteCheckDir = biteCheckDir;
        this.clock = clock;
    }

    public IndexSource indexSource() {
        return indexSource;
    }

    public Check check(Path jar) throws IOException {
        String sha = Hashing.sha256Hex(jar);
        Optional<Index> index = indexSource.index();
        Index.Entry entry = index.map(i -> i.lookup(sha))
                .orElse(new Index.Entry(Verifier.Status.UNVERIFIED, "registry index not available"));
        Verifier.Status status = entry.status();
        String info = entry.label();
        if (status == Verifier.Status.VERIFIED && index.get().isStale(clock.get())) {
            status = Verifier.Status.UNVERIFIED;
            info = "index is out of date, cannot confirm verification";
        }

        Scanner.Report scan = null;
        boolean suspicious = false;
        if (status == Verifier.Status.UNVERIFIED) {
            try {
                scan = Scanner.scan(jar);
            } catch (IOException | RuntimeException e) {
                scan = null;
            }
            suspicious = scan == null || scan.hasHardFindings();
            info = info + "; " + describe(scan);
        }

        Verdict verdict = switch (status) {
            case VERIFIED -> Verdict.ALLOW;
            case BLOCKED, INVALID -> Verdict.REFUSE;
            case FLAGGED -> Verdict.CONFIRM_TWICE;
            case UNVERIFIED -> suspicious ? Verdict.CONFIRM_TWICE : Verdict.CONFIRM;
        };
        if (verdict == Verdict.CONFIRM_TWICE && allowedAnyway(sha)) {
            verdict = Verdict.ALLOW;
            info = info + "; allowed anyway earlier";
        } else if (verdict == Verdict.CONFIRM && Boolean.TRUE.equals(approvals().decision(sha))) {
            verdict = Verdict.ALLOW;
            info = info + "; allowed earlier";
        }
        return new Check(jar, sha, status, info, suspicious, scan, verdict);
    }

    /**
     * Records that the jar may load: an allow decision in mod_approvals.json, and for a flagged or
     * suspicious jar also "allowed anyway" in BiteCheck's state, so the launcher keeps it allowed at
     * the next start instead of denying it again. Refuses when the consent is not enough.
     */
    public void approve(Check check, String modId, String workshopId, Consent consent) throws IOException {
        if (!check.allows(consent)) {
            throw new IOException(check.jar().getFileName() + ": " + check.verdict() + " needs more than " + consent);
        }
        if (check.verdict() == Verdict.CONFIRM_TWICE) {
            LauncherState state = LauncherState.load(biteCheckDir.resolve("state.toml"));
            state.allowedAnyway().put(check.sha256(), modId + " " + check.jar().getFileName() + " (allowed in game)");
            state.save();
        }
        ZombieBuddyApprovals approvals = approvals();
        approvals.set(modId, workshopId, check.sha256(), true);
        approvals.save();
    }

    /** Records that the jar must not load, for example after BiteCheck blocked it. */
    public void deny(Check check, String modId, String workshopId) throws IOException {
        ZombieBuddyApprovals approvals = approvals();
        approvals.set(modId, workshopId, check.sha256(), false);
        approvals.save();
    }

    private ZombieBuddyApprovals approvals() throws IOException {
        return ZombieBuddyApprovals.load(zombieBuddyDir);
    }

    private boolean allowedAnyway(String sha) throws IOException {
        return LauncherState.load(biteCheckDir.resolve("state.toml")).allowedAnyway().containsKey(sha);
    }

    private static String describe(Scanner.Report scan) {
        if (scan == null) {
            return "local scan: jar could not be read";
        }
        Set<String> hard = new LinkedHashSet<>();
        Set<String> soft = new LinkedHashSet<>();
        for (Scanner.Finding f : scan.findings()) {
            (f.severity() == Scanner.Severity.HARD ? hard : soft).add(f.rule());
        }
        if (!hard.isEmpty()) {
            return "local scan found: " + String.join(", ", hard);
        }
        return soft.isEmpty() ? "local scan: no findings" : "local scan, check: " + String.join(", ", soft);
    }
}
