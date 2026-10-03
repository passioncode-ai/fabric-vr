# Signing — custody, backup, and what happens if the key is lost

> **No secret value appears in this file, and none may be added to it.** What is recorded here is
> a *location*, a *custodian* and a *fingerprint*. A fingerprint is public information; a password
> is not, and it lives in a credential store under a name (`DEC-0049`, `DEC-0104`).

## The state, as of 2026-10-03 — read this first

**The release keystore exists, and only CI signs with it** (`DEC-0104`). It was created by
the organization's release-signing tooling (`scripts/new-android-keystore.sh` in
[passioncode-ai/.github](https://github.com/passioncode-ai/.github/blob/main/release-signing/README.md)),
not on a development machine, and it has three homes and no fourth:

1. the Project Observatory vault, slot **`fabric-vr/prod`**, under the names `ANDROID_KEYSTORE_B64`,
   `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` — the original;
2. the vault's **encrypted off-disk backup** — the second copy;
3. this repository's protected GitHub environment **`release`**, as secrets of the same four names —
   the copy CI signs with. The environment's reviewers are the team `release-approvers`, the person
   who pushed the tag cannot approve, administrators cannot bypass it, and only `v*` tags may deploy
   to it.

**Nobody's laptop holds it, and a build signed anywhere else is never published.** Headset testing
stays **debug-signed**: `./gradlew :app:assembleDebug` and `scripts/install-on-quest.sh`, exactly
as before. How a release is cut is [*Building a release*](#building-a-release) below.

**`T-037` has still not run.** Both headsets carry debug-signed builds, and a release-signed APK
is a different app to them. **Do not install a CI-signed APK over a debug install**: Android refuses
it, and the only way past is the uninstall that deletes everything (next section). `T-037` owns the
migration, and it now has a real key to migrate to.

## What happens if the key is lost

An Android package is identified by its `applicationId` **and** its signing certificate. A build
signed by a different key is, to the platform, a different app: `adb install -r` refuses with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only way through is `adb uninstall`, which takes
`filesDir` with it — the notes database, the whole Markdown vault, every recording, the 190 MB
speech model and every Keystore-encrypted setting. `android:allowBackup="false"` means the system
has no copy. `T-023`'s export exists, so the loss is survivable *if somebody exported first*; it is
not reversible.

There is no vendor, no console and no recovery form. This is the one artefact in the project with
no recovery path, which is the whole reason this document exists.

## The keystore

| | |
|---|---|
| **Where it lives** | the vault slot `fabric-vr/prod`, its encrypted off-disk backup, and the `release` environment of `passioncode-ai/fabric-vr` — see *The state* above. Never a file in a checkout or on a development machine |
| **Alias** | `fabricvr` |
| **Type / algorithm** | PKCS12, RSA 4096, one password for the store and the key |
| **Validity** | until **2056-09-25** (30 years). A key that expires is a key that ends the app |
| **Certificate SHA-256** | `06:69:C0:CF:49:07:E4:11:41:AC:5C:4B:F8:2A:25:CE:75:C9:1E:33:BF:B0:D7:FC:A0:55:40:E3:94:C6:D5:41` |
| **Custodian** | the organization's release signing (`passioncode-ai/.github` → `release-signing/README.md`, *Keys and their homes*). Rotating or restoring it follows that document, and the vault records every rotation (`vault.py rotate`) |

**The fingerprint above is enforced, not only recorded.** `scripts/verify-release-apk.sh` refuses
any APK whose signer is not this certificate, and `scripts/check-release-apk.sh` (a gate in
`check-all.sh`) fails if the script and this table ever disagree.

**`keystore.properties` holds `storeFile` and `keyAlias` only**, on the runner as on any machine.
The two passwords come from `FABRICVR_KEYSTORE_PASSWORD` / `FABRICVR_KEY_PASSWORD` in the
environment (`DEC-0049`); CI fills those from the `release` environment's `ANDROID_KEYSTORE_PASSWORD`
and `ANDROID_KEY_PASSWORD`.

## The debug keystore, which matters until T-037 has run everywhere

Both headsets carry debug-signed builds. Until `T-037` has completed on **every** headset, the
debug key is as irreplaceable as the release key will be, because it is the only thing that can
still upgrade what is installed.

| | |
|---|---|
| **Location** | `~/.android/debug.keystore` |
| **Alias / store password** | `androiddebugkey` / the Android SDK's published default |
| **Certificate SHA-256** | `44:F3:EF:E6:C0:8C:71:54:2C:EF:AF:A6:87:C4:8B:9E:75:DD:1F:80:A9:7F:1E:22:71:0B:68:41:15:90:83:FD` |
| **File SHA-256** | `b592817dfb0ff85234402bc2430dd3f6955ef251f2ad9a7b459b08d7ebd5307e` |
| **Valid** | 2026-09-18 → 2056-09-10 |

**Two different numbers, and the specs conflated them.** `T-039`'s *what NOT to touch* section
says the debug keystore is "byte-identical … verified at SHA-256 `44f3efe6…83fd`" — that value is
the **certificate** digest (what `apksigner verify --print-certs` prints), not a hash of the file.
The file's own hash is the second row above. Both are worth having and they check different
things: the certificate digest proves the *key*, the file hash proves the *file* was not swapped
or regenerated. Verify with:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -storepass android \
  -alias androiddebugkey | grep SHA256
shasum -a 256 ~/.android/debug.keystore
```

**Do not regenerate it, do not move it, and do not let a tool "clean" `~/.android`.** Back it up
alongside the release keystore until `T-037` has completed on every headset.

## Backup

Two copies, **at least one off any one machine** — the minimum that survives the failure this
protects against: a machine dying, being reset, or being tidied. The `release` environment's copy
is a third, but it cannot be read back out of GitHub, so it is not a backup.

Three artefacts, and any one of them alone is useless:

| Artefact | Where |
|---|---|
| the keystore (`ANDROID_KEYSTORE_B64`, the PKCS12 file in base64) | vault slot `fabric-vr/prod` + its encrypted off-disk backup |
| the password and the alias (`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, `ANDROID_KEY_ALIAS`) | the same slot, the same backup |
| `release-lineage.bin` (`T-037`) | does not exist yet. Without it a rotated build cannot prove its descent from the debug key, and every headset needs an uninstall again. When `T-037` creates it, it goes into the same slot |

The debug keystore (`~/.android/debug.keystore`, section above) is **not** in the vault: until
`T-037` has run on every headset it is as irreplaceable as the release key, and backing it up is
still the maintainer's.

| Copy | Where | Last verified |
|---|---|---|
| 1 | Observatory vault, slot `fabric-vr/prod` — four names present (`observatory_credentials fabric-vr`, 2026-10-03) | the certificate SHA-256 above is the one recorded with the key in the organization's *Keys and their homes*; the first CI build verifies it again from the signed APK |
| 2 (off-disk) | the vault's encrypted off-disk backup | not re-read by this change |

*A copy that has never been restored from is a copy nobody has tested. "Last verified" means
somebody opened it and saw the right fingerprint, not that it exists.*

## Building a release

**Only CI builds a release** — `.github/workflows/release.yml`, in the `release` environment:

1. Merge the release commit to `main` through the gates, with its `CHANGELOG.md` section
   `## X.Y.Z` (the version is `versionName` in `app/build.gradle.kts`, without the `+<sha>`).
2. Push an annotated tag: `git tag -a vX.Y.Z <commit> -m "Fabric VR X.Y.Z" && git push origin vX.Y.Z`.
3. The `android` job waits for the `release` environment. Someone from `release-approvers` other
   than the tag's author approves it ("Review deployments").
4. The job decodes the keystore into `$RUNNER_TEMP`, writes a `keystore.properties` with the path
   and alias only, runs `./gradlew :app:assembleRelease` with the passwords in the environment, and
   runs `scripts/verify-release-apk.sh` on the APK: APK Signature Scheme v2 or v3, exactly one
   signer, **this certificate's SHA-256**, a release manifest (not debuggable, `targetSdkVersion`
   34, `minSdkVersion` 29–34, arm64 only) and a `versionName` that is the tag's version. Any
   failure stops the release. It uploads `fabric-vr-X.Y.Z.apk` as `release-android`.
