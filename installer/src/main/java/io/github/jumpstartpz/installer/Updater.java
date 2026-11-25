package io.github.jumpstartpz.installer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Jumpstart's automatic updates: the newest release on GitHub (pre-releases such as test-kit are
 * never taken), only if its {@code jumpstart-release.txt} is signed with {@link ReleaseKey}, and
 * every downloaded file only if its SHA-256 matches that signed manifest. So neither a changed
 * download nor a changed release page can put code on a player's computer without the signing key.
 */
final class Updater {
    static final URI RELEASES = URI.create("https://github.com/SmokerDoker/jumpstart-pz/releases/");
    static final String MANIFEST = "jumpstart-release.txt";

    /** A signed release. */
    record Release(String version, String tag, Instant built, String payload, String payloadSha256,
            String setup, String setupSha256) {

        LocalDate builtDay() {
            return built.atZone(ZoneOffset.UTC).toLocalDate();
        }
    }

    private final URI releases;
    private final PublicKey key;
    private final HttpClient http;

    Updater(URI releases, String publicKey) {
        this.releases = releases;
        try {
            this.key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available in this Java", e);
        }
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** Jumpstart's releases on GitHub; -Djumpstart.releases=<url> for tests. */
    static Updater forJumpstart() {
        String url = System.getProperty("jumpstart.releases");
        return new Updater(url == null || url.isBlank() ? RELEASES : URI.create(url.endsWith("/") ? url : url + "/"), ReleaseKey.PUBLIC);
    }

    /** The newest signed release; empty if there is none yet. Throws if a manifest is there but not validly signed. */
    Optional<Release> latest() throws IOException {
        Optional<byte[]> manifest = get(releases.resolve("latest/download/" + MANIFEST), Duration.ofSeconds(15));
        if (manifest.isEmpty()) {
            return Optional.empty();
        }
        byte[] sig = get(releases.resolve("latest/download/" + MANIFEST + ".sig"), Duration.ofSeconds(15))
                .orElseThrow(() -> new IOException(MANIFEST + " has no signature"));
        return Optional.of(verify(manifest.get(), sig));
    }

    /** The manifest, if the signature is valid for it. */
    Release verify(byte[] manifest, byte[] signature) throws IOException {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(manifest);
            if (!s.verify(Base64.getDecoder().decode(new String(signature, StandardCharsets.US_ASCII).strip()))) {
                throw new IOException(MANIFEST + ": the signature is not valid; not installing it");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException(MANIFEST + ": the signature cannot be checked: " + e.getMessage(), e);
        }
        return parse(new String(manifest, StandardCharsets.UTF_8));
    }

    static Release parse(String text) throws IOException {
        Map<String, String> v = new HashMap<>();
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                v.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        if (!"1".equals(v.get("format"))) {
            throw new IOException(MANIFEST + ": unknown format " + v.get("format"));
        }
        try {
            Release r = new Release(require(v, "version"), require(v, "tag"), Instant.parse(require(v, "built")),
                    name(require(v, "payload")), sha(require(v, "payload.sha256")), name(require(v, "setup")), sha(require(v, "setup.sha256")));
            if (!Versions.isRelease(r.version()) || !r.tag().equals("v" + r.version())) {
                throw new IOException(MANIFEST + ": version " + r.version() + " / tag " + r.tag() + " do not fit");
            }
            return r;
        } catch (RuntimeException e) {
            throw new IOException(MANIFEST + ": " + e.getMessage(), e);
        }
    }

    /** Downloads one file of the release into {@code dir} and checks its SHA-256. */
    Path download(Release r, String name, String sha256, Path dir) throws IOException {
        byte[] data = get(releases.resolve("download/" + r.tag() + "/" + name), Duration.ofMinutes(5))
                .orElseThrow(() -> new IOException(name + " is missing in release " + r.tag()));
        String actual = sha256(data);
        if (!actual.equals(sha256)) {
            throw new IOException(name + ": SHA-256 " + actual + " is not the signed " + sha256 + "; not installing it");
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.write(file, data);
        return file;
    }

    /** Downloads and unpacks the release's payload (Jumpstart.jar and the fork's mod folder). */
    Payload payload(Release r) throws IOException {
        Path dir = Files.createTempDirectory("jumpstart-update");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Payload.deleteQuietly(dir)));
        Path zip = download(r, r.payload(), r.payloadSha256(), dir);
        Path unpacked = dir.resolve("payload");
        try (InputStream in = Files.newInputStream(zip)) {
            Payload.unzip(in, unpacked);
        }
        Payload p = Payload.of(unpacked);
        if (!p.version().equals(r.version())) {
            // the installed version would never match the release, and every start would update again
            throw new IOException(r.payload() + " holds version " + p.version() + ", the signed release says " + r.version());
        }
        return p;
    }

    private Optional<byte[]> get(URI uri, Duration timeout) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("User-Agent", "Jumpstart-updater").GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new IOException(uri + ": HTTP " + response.statusCode());
            }
            return Optional.of(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static String require(Map<String, String> v, String key) {
        String value = v.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(key + " is missing");
        }
        return value;
    }

    /** A plain file name, so a manifest cannot point outside the release. */
    private static String name(String s) {
        if (!s.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("bad file name " + s);
        }
        return s;
    }

    private static String sha(String s) {
        if (!s.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("bad SHA-256 " + s);
        }
        return s;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
