# Dependencies — verification, bumping, and what to do when a build refuses

`gradle/verification-metadata.xml` pins every third-party artefact this build resolves by SHA-256
(`DEC-0050`). This runbook is the three things a person actually needs from it, and the first one
is the one they will meet at the worst moment.

**It is also the project's SBOM.** That file is the only tracked artefact enumerating every
third-party component the build pulls — 685 components, 1 210 checksums as captured. Anybody who
needs a component inventory should read it rather than build a second one.

## 1. A build refused an artefact

The message looks like this, and it is not a generic build error:

```
> Dependency verification failed for configuration 'classpath'
  One artifact failed verification: kotlin-stdlib-2.0.21.jar (org.jetbrains.kotlin:kotlin-stdlib:2.0.21) from repository MavenRepo
  This can indicate that a dependency has been compromised. Please carefully verify the checksums.
  Open this report for more details: file://…/build/reports/dependency-verification/…/dependency-verification-report.html
```

**There are exactly two causes and neither is fixed by turning verification off.**

| Cause | How you know | What to do |
|---|---|---|
| **The metadata is stale** — somebody bumped a version, added a dependency, or a transitive moved under one | the named artefact belongs to something that just changed in `gradle/libs.versions.toml`, or to its dependency tree | regenerate, deliberately, from a green tree — section 2 |
| **The artefact changed under a version that did not** | the named artefact belongs to nothing anybody touched | **stop.** This is the case the file exists for. Compare the checksum in the report against the publisher's, on another machine or another network, before doing anything else |

**Never**:

- delete `gradle/verification-metadata.xml`,
- pass `--dependency-verification off`,
- leave `--dependency-verification lenient` anywhere persistent.

`lenient` is the right tool *while generating* and nothing else. A lenient build prints warnings
that look exactly like a build with no verification at all, which is how a project ends up
believing it has a mechanism it has switched off.

## 2. Bumping a version, or adding a dependency

Same procedure for both.

1. **Start from a green tree.** The metadata records what is there now; generating it from a
   half-resolved or compromised tree records that instead.

   ```bash
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
   bash scripts/check-all.sh          # must end with ALL GATES GREEN
   git status --short                  # must be empty except for your version bump
   ```

2. **Edit `gradle/libs.versions.toml`** — and nothing else in that commit.

3. **Regenerate.** The task set is not optional: it is what decides which artefacts are resolved
   and therefore which ones get a checksum.

   ```bash
   ./gradlew --write-verification-metadata sha256 --refresh-dependencies \
     clean :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
     testDebugUnitTest lintDebug
   ```

   `:app:assembleDebugAndroidTest` is in that list because a verification failure that only
   appears when somebody runs the instrumented suite is the worst possible time to find one.

   **The command the findings quote — `--write-verification-metadata sha256 help` — under-covers
   badly.** `help` resolves almost nothing, so the file it writes misses most of what the real
   build pulls and the first real build fails on hundreds of missing entries.

4. **Read the diff, and read it for one thing.** It is thousands of lines and unreadable as prose.
   The rule is:

   > **A diff that touches artefacts unrelated to the version you bumped is a finding, not noise.**

   `git diff --stat gradle/verification-metadata.xml` for the size, then
   `git diff gradle/verification-metadata.xml | grep '^[-+] *<component'` for *what* moved. Every
   component in that list should be explicable by the thing you changed or by its dependency tree.

5. **Commit both files together** — the version bump and the metadata. Separating them makes the
   metadata diff unreviewable, because its only cause is in the other commit.

6. Re-run the generation **after** restoring the header comment if you clobbered it: the file is
   rewritten wholesale, and its explanatory comment is not regenerated. Check with
   `head -5 gradle/verification-metadata.xml`.

## 3. Android Studio will not sync

Sources and javadoc jars are downloaded by the IDE and never by the command-line build, so they
have no checksums. They are trusted by pattern in the `<trusted-artifacts>` block at the top of the
file:

```xml
<trust file=".*-sources[.]jar" regex="true"/>
<trust file=".*-javadoc[.]jar" regex="true"/>
```

Trusting them is safe in a way that trusting a jar is not: they are never on a classpath and
nothing executes from them. **If a sync still fails, add the specific artefact to that block with
a comment saying why — do not disable verification to make a sync work.**

## What this does not cover, said plainly

- **`third_party/whisper.cpp`.** It is a git submodule compiled by CMake, not a Gradle dependency,
  so no checksum here touches it. Its integrity is the submodule pin's job, and `.gitmodules`
  declares `branch = master`, which invites `git submodule update --remote` — a separate finding
  with its own row.
- **The Gradle wrapper.** Already pinned and validated by `distributionSha256Sum` and
  `validateDistributionUrl=true` in `gradle/wrapper/gradle-wrapper.properties`. Do not re-derive
  or "tidy" those: replacing a verified pin with an unverified one is a downgrade that looks like
  housekeeping.
- **Signatures.** `verify-signatures` is `false` by decision, not by omission — `DEC-0050` says
  why, and adding PGP later is additive.

## Proof that it works

A wrong checksum was planted and the build was watched refusing it on 2026-09-21: one byte changed
in `kotlin-stdlib`'s entry, `./gradlew --refresh-dependencies testDebugUnitTest` failed with
*"Dependency verification failed for configuration 'classpath'"*, and the file was restored from a
copy. A gate is not a gate until a violation has been planted and watched failing it.
