import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * What git says about this tree, at configuration time.
 *
 * `providers.exec` rather than `"git …".execute()`: the latter breaks the configuration cache,
 * which this project should not be giving up for four strings. Every value has a fallback and the
 * fallback is **a word, not a blank** — a source tarball, a shallow clone or a machine with no
 * `git` must still build, and a version line that silently omits its provenance reads as an
 * answer without being one, which is the defect this whole block replaces.
 */
fun gitOutput(vararg args: String): String = runCatching {
    providers.exec { commandLine(listOf("git") + args) }
        .standardOutput.asText.get().trim()
}.getOrDefault("")

val gitSha: String = gitOutput("rev-parse", "--short", "HEAD").ifEmpty { "unknown" }
val gitBranch: String = gitOutput("rev-parse", "--abbrev-ref", "HEAD").ifEmpty { "unknown" }
/**
 * **The commit's timestamp, not `now()`.** A wall clock makes every build non-reproducible and
 * invalidates the configuration cache on every invocation — and it answers the wrong question:
 * what a reader wants is how old the *code* is, which is what "this build is a week old" means.
 */
val gitTime: String = gitOutput("show", "-s", "--format=%cI", "HEAD").ifEmpty { "unknown" }

/**
 * **`B-160`: the version code is the commit's own clock, because a commit COUNT is a property of
 * the branch rather than of the code.**
 *
 * `DEC-0048` took `git rev-list --count HEAD` and was right to take something git-derived — the
 * `-PversionCode` it replaced had one caller and every APK this project had produced read `(1)`.
 * What it got wrong is which git number. Measured here on 2026-09-22: `main` 57,
 * `feat/v1-notes-core` 176. Build from `main` — the repository's default branch, which a fresh
 * clone lands on — over a branch build and Android answers `INSTALL_FAILED_VERSION_DOWNGRADE`;
 * the only way through is the uninstall that takes `filesDir` with it, which is the vault, the
 * database, every Keystore value and a 574 MB model. Two feature branches off one base are worse
 * again: the longer one outranks the newer one, in either direction, forever.
 *
 * **The property needed is monotonicity in the code's own age, on every branch somebody might
 * install from.** `%ct` — the commit's committer time — is a total order over every commit in
 * every branch, it is fixed by the commit rather than by the build, and it needs no second ref
 * and no tracked file. A rebase, an amend and a cherry-pick all reset it to the moment the new
 * commit was made, so the number rises exactly when the code is rewritten.
 *
 * **Two candidates were refused.** A *committed counter bumped by a check* gives two branches off
 * one base the SAME number, so two different APKs become indistinguishable and `install -r`
 * succeeds silently — `H-31` again, with a merge conflict in one line on every rebase; and it
 * puts the version back in a tracked file, which is what `DEC-0048` moved away from. *`rev-list
 * --count main` plus a branch offset* needs a local `main` that a single-branch CI checkout does
 * not have, and composing two sequences into one integer is precisely what `DEC-0048`'s
 * Decision 2 refused — two monotonic sequences over one field cannot be ordered against each
 * other.
 *
 * **What it trades.** The number stops being small: `176` becomes about 22 800 000, so nobody
 * reads it aloud any more — `versionName`'s short sha and `GIT_BRANCH` are what a person quotes,
 * and they were already the readable half. The space is finite: 2 100 000 000 seconds from the
 * epoch below runs out in 2092, which `VersionCodeTest` asserts rather than assumes. And a
 * *deliberately backdated* committer date still walks the number backwards — a rewritten history
 * can lie about anything, and that is a smaller hazard than switching branches.
 *
 * **The shallow-clone refusal is gone with the count that needed it.** `rev-list --count` returns
 * `1` in a `--depth 1` clone — a plausible wrong answer indistinguishable from a first commit,
 * which shipped release APKs stamped `versionCode = 1` for eleven commits. `show -s --format=%ct
 * HEAD` reads the commit object the clone is *for*, so it is correct at any depth and the build
 * no longer has to refuse one. CI keeps `fetch-depth: 0` because `check-docs.sh` resolves SHAs
 * against history; the build stopped depending on it.
 */
