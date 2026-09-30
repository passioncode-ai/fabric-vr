# Signing — custody, backup, and what happens if the key is lost

> **No secret value appears in this file, and none may be added to it.** What is recorded here is
> a *location*, a *custodian* and a *fingerprint*. A fingerprint is public information; a password
> is not, and it lives in the maintainer's local credential store under a name (`DEC-0049`).

## The state, as of 2026-09-21 — read this first

**No release keystore exists yet, on this machine or anywhere else**, and `keystore.properties` is
absent. That is why this document was written *now*: nothing is lost, and the default outcome of
doing nothing is a `.keystore` on one laptop with no backup.

Every gate that protects the key is already in place and has been watched refusing a planted
violation — `.gitignore` and `scripts/check-secrets.sh` §2b — so the file cannot be committed by
accident before the procedure below is followed.

**`T-037` must not start until the *Human steps* section is done.** It is the task that installs a
release build over a debug one, and it is the point of no return: from the first signed install,
losing the key costs every headset its data.

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
| **Location** | *not yet created* — an absolute path **outside** this repository, on the maintainer's machine. Recorded here once it exists. |
| **Alias** | `fabricvr` |
| **Type / algorithm** | PKCS12, RSA 4096, `SHA256withRSA` |
| **Validity** | ≥ 10 000 days (~27 years). A key that expires is a key that ends the app. |
| **Certificate SHA-256** | *not yet created* — filled from `keytool -list -v … \| grep SHA256` |
| **Custodian** | the maintainer, single custodian. One custodian and two copies is the right shape for a two-person project; a second custodian with no second person who can reach the backup is a name in a document. |

**`keystore.properties` holds `storeFile` and `keyAlias` only.** The two passwords come from the
environment — see *Building a release*.

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

Two copies, **at least one not on this laptop** — the minimum that survives the failure this
protects against: the laptop dying, being reset, or being tidied.

Three artefacts, and any one of them alone is useless:

| Artefact | Why |
|---|---|
| the `.keystore` file | the key |
| the store password, the key password, the alias | the key without them is a file |
| `release-lineage.bin` (`T-037`) | without it a rotated build cannot prove its descent from the debug key, and every headset needs an uninstall again |

The passwords go into the maintainer's local credential store, which is backed up daily on its
own schedule, independently of this repository and of any one tool that reads it. The
`.keystore` and the lineage are binary files, not vault values: they
go wherever the maintainer keeps things that must survive the laptop, and this table says **where
without saying what is inside**.

| Copy | Where | Last verified |
|---|---|---|
| 1 | *not yet created* | — |
| 2 (off this laptop) | *not yet created* | — |

*A copy that has never been restored from is a copy nobody has tested. "Last verified" means
somebody opened it with `keytool -list` and saw the right fingerprint, not that it exists.*

## Building a release

The passwords are **named**, never typed into a file:

```bash
<secret-runner> run fabric-vr \
  FABRICVR_KEYSTORE_PASSWORD,FABRICVR_KEY_PASSWORD -- ./gradlew :app:assembleRelease
```

The secret runner resolves each name, places it in the child's environment, and removes it from
everything the child prints — so a Gradle stack trace quoting a signing error cannot carry the
value into a transcript. The argument form above was checked against the runner's `--help` on
2026-09-21: one comma-separated list, then `--`, then the command.

The build reads `keystore.properties` first and the environment second, so a file with the
passwords in it still works. It is simply not the documented path.

**Verify what was produced against this document:**

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | grep SHA-256
```

That is the only place the record and the artefact are compared. If they disagree, the record is
wrong or the build signed with something else; either way stop.

**With no `keystore.properties` the release build still runs** and produces an **unsigned** APK,
with a lifecycle message saying so. That is deliberate: an absent keystore must be a clear message,
not a broken build.

## Human steps — these need a person, and they are all in one place

Everything above that says *not yet created* is waiting on these. They need a human because a
password chosen, typed or generated inside an agent session has already left the vault: a session
transcript outlives the key, and agent memory stores on a development machine have been measured
holding credential values for exactly that reason.

1. **Create the keystore**, at an absolute path outside this repository. Choose the two passwords
   at the prompts; do not reuse anything.

   ```bash
   keytool -genkeypair -v \
     -keystore "$HOME/<somewhere outside the repo>/fabric-vr-release.keystore" \
     -alias fabricvr -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
   ```

2. **Put the two passwords in the vault, by stdin — not as arguments, which reach the shell
   history.**

   ```bash
   <secret-store> put fabric-vr prod FABRICVR_KEYSTORE_PASSWORD
   <secret-store> put fabric-vr prod FABRICVR_KEY_PASSWORD
   ```

3. **Copy `keystore.properties.sample` to `keystore.properties`** and fill in `storeFile` (the
   absolute path from step 1) and `keyAlias`. Leave both password lines out.

4. **Make the two backups**, at least one off this laptop, covering the `.keystore` **and**
   `~/.android/debug.keystore`.

5. **Tell the next session the fingerprint and the two locations** — the fingerprint is public and
   may be said out loud; the passwords may not. The agent fills in the four *not yet created*
   fields above and the backup table, and the record becomes true.

Once step 5 has happened, `T-037` may start.

## Related

- `DEC-0049` — the decision this document implements
- `docs/evidence/plans/2026-09-20-v2/T-037.md` — the signed-install migration that depends on it
- `README.md` → *The wrapper, and a release build*
- `scripts/check-secrets.sh` §2b — refuses tracked signing material, canary and all
