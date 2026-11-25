package io.github.jumpstartpz.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** The updater against a local stand-in for GitHub's release downloads, with a test key. */
class UpdaterTest {
    @TempDir
    Path tmp;

    private final Map<String, byte[]> files = new HashMap<>();
    private HttpServer server;
    private URI base;
    private KeyPair key;

    @BeforeEach
    void start() throws IOException, GeneralSecurityException {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = files.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/releases/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Updater updater() {
        return new Updater(base, Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
    }

    private static byte[] payloadZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String[] e : new String[][] {{"VERSION", "0.2.0\n"}, {"Jumpstart.jar", "jar"},
                    {"mods/ZombieBuddy/42/mod.info", "id=ZombieBuddy\n"}}) {
                zip.putNextEntry(new ZipEntry(e[0]));
                zip.write(e[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private String sign(byte[] data) throws GeneralSecurityException {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key.getPrivate());
        s.update(data);
        return Base64.getEncoder().encodeToString(s.sign()) + "\n";
    }

    /** Publishes release 0.2.0 like the Kits workflow does. */
    private byte[] publish(byte[] payload) throws GeneralSecurityException {
        String manifest = "format=1\nversion=0.2.0\ntag=v0.2.0\nbuilt=2026-11-24T10:00:00Z\n"
                + "payload=jumpstart-payload.zip\npayload.sha256=" + Updater.sha256(payload) + "\n"
                + "setup=JumpstartSetup.jar\nsetup.sha256=" + Updater.sha256(new byte[] {1}) + "\n";
        byte[] m = manifest.getBytes(StandardCharsets.UTF_8);
        files.put("/releases/latest/download/jumpstart-release.txt", m);
        files.put("/releases/latest/download/jumpstart-release.txt.sig", sign(m).getBytes(StandardCharsets.US_ASCII));
        files.put("/releases/download/v0.2.0/jumpstart-payload.zip", payload);
        files.put("/releases/download/v0.2.0/JumpstartSetup.jar", new byte[] {1});
        return m;
    }

    @Test
    void noReleaseYet() throws IOException {
        assertEquals(Optional.empty(), updater().latest());
    }

    @Test
    void signedReleaseInstalls() throws Exception {
        byte[] payload = payloadZip();
        publish(payload);
        Updater.Release r = updater().latest().orElseThrow();
        assertEquals("0.2.0", r.version());
        assertEquals("2026-11-24", r.builtDay().toString());
        Payload p = updater().payload(r);
        assertEquals("0.2.0", p.version());
        assertTrue(Files.isRegularFile(p.jumpstartJar()));
    }

    @Test
    void changedManifestIsRefused() throws Exception {
        publish(payloadZip());
        byte[] m = files.get("/releases/latest/download/jumpstart-release.txt");
        files.put("/releases/latest/download/jumpstart-release.txt",
                new String(m, StandardCharsets.UTF_8).replace("version=0.2.0\ntag=v0.2.0", "version=9.0.0\ntag=v9.0.0").getBytes(StandardCharsets.UTF_8));
        IOException e = assertThrows(IOException.class, () -> updater().latest());
        assertTrue(e.getMessage().contains("signature"), e.getMessage());
    }

    @Test
    void otherKeyIsRefused() throws Exception {
        publish(payloadZip());
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Updater stranger = new Updater(base, Base64.getEncoder().encodeToString(other.getPublic().getEncoded()));
        assertThrows(IOException.class, stranger::latest);
    }

    @Test
    void changedDownloadIsRefused() throws Exception {
        publish(payloadZip());
        Updater.Release r = updater().latest().orElseThrow();
        files.put("/releases/download/v0.2.0/jumpstart-payload.zip", "evil".getBytes(StandardCharsets.UTF_8));
        IOException e = assertThrows(IOException.class, () -> updater().payload(r));
        assertTrue(e.getMessage().contains("SHA-256"), e.getMessage());
    }

    @Test
    void manifestWithoutSignatureIsRefused() throws Exception {
        publish(payloadZip());
        files.remove("/releases/latest/download/jumpstart-release.txt.sig");
        assertThrows(IOException.class, () -> updater().latest());
    }

    @Test
    void theNewInstallerIsKeptAndCheckedBeforeUse() throws Exception {
        publish(payloadZip());
        System.setProperty("jumpstart.home", tmp.resolve("home").toString());
        try {
            Updater.Release r = updater().latest().orElseThrow();
            assertEquals(Optional.empty(), Bootstrap.newer());
            Bootstrap.fetch(updater(), r, line -> { });
            Bootstrap.Newer n = Bootstrap.newer().orElseThrow();
            assertEquals("0.2.0", n.version());
            assertTrue(Files.isRegularFile(n.jar()));
            // a changed file is never used
            Files.write(n.jar(), new byte[] {2});
            assertEquals(Optional.empty(), Bootstrap.newer());
            // in a build folder (no version) nothing takes over
            assertEquals(false, Bootstrap.delegate(new String[0]));
        } finally {
            System.clearProperty("jumpstart.home");
        }
    }

    @Test
    void theReleaseKeyIsUsable() {
        // the pinned public key parses as Ed25519
        Updater.forJumpstart();
        assertTrue(ReleaseKey.PUBLIC.startsWith("MCowBQYDK2Vw"));
    }

    @Test
    void manifestFileNamesStayInTheRelease() {
        assertThrows(IOException.class, () -> Updater.parse("format=1\nversion=0.2.0\ntag=v0.2.0\nbuilt=2026-11-24T10:00:00Z\n"
                + "payload=../x.zip\npayload.sha256=" + "0".repeat(64) + "\nsetup=a.jar\nsetup.sha256=" + "0".repeat(64) + "\n"));
    }
}