val versionEpochSeconds = 1_767_225_600L   // 2026-01-01T00:00:00Z — this project's first year

/**
 * A tarball or a machine with no `git` answers nothing, and the fallback is `1` rather than a
 * negative number: every other provenance field reads the word `unknown` on that path, and a
 * version code is the one field with no word to fall back to. `coerceIn` also holds the platform
 * bound, so a clock that has run away cannot produce an APK Android refuses to parse.
 */
val versionFromCommit: Int = (gitOutput("show", "-s", "--format=%ct", "HEAD").toLongOrNull() ?: 0L)
    .minus(versionEpochSeconds)
    .coerceIn(1L, 2_100_000_000L)
    .toInt()

/**
 * **The licence notice is copied into the APK, never re-typed into it** (`REQ-063`, audit `H13`).
 *
 * whisper.cpp is MIT and its notice has to travel with the binary; a submodule path is not a
 * notice a person holding the APK can reach. The cheap way to satisfy that is to paste the text
 * into `strings.xml`, and it is the way this project keeps finding broken: the root `NOTICE`
 * moves whenever a dependency moves, a pasted copy does not, and both keep rendering. One
 * producer, one file. `LicencesTest` reads the asset and `../NOTICE` and compares the bytes, so
 * a drift is a red test rather than a licence breach nobody notices.
 *
 * **`assets.srcDir(provider)` alone does not run it.** Measured 2026-09-21: the generated
 * directory was never created and `LicencesTest` failed with `FileNotFoundException`, because a
 * `Copy` task's `destinationDir` is a plain `File` and the source set carried no dependency on
 * the task that fills it. The `preBuild` anchor is what every variant task — `mergeDebugAssets`
 * included, and the unit tests' merged assets with it — is ordered after, so that is where the
 * dependency is declared.
 */
val licenceAssetDir: Provider<Directory> = layout.buildDirectory.dir("generated/licences/assets")

val copyLicenceNotice by tasks.registering(Copy::class) {
    description = "Copies the repository's NOTICE into the APK's assets. One notice, not two."
    from(rootProject.file("NOTICE"))
    into(licenceAssetDir)
}

tasks.named("preBuild") { dependsOn(copyLicenceNotice) }

