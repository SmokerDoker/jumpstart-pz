import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Signs Jumpstart's releases for the automatic updates (Ed25519, from the JDK alone). The installer
 * installs a release by itself only if jumpstart-release.txt carries a valid signature from the key
 * pinned in installer/.../ReleaseKey.java.
 *
 * <pre>
 * java kit/ReleaseSign.java keygen &lt;private key file&gt;          new key pair; prints the public key
 * java kit/ReleaseSign.java manifest &lt;folder&gt; &lt;version&gt; &lt;tag&gt;  writes &lt;folder&gt;/jumpstart-release.txt
 * java kit/ReleaseSign.java sign &lt;file&gt;                        writes &lt;file&gt;.sig, key from $JUMPSTART_SIGNING_KEY
 * java kit/ReleaseSign.java verify &lt;file&gt; &lt;public key&gt;         checks &lt;file&gt;.sig
 * </pre>
 *
 * Keys are base64: the private key PKCS#8, the public key X.509 (SubjectPublicKeyInfo).
 */
public class ReleaseSign {
    public static void main(String[] args) throws Exception {
        switch (args.length == 0 ? "" : args[0]) {
            case "keygen" -> {
                Path out = Path.of(args[1]);
                if (Files.exists(out)) {
                    throw new IllegalStateException(out + " exists; not overwriting a signing key");
                }
                KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                Files.createDirectories(out.toAbsolutePath().getParent());
                Files.writeString(out, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n");
                System.out.println("private key: " + out.toAbsolutePath() + " (keep it secret, back it up)");
                System.out.println("public key:  " + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            }
            case "manifest" -> {
                Path dir = Path.of(args[1]);
                String version = args[2];
                String tag = args[3];
                StringBuilder s = new StringBuilder();
                s.append("format=1\n");
                s.append("version=").append(version).append('\n');
                s.append("tag=").append(tag).append('\n');
                s.append("built=").append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append('\n');
                for (String[] f : new String[][] {{"payload", "jumpstart-payload.zip"}, {"setup", "JumpstartSetup.jar"}}) {
                    Path file = dir.resolve(f[1]);
                    s.append(f[0]).append('=').append(f[1]).append('\n');
                    s.append(f[0]).append(".sha256=").append(sha256(file)).append('\n');
                }
                Files.writeString(dir.resolve("jumpstart-release.txt"), s.toString(), StandardCharsets.UTF_8);
                System.out.print(s);
            }
            case "sign" -> {
                String key = System.getenv("JUMPSTART_SIGNING_KEY");
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("JUMPSTART_SIGNING_KEY is not set");
                }
                PrivateKey priv = KeyFactory.getInstance("Ed25519")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.strip())));
                Path file = Path.of(args[1]);
                Signature sig = Signature.getInstance("Ed25519");
                sig.initSign(priv);
                sig.update(Files.readAllBytes(file));
                Files.writeString(Path.of(file + ".sig"), Base64.getEncoder().encodeToString(sig.sign()) + "\n");
                System.out.println("signed " + file);
            }
            case "verify" -> {
                PublicKey pub = KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(args[2].strip())));
                Path file = Path.of(args[1]);
                Signature sig = Signature.getInstance("Ed25519");
                sig.initVerify(pub);
                sig.update(Files.readAllBytes(file));
                boolean ok = sig.verify(Base64.getDecoder().decode(Files.readString(Path.of(file + ".sig")).strip()));
                System.out.println(ok ? "signature ok" : "SIGNATURE INVALID");
                System.exit(ok ? 0 : 1);
            }
            default -> {
                System.err.println("usage: keygen <file> | manifest <folder> <version> <tag> | sign <file> | verify <file> <public key>");
                System.exit(2);
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
