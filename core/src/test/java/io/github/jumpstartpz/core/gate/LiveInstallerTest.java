package io.github.jumpstartpz.core.gate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Keys;
import io.github.bitecheckpz.core.Signatures;
import io.github.bitecheckpz.core.Verifier;
import io.github.bitecheckpz.launcher.LauncherState;
import io.github.bitecheckpz.launcher.ZombieBuddyApprovals;
import io.github.jumpstartpz.core.HotswapBackend;
import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.gate.BiteCheckGate.Consent;
import io.github.jumpstartpz.core.gate.fixtures.CleanFixtureMod;
import io.github.jumpstartpz.core.gate.fixtures.ShadyFixtureMod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The BiteCheck gate end to end with BiteCheck's real code: a registry index signed with a test key
 * and fetched by BiteCheck's IndexClient from a file: URL, BiteCheck's scanner on real jars, and
 * ZombieBuddy's mod_approvals.json written by BiteCheck's ZombieBuddyApprovals. Only the loader is
 * faked.
 */
class LiveInstallerTest {
    private static final Instant GENERATED = Instant.parse("2026-11-20T10:00:00Z");

    @TempDir
    Path tmp;

    private Path biteCheckDir;
    private Path zombieBuddyDir;
    private Path verifiedJar;
    private Path cleanJar;
    private Path shadyJar;
    private Path flaggedJar;
    private Path blockedJar;
    private FakeBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        biteCheckDir = Files.createDirectories(tmp.resolve("bitecheck"));
        zombieBuddyDir = Files.createDirectories(tmp.resolve("zombie_buddy"));
        // five different jars: the same class under different entry names gives different hashes
        verifiedJar = jarOf("verified.jar", CleanFixtureMod.class, "a/");
        cleanJar = jarOf("clean.jar", CleanFixtureMod.class, "b/");
        shadyJar = jarOf("shady.jar", ShadyFixtureMod.class, "c/");
        flaggedJar = jarOf("flagged.jar", CleanFixtureMod.class, "d/");
        blockedJar = jarOf("blocked.jar", CleanFixtureMod.class, "e/");
        writeSignedIndex();
        backend = new FakeBackend(zombieBuddyDir);
    }

    @Test
    void loadsAVerifiedJarWithoutAskingAndRecordsTheApproval() throws Exception {
        LiveInstaller.Outcome outcome = installer(GENERATED.plus(Duration.ofHours(1)))
                .install(verifiedJar, "pkg", "VerifiedMod", null, Consent.NONE);

        assertEquals(LiveInstaller.Kind.DONE, outcome.kind(), outcome.message());
        assertEquals(Verifier.Status.VERIFIED, outcome.check().status());
        assertEquals(List.of("load " + verifiedJar.getFileName()), backend.calls);
        assertEquals(Boolean.TRUE, ZombieBuddyApprovals.load(zombieBuddyDir).decision(Hashing.sha256Hex(verifiedJar)));
    }

    @Test
    void asksOnceForAnUnverifiedCleanJar() throws Exception {
        LiveInstaller installer = installer(GENERATED);

        LiveInstaller.Outcome asked = installer.install(cleanJar, "pkg", "CleanMod", null, Consent.NONE);
        assertEquals(LiveInstaller.Kind.NEEDS_CONFIRMATION, asked.kind(), asked.message());
        assertEquals(BiteCheckGate.Verdict.CONFIRM, asked.check().verdict());
        assertTrue(backend.calls.isEmpty());
        assertNull(ZombieBuddyApprovals.load(zombieBuddyDir).decision(Hashing.sha256Hex(cleanJar)));

        LiveInstaller.Outcome done = installer.install(cleanJar, "pkg", "CleanMod", null, Consent.CONFIRMED);
        assertEquals(LiveInstaller.Kind.DONE, done.kind(), done.message());

        // allowed once, not asked again
        assertEquals(BiteCheckGate.Verdict.ALLOW, installer.install(cleanJar, "pkg", "CleanMod", null, Consent.NONE).check().verdict());
    }

    @Test
    void asksTwiceForASuspiciousJarAndKeepsItAllowedForTheLauncher() throws Exception {
        LiveInstaller installer = installer(GENERATED);

        LiveInstaller.Outcome once = installer.install(shadyJar, "pkg", "ShadyMod", null, Consent.CONFIRMED);
        assertEquals(LiveInstaller.Kind.NEEDS_CONFIRMATION, once.kind(), once.message());
        assertTrue(once.check().suspicious());
        assertTrue(once.check().info().contains("process"), once.check().info());

        LiveInstaller.Outcome twice = installer.install(shadyJar, "pkg", "ShadyMod", null, Consent.CONFIRMED_TWICE);
        assertEquals(LiveInstaller.Kind.DONE, twice.kind(), twice.message());
        String sha = Hashing.sha256Hex(shadyJar);
        assertTrue(LauncherState.load(biteCheckDir.resolve("state.toml")).allowedAnyway().containsKey(sha));
        assertEquals(Boolean.TRUE, ZombieBuddyApprovals.load(zombieBuddyDir).decision(sha));
    }

    @Test
    void asksThePlayerOnlyWhenBiteCheckWantsIt() throws Exception {
        LiveInstaller installer = installer(GENERATED);
        List<BiteCheckGate.Check> asked = new ArrayList<>();
        LiveInstaller.ConsentSource player = check -> {
            asked.add(check);
            return Consent.CONFIRMED;
        };

        assertEquals(LiveInstaller.Kind.DONE, installer.install(verifiedJar, "pkg", "VerifiedMod", null, player).kind());
        assertTrue(asked.isEmpty(), "a verified jar needs no question");

        assertEquals(LiveInstaller.Kind.DONE, installer.install(cleanJar, "pkg", "CleanMod", null, player).kind());
        assertEquals(1, asked.size());
        assertEquals(BiteCheckGate.Verdict.CONFIRM, asked.get(0).verdict());
        assertEquals(cleanJar, asked.get(0).jar(), "the player is asked about the jar that gets approved");
    }

    @Test
    void aPlayerWhoSaysNoGetsNothingLoadedAndTheLauncherKeepsItOff() throws Exception {
        LiveInstaller.Outcome outcome = installer(GENERATED).install(cleanJar, "pkg", "CleanMod", null,
                (LiveInstaller.ConsentSource) check -> null);
        assertEquals(LiveInstaller.Kind.DECLINED, outcome.kind());
        assertTrue(backend.calls.isEmpty());
        String sha = Hashing.sha256Hex(cleanJar);
        assertEquals(Boolean.FALSE, ZombieBuddyApprovals.load(zombieBuddyDir).decision(sha));
        LauncherState state = LauncherState.load(biteCheckDir.resolve("state.toml"));
        assertTrue(state.refused().containsKey(sha), "the launcher does not ask again");
        assertFalse(state.allowedAnyway().containsKey(sha));

        // installing it again in game asks again, and a yes replaces the no
        LiveInstaller.Outcome again = installer(GENERATED).install(cleanJar, "pkg", "CleanMod", null, Consent.CONFIRMED);
        assertEquals(LiveInstaller.Kind.DONE, again.kind(), again.message());
        state = LauncherState.load(biteCheckDir.resolve("state.toml"));
        assertFalse(state.refused().containsKey(sha));
        assertTrue(state.allowedAnyway().containsKey(sha));
    }

    @Test
    void anUnverifiedJarAllowedInGameIsNotAskedAboutAgainByTheLauncher() throws Exception {
        LiveInstaller.Outcome done = installer(GENERATED).install(cleanJar, "pkg", "CleanMod", null, Consent.CONFIRMED);
        assertEquals(LiveInstaller.Kind.DONE, done.kind(), done.message());
        LauncherState state = LauncherState.load(biteCheckDir.resolve("state.toml"));
        assertFalse(state.tampered());
        assertTrue(state.allowedAnyway().containsKey(Hashing.sha256Hex(cleanJar)),
                "the launcher reads allowed_anyway, not mod_approvals.json");
    }

    @Test
    void modApprovalsAloneDoNotAllowAJar() throws Exception {
        // any mod can write mod_approvals.json; BiteCheck does not trust it, so the gate does not either
        ZombieBuddyApprovals approvals = ZombieBuddyApprovals.load(zombieBuddyDir);
        approvals.set("CleanMod", null, Hashing.sha256Hex(cleanJar), true);
        approvals.save();
        LiveInstaller.Outcome outcome = installer(GENERATED).install(cleanJar, "pkg", "CleanMod", null, Consent.NONE);
        assertEquals(LiveInstaller.Kind.NEEDS_CONFIRMATION, outcome.kind(), outcome.message());
    }

    @Test
    void keepsTheLaunchersAnswersAndItsCheckValue() throws Exception {
        // answers the launcher saved before the game started
        String refusedSha = "a".repeat(64);
        LauncherState before = LauncherState.load(biteCheckDir.resolve("state.toml"));
        before.refused().put(refusedSha, "OtherMod - not allowed by the player");
        before.trusted().put("OtherMod/other.jar",
                new LauncherState.Trusted("OtherMod/other.jar", "b".repeat(64), "1.0", null));
        before.save();

        installer(GENERATED).install(cleanJar, "pkg", "CleanMod", null, Consent.CONFIRMED);

        LauncherState after = LauncherState.load(biteCheckDir.resolve("state.toml"));
        assertFalse(after.tampered(), "Jumpstart's save carries BiteCheck's check value");
        assertTrue(after.refused().containsKey(refusedSha));
        assertTrue(after.trusted().containsKey("OtherMod/other.jar"));
        assertTrue(after.allowedAnyway().containsKey(Hashing.sha256Hex(cleanJar)));
    }

    @Test
    void doesNotHideAnswersChangedOutsideBiteCheck() throws Exception {
        LauncherState launcher = LauncherState.load(biteCheckDir.resolve("state.toml"));
        launcher.refused().put("a".repeat(64), "OtherMod - not allowed by the player");
        launcher.save();
        Path file = biteCheckDir.resolve("state.toml");
        Files.writeString(file, Files.readString(file).replace("not allowed", "edited"));

        installer(GENERATED).gate().check(verifiedJar); // fetches the index, as in game

        assertTrue(LauncherState.load(file).tampered(), "the launcher can still warn about it");
    }

    @Test
    void oneConfirmationIsNotEnoughForASuspiciousJarEvenFromThePlayer() throws Exception {
        LiveInstaller.Outcome outcome = installer(GENERATED).install(shadyJar, "pkg", "ShadyMod", null,
                (LiveInstaller.ConsentSource) check -> Consent.CONFIRMED);
        assertEquals(LiveInstaller.Kind.NEEDS_CONFIRMATION, outcome.kind());
        assertTrue(backend.calls.isEmpty());
    }

    @Test
    void asksTwiceForAFlaggedJar() throws Exception {
        LiveInstaller.Outcome outcome = installer(GENERATED).install(flaggedJar, "pkg", "FlaggedMod", null, Consent.CONFIRMED);
        assertEquals(LiveInstaller.Kind.NEEDS_CONFIRMATION, outcome.kind());
        assertEquals(Verifier.Status.FLAGGED, outcome.check().status());
        assertEquals(BiteCheckGate.Verdict.CONFIRM_TWICE, outcome.check().verdict());
    }

    @Test
    void refusesABlockedJarWhateverThePlayerSays() throws Exception {
        LiveInstaller.Outcome outcome = installer(GENERATED).install(blockedJar, "pkg", "BlockedMod", null, Consent.CONFIRMED_TWICE);
        assertEquals(LiveInstaller.Kind.REFUSED, outcome.kind());
        assertTrue(backend.calls.isEmpty());
        assertNull(ZombieBuddyApprovals.load(zombieBuddyDir).decision(Hashing.sha256Hex(blockedJar)));
    }

    @Test
    void anOutdatedIndexNoLongerVerifies() throws Exception {
        LiveInstaller.Outcome outcome = installer(GENERATED.plus(Duration.ofDays(8)))
                .install(verifiedJar, "pkg", "VerifiedMod", null, Consent.NONE);
        assertEquals(LiveInstaller.Kind.NEEDS_CONFIRMATION, outcome.kind());
        assertEquals(Verifier.Status.UNVERIFIED, outcome.check().status());
        assertTrue(outcome.check().info().contains("out of date"), outcome.check().info());
    }

    @Test
    void withoutAnIndexEveryJarIsUnverified() throws Exception {
        Files.delete(biteCheckDir.resolve("index/index.toml"));
        LiveInstaller.Outcome outcome = installer(GENERATED).install(verifiedJar, "pkg", "VerifiedMod", null, Consent.NONE);
        assertEquals(LiveInstaller.Kind.NEEDS_CONFIRMATION, outcome.kind());
        assertEquals(Verifier.Status.UNVERIFIED, outcome.check().status());
    }

    @Test
    void saysWhatIsMissingWhenTheLoaderCannotSwitchLive() throws Exception {
        backend.unavailable = "install the Jumpstart fork of ZombieBuddy";
        LiveInstaller.Outcome outcome = installer(GENERATED).install(verifiedJar, "pkg", "VerifiedMod", null, Consent.NONE);
        assertEquals(LiveInstaller.Kind.FAILED, outcome.kind());
        assertEquals("install the Jumpstart fork of ZombieBuddy", outcome.message());
        assertFalse(Files.exists(zombieBuddyDir.resolve("mod_approvals.json")));
    }

    @Test
    void updatesThroughTheBackend() throws Exception {
        LiveInstaller.Outcome outcome = installer(GENERATED).update(verifiedJar, "pkg", "VerifiedMod", null, Consent.NONE);
        assertEquals(LiveInstaller.Kind.DONE, outcome.kind(), outcome.message());
        assertEquals(List.of("update " + verifiedJar.getFileName()), backend.calls);
    }

    private LiveInstaller installer(Instant now) {
        BiteCheckIndex index = new BiteCheckIndex(biteCheckDir, () -> now);
        return new LiveInstaller(new BiteCheckGate(index, zombieBuddyDir, biteCheckDir, () -> now), backend);
    }

    /** An index like the registry's, signed with a fresh key that config.toml pins for this test. */
    private void writeSignedIndex() throws Exception {
        KeyPair keys = Keys.generate();
        String keyId = Keys.keyId(keys.getPublic());
        Files.writeString(biteCheckDir.resolve("test-registry.pub"), Keys.encodePublic(keys.getPublic(), "test"));
        Path index = Files.createDirectories(biteCheckDir.resolve("index")).resolve("index.toml");
        Files.writeString(index, String.join("\n",
                "format = 1",
                "generated_at = \"" + GENERATED + "\"",
                "registry_commit = \"test\"",
                "",
                "[verified]",
                Hashing.sha256Hex(verifiedJar) + " = \"verified-mod 1.0\"",
                "",
                "[flagged]",
                Hashing.sha256Hex(flaggedJar) + " = \"flagged-mod 1.0: under review\"",
                "",
                "[blocked]",
                Hashing.sha256Hex(blockedJar) + " = \"malware\"",
                ""), StandardCharsets.UTF_8);
        Signatures.sign(index, new Keys.PrivateKeyInfo(keys.getPrivate(), keyId));
        Files.writeString(biteCheckDir.resolve("config.toml"),
                "index_url = \"index/index.toml\"\nregistry_key = \"test-registry.pub\"\n", StandardCharsets.UTF_8);
    }

    /** A jar with one class file from the test classpath, stored under a prefix to vary its hash. */
    private Path jarOf(String name, Class<?> type, String prefix) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        Path jar = tmp.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(out);
             InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            jos.putNextEntry(new JarEntry(prefix + resource));
            jos.write(in.readAllBytes());
            jos.closeEntry();
        }
        return jar;
    }

    private static final class FakeBackend implements HotswapBackend {
        final List<String> calls = new ArrayList<>();
        final Path approvalsDir;
        String unavailable;

        FakeBackend(Path approvalsDir) {
            this.approvalsDir = approvalsDir;
        }

        @Override
        public String unavailableReason() {
            return unavailable;
        }

        @Override
        public String load(Path jar, String javaPackage, String modId) {
            calls.add("load " + jar.getFileName());
            return null;
        }

        @Override
        public String update(Path jar, String javaPackage, String modId) {
            calls.add("update " + jar.getFileName());
            return null;
        }

        @Override
        public Path approvalsDir() {
            return approvalsDir;
        }
    }
}
