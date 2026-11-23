package io.github.jumpstartpz.installer;

/**
 * The public key Jumpstart's releases are signed with (Ed25519, X.509 base64). Its private half is a
 * GitHub secret (JUMPSTART_SIGNING_KEY) used only by the Kits workflow for tags v*; see
 * kit/ReleaseSign.java. A new key means: new installers for everybody, so keep the private key safe.
 */
final class ReleaseKey {
    static final String PUBLIC = "MCowBQYDK2VwAyEA2GMCuuItFPDMTwxsnCXjMzbLShDxZFjXwYj4NWPXpGE=";

    private ReleaseKey() {
    }
}