android {
    namespace = "ai.passioncode.fabricvr"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.passioncode.fabricvr"
        minSdk = 34
        targetSdk = 34
        // **Stamped from git, because the previous mechanism was never used.** The build number
        // came from `-PversionCode=42`, "so a release can be rebuilt from the same commit
        // without editing a tracked file" — and nobody ever passed it, so every APK this project
        // has produced is `versionCode = 1` and `versionName = "0.1.0"`. Two headsets run this
        // app and whether the second one was a week old had no answer that did not involve
        // comparing APK bytes (`H-31`).
        //
        // The property still wins where it is passed, as an escape hatch — but **CI stopped
        // passing it** in this change, because two sequences in one `versionCode` is worse than
        // none: a run number and a commit count are both monotonic and they are not the same
        // number, so a local build could refuse to install over a release or the reverse.
        //
        // **Which git number, and why it is no longer the count: see [versionFromCommit]**
        // (`B-160`). A count orders branches by their length; a commit's clock orders them by
        // when their code was written, which is what an install-over-install needs.
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: versionFromCommit
        versionName = "0.1.0+$gitSha"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
        buildConfigField("String", "BUILD_TIME", "\"$gitTime\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Signing comes from an untracked keystore.properties. Absent, the release build still runs and
    // produces an unsigned APK — an absent keystore must be a clear message, not a broken build.
    val keystoreProperties = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                // **`File(...)` rather than `rootProject.file(...)`.** The latter resolves a
                // relative path *inside the repository*, and the sample's own "paths are
                // relative to the repository root" made that the path of least resistance — so
                // the shortest route from the sample to a working build put the project's one
                // unrecoverable secret in a directory `.gitignore` did not cover (`DEC-0049`).
                // An absolute path is what the rewritten sample now asks for, and this accepts
                // one; a relative path still resolves against the root for anyone who has one.
                val declared = keystoreProperties.getProperty("storeFile")
                storeFile = File(declared).takeIf { it.isAbsolute } ?: rootProject.file(declared)
                // **The file first, the environment second.** A plain `keystore.properties` keeps
                // working for whoever prefers it; the path that ships is the release workflow
                // (`.github/workflows/release.yml`, `DEC-0104`), which writes the path and alias
                // here and puts the passwords in these two variables from the `release`
                // environment, so a value is never typed, never pasted and never in argv. Two
                // irreplaceable values in a plaintext file in the working directory is the file
                // most likely to be opened, screenshotted or pasted while debugging a signing error.
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: System.getenv("FABRICVR_KEYSTORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: System.getenv("FABRICVR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            if (signingConfig == null) {
                logger.lifecycle(
                    "fabric-vr: no keystore.properties — :app:assembleRelease will produce an " +
                        "UNSIGNED apk. Releases are signed only by CI's release workflow; " +
                        "see docs/deployment/signing.md.",
                )
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    /**
     * `T-032`/`DEC-0045`. Twenty-six declared strings were referenced by nothing, and the first
     * sentence the product said was wrong twice — `today_empty` promised a hold gesture
     * `DEC-0010` had deleted and a note control that did not exist. Lint had been **reporting**
     * the unreferenced ones the whole time, as warnings nobody read.
     *
     * A detector, not a script: `UnusedResources` already resolves `@string/` from the manifest
     * and `R.plurals.` from Kotlin, which is where a naive `grep R.string.NAME` gets three of
     * twenty-seven answers wrong. The cross-module duplicate it does **not** find is
     * `scripts/check-strings.sh`'s.
     *
     * **There is no suppression list, and that is deliberate** — a suppression list is how the
     * twenty-six accumulated. A string added before its caller fails the build; add it in the
     * same commit as the code that renders it.
     */
    lint {
        error += "UnusedResources"
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["main"].assets.srcDir(licenceAssetDir)
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")
    testOptions {
        unitTests.isIncludeAndroidResources = true
        /**
         * **A test worker gets its own heap, and the default is not enough for this suite.**
         * `org.gradle.jvmargs` sizes the daemon, not the forked JVM the tests run in. Since
         * `T-030` this module composes Compose under Robolectric — twenty-nine such cases now —
         * and a real `OutOfMemoryError` was watched twice in one session: once from a test that
         * looped, and once as three unrelated tests failing together in a single `check-all` run
         * that four immediate re-runs could not reproduce. A suite that fails under load and
         * passes idle is a suite whose green means nothing on a busy machine or in CI.
         *
         * **1 GB is measured, not guessed** (`B-162`). The number was `2g`, chosen by symptom,
         * and the row that recorded it said so: *"the fix is a guess sized by symptom, not a
         * measurement … 2 g on a CI runner with 7 GB and four parallel modules may be the wrong
         * trade."* Measured on 2026-09-22 by running the whole `:app` suite under
         * `-Xlog:gc` (reproduce with `-Pfabricvr.gcLog=<path>`):
         *
         * | heap | GC events | peak used | peak live | full GCs |
         * |---|---|---|---|---|
         * | 2 g  | 26 | 232 MB | 150 MB | 0 |
         * | 1 g  | 26 | 231 MB | 146 MB | 0 |
         * | 256m | 51 | 169 MB | 144 MB | 0 |
         * | 128m | 84 | 128 MB | 128 MB | 2 — saturated, still green |
         *
         * So the suite's working set is ~150 MB live and ~230 MB peak, and at 1 g the collector
         * behaves **identically** to 2 g: same event count, same peak, no full collection. The
         * headroom kept is ~4× the measured peak, which covers a Compose case nobody has written
         * yet; what is given back is the half of the reservation that was never touched, and that
         * is the whole trade the row asked about — four modules in parallel reserve 4 GB rather
         * than 8 GB on a runner that has 7 GB.
         *
         * **The floor is not the bound.** 128 MB passes, and shipping the floor is how the next
         * Compose test turns a green suite red for a reason nobody connects to their change.
         */
        unitTests.all {
            it.maxHeapSize = providers.gradleProperty("fabricvr.testHeap").getOrElse("1g")
            // Opt-in, so the measurement above can be repeated rather than believed.
            providers.gradleProperty("fabricvr.gcLog").orNull
                ?.let { path -> it.jvmArgs("-Xlog:gc:file=$path") }
        }
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-notes"))
    implementation(project(":feature-vault"))
    implementation(project(":feature-stt"))
    // `:feature-assistant` is deliberately NOT a dependency (`DEC-0020`). The module stays in the
    // tree and its tests keep running; the app does not link it, so nothing of the assistant
    // reaches the APK and nothing can invoke it. Re-entry is this one line.

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.meta.spatial.sdk)
    implementation(libs.meta.spatial.sdk.toolkit)
    implementation(libs.meta.spatial.sdk.vr)
    implementation(libs.meta.spatial.sdk.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // `T-030`/`DEC-0043`: the Compose test harness in the **JVM** tier, not only the instrumented
    // one. This screen's two hardest claims are claims about geometry — that the record button
    // does not move between states, and that a note is reachable at the panel's declared minimum
    // — and until now the only way to check either was a headset that has been away since
    // `eaf0c51`. Robolectric composes the frame at a forced size and measures it in ~2 s.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// AGP 8.11.1 pulls a kotlin-stdlib newer than the compiler this project runs;
// pin it to the compiler's own version so metadata versions cannot disagree.
configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:" + libs.versions.kotlin.get())
}

// ---------------------------------------------------------------------------------------------
// **Unit tests here that read a file outside their own source set** — `B-204`.
//
// A `Test` task's declared inputs are its own module's classpath. A test that opens a file
// anywhere else can therefore be UP-TO-DATE while that file changes, and a check that did not run
// prints exactly what a check that passed prints. `B-197` is what that costs: a tree-wide scan sat
// UP-TO-DATE while the defect it scans for was planted back into a module it could not see.
//
// Each line below is read by `CrossModuleTestInputsTest`, which refuses a test that escapes its
// module without a line, a `declared` line with nothing declared, and a line naming a test that no
// longer escapes. Every `safe:` verdict was **measured** on 2026-09-22 — planted, run, reverted —
// rather than reasoned about, because "the classpath surely covers it" is the sentence `B-197` was
// hiding behind.
//
// reads-outside-module: MainThreadPolicyTest — declared: `tools/check_main_thread.py` and the three main-source roots it scans, in the inputs below.
// reads-outside-module: ControlFloorTest — safe: it scans this module's own `src/main/kotlin/.../ui`, which compiles into the very classpath it runs against; a planted bare `TextButton(` re-ran the task and failed it.
// reads-outside-module: MainDispatcherPolicyTest — safe: it scans this module's own `src/test/kotlin`, likewise on its own classpath; a planted `Dispatchers.setMain` re-ran the task and failed it.
// reads-outside-module: LicencesTest — safe: `../NOTICE` is copied into the merged assets by `copyLicenceNotice`, so it is already an input by that route; appending a line to NOTICE re-ran the task.
// reads-outside-module: ManifestPlatformTest — safe: it reads the SOURCE `AndroidManifest.xml`, `res/xml/network_security_config.xml` and `res/xml/data_extraction_rules.xml` (`B-104`), all of which feed the merged manifest and resources this task packages; perturbing each re-ran the task.
// reads-outside-module: GeometryDensityPolicyTest — safe: it scans this module's own `src/test/kotlin`, on its own classpath (`B-208`); a planted `compose.density` line in `TodayFrameTest` re-ran the task and failed it.
// reads-outside-module: PanelSizeTest — safe: the source manifest, by the same route as ManifestPlatformTest and measured with it.
// reads-outside-module: DestructiveAffordanceTest — safe: it scans this module's own
//   `src/main/kotlin/.../ui`, already declared by the tree-wide input below and on this task's own
//   classpath besides. Registered rather than left silent because the marker that finds it is the walk
//   to `settings.gradle.kts`, and a reader seeing that walk cannot tell a same-module scan from an
//   escape without being told which it is.
// reads-outside-module: StateAtomicityTest — safe: it scans this module's own
//   `src/main/kotlin` for `x.value = x.value.copy(`, which is the tree-wide input declared below and
//   on this task's own classpath besides. Registered rather than left silent for
//   `DestructiveAffordanceTest`'s reason: the marker that finds it is the walk to
//   `settings.gradle.kts`, and a reader cannot tell a same-module scan from an escape unless told.
// reads-outside-module: ImePaddingTest — safe: it lists this module's own `ui/` directory and reads
//   the screens in it, to require `imePadding()` wherever a text field is drawn (`DEC-0087`). Same
//   module, same declared tree-wide input, same reason for being registered as `ControlFloorTest`.
// reads-outside-module: SpaceBackgroundTest — safe: it reads three files in this module
//   (`ImmersiveActivity.kt`, `PanelActivity.kt`, `ui/FabricApp.kt`) to assert that the immersive host
//   asks for the translucent background and the panel host does not (`DEC-0085`). All three are under
//   this module's own `src/main`, which is the tree-wide input declared below and on this task's
//   classpath besides; it is registered rather than left silent for `DestructiveAffordanceTest`'s
//   reason — the marker that finds it is the walk to `settings.gradle.kts`, and a reader seeing that
//   walk cannot tell a same-module scan from an escape unless told which it is.
// reads-outside-module: VersionCodeTest — declared: `app/build.gradle.kts` (the expression it asserts)
//   and `git show -s --format=%ct HEAD` through a ProcessBuilder. The build file is declared in the
//   input below. **The git call is NOT an input and cannot be one** — the task would have to re-run on
//   every commit, which is a cost with no defect behind it: the test asserts the SHAPE of the
//   expression, and the shape lives in the file. What it would miss is a version code that changed
//   because history moved rather than because the build file did, and that is the thing the
//   expression exists to guarantee.
//
// **`MainThreadPolicyTest` is the one that was broken**, and `:feature-assistant` is the module
// again — `DEC-0020` keeps it out of `:app`'s dependencies, so nothing in it reaches this task's
// classpath. Measured: an R2 violation planted in `feature-assistant/src/main` left
// `:app:testDebugUnitTest` UP-TO-DATE and green while `python3 tools/check_main_thread.py .`
// exited 1 on the same tree. The checker script itself was the second hole: weakening it changed
// nothing this task could see.
//
// The tree-wide fileTree is deliberately wider than `check_main_thread.py`'s three roots. A fourth
// module added later is a module the scanner picks up and a narrow input list does not, and that
// asymmetry is precisely the failure being closed.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("*/src/main/kotlin/**/*.kt")
        },
    ).withPropertyName("treeWideKotlinSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("tools/check_main_thread.py"))
        .withPropertyName("mainThreadChecker").withPathSensitivity(PathSensitivity.RELATIVE)
    // `VersionCodeTest` asserts the shape of the versionCode expression, which lives here.
    inputs.file(rootProject.file("app/build.gradle.kts"))
        .withPropertyName("appBuildFile").withPathSensitivity(PathSensitivity.RELATIVE)
}