5. `publish` (the organization's `release-publish.yml@v1`, a second approval because it holds the
   GPG key) attests the APK with Sigstore, writes `SHA256SUMS` and `SHA256SUMS.asc`, and publishes
   the GitHub release with the CHANGELOG section as its notes. A published release is never
   rewritten: a fix is a new tag.

**A rehearsal** runs the whole path and publishes nothing: push `vX.Y.Z-rc.N` (the tag push trigger
ignores `-rc` tags), then

```bash
gh workflow run release.yml --ref vX.Y.Z-rc.N -f publish=false
```

The signed set is kept as a workflow artifact for 14 days.

**Verify a downloaded release** by hand the same way:

```bash
bash scripts/verify-release-apk.sh fabric-vr-X.Y.Z.apk X.Y.Z
gh attestation verify fabric-vr-X.Y.Z.apk -R passioncode-ai/fabric-vr
```

### What Meta requires, and what is checked

Read from developers.meta.com on 2026-10-03; re-read before any store submission.

| Requirement | Source | This APK |
|---|---|---|
| Signed with **APK Signature Scheme v2**; v1-only apps are not supported on Quest; later versions must use the same certificate | `VRC.Quest.Packaging.2` (required for immersive apps, recommended for panel apps) | v2 or v3 verified by `verify-release-apk.sh`, which refuses a v1-only signature. Android itself requires v2+ for an app targeting API 30 or above |
| `targetSdkVersion` 32–34 (immersive) / 32–36 (2D); **an app created since 2026-03-01 must target 34** | *AndroidManifest.xml Requirements for Meta Quest Release* (updated 2026-09-30) | 34 (`app/build.gradle.kts`), checked on the built APK |
| `minSdkVersion` 29–34, or 32 for in-lifecycle devices only | the same page | 34, checked to lie in 29–34 |
| `compileSdkVersion` ≥ `targetSdkVersion` | the same page | 35 |
| `android:debuggable` false or unset | the same page | checked on the built APK |
| 64-bit only | `VRC.Quest.Packaging.6` | `abiFilters += "arm64-v8a"`, checked on the built APK |

