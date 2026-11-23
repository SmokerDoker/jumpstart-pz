# Releases and automatic updates

Every push to `main` rebuilds the pre-release `test-kit`. Players with automatic updates never get
`test-kit`: they get only **tagged releases (`v*`) whose manifest is signed** with Jumpstart's release
key.

## How an update reaches a player

1. A tag `v0.2.0` runs the *Kits* workflow. Its publish job writes `jumpstart-release.txt` (version,
   tag, build time, SHA-256 of `jumpstart-payload.zip` and `JumpstartSetup.jar`) and signs it with the
   secret `JUMPSTART_SIGNING_KEY` (`kit/ReleaseSign.java`, Ed25519). Without the secret the release fails
   on purpose.
2. Installed with "update automatically", Steam's launch options start Jumpstart first
   (`"…/Jumpstart" --wrap %command% …`, in front of BiteCheck's wrapper if there is one). Before every
   game start it reads `releases/latest/download/jumpstart-release.txt` and its `.sig`, checks the
   signature against the public key in `installer/.../ReleaseKey.java`, and installs the payload only
   if its SHA-256 matches and the version is newer (never back to an older release). Then it starts the
   game. No network, a bad signature, anything else: the game starts as it is
   (`Zomboid/jumpstart-update.log` says why).
3. Servers: `JumpstartSetup.jar --server … --update` in `ExecStartPre` does the same (see SERVER.md).

What updates: the ZombieBuddy fork (agent jar and mod folder) and the Jumpstart mod. The installed app
itself (msi/dmg/deb/rpm) updates only with a new installer; on servers the jar replaces itself.

## The signing key (once)

The key pair was made with `java kit/ReleaseSign.java keygen <file>`. The private key is **not** in
the repository; it lives in a local file of the maintainer and as GitHub secret:

```sh
gh secret set JUMPSTART_SIGNING_KEY --repo SmokerDoker/jumpstart-pz < <private key file>
```

Keep a backup of the private key (password manager). If it is lost, make a new pair, put the new
public key into `ReleaseKey.java` and the new secret into GitHub: installers built before that cannot
update any more (players install the new installer once). If it leaks, do the same at once.

## Making a release

```sh
git tag v0.2.0 && git push origin v0.2.0
```

The version comes from the tag (also into the payload's `VERSION`). Check the release afterwards:
`jumpstart-release.txt`, `jumpstart-release.txt.sig`, `jumpstart-payload.zip` must be there.