**This is not store readiness.** No Horizon Store listing exists; the store's review covers
performance, input, functional behaviour and assets, and passing an APK check answers none of it.
What the checks above establish is that a published APK is the real key's, sideloadable on a Quest
3 or 3S, and does not fail the store's packaging rows on the points listed.

### A locally signed build is a debug build

`app/build.gradle.kts` still signs from a `keystore.properties` wherever one exists, because that is
how CI's build reads it. A build signed that way anywhere but the `release` environment — a laptop,
a fork, a dispatch on a branch — is a **debug build** in the organization's sense, whatever its
variant: it is never published and never attached to a release. The release key is not handed to a
development machine for it. For a headset, build debug.

**With no `keystore.properties` the release build still runs** and produces an **unsigned** APK,
with a lifecycle message saying so. That is deliberate — it is what ci.yml's nightly `release build`
job does, to prove R8, the NDK and lint — and `verify-release-apk.sh` refuses such an APK.

## Related

- `DEC-0049` — the decision this document implements
- `docs/evidence/plans/2026-09-20-v2/T-037.md` — the signed-install migration that depends on it
- `DEC-0104` — the release key is created by the organization and used only by CI
- `.github/workflows/release.yml`, `scripts/verify-release-apk.sh`, `scripts/check-release-apk.sh`
- `README.md` → *The wrapper, and a release build*
- `scripts/check-secrets.sh` §2b — refuses tracked signing material, canary and all
