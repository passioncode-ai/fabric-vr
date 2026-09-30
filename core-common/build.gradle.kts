plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "ai.passioncode.fabricvr.common"
    compileSdk = 35
    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    buildFeatures { compose = true }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    // `api`, not `implementation`, and the choice is `B-197`'s. `FabricHttp.builder()` RETURNS an
    // `OkHttpClient.Builder`, so a module that calls it must see the type — and the whole point of
    // moving the factory here is that both `:feature-stt` and `:feature-assistant` can. Declaring
    // it `implementation` would compile for exactly as long as each of those modules kept its own
    // OkHttp line, and break silently the day one of them dropped it.
    api(libs.okhttp)
    testImplementation(libs.junit)
    // `T-032`/`F-14`: `AppError` is sealed, and `sealedSubclasses` is the only way to assert that
    // every shape has a test case **by construction** rather than by a count in a document that
    // nobody keeps true. Test-only on purpose — nothing in `main` reflects, and the app's APK
    // never sees this.
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlin.reflect)
}

// AGP 8.11.1 pulls a kotlin-stdlib newer than the compiler this project runs;
// pin it to the compiler's own version so metadata versions cannot disagree.
configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:" + libs.versions.kotlin.get())
}

// ---------------------------------------------------------------------------------------------
// Unit tests in this module that read a file outside their own source set. `CrossModuleTestInputsTest`
// (in `:app`) requires every one of them to be named here with a verdict and a reason, and refuses
// a name that is no longer true — `B-204`.
//
// reads-outside-module: FabricHttpSourceTest — declared: every module's `src/main/kotlin`, in the input below.
// reads-outside-module: RedactionParityTest — declared: `scripts/check-secrets.sh`, in the input below.
//
// `FabricHttpSourceTest` reads EVERY module's sources, and Gradle had no way to know that.
//
// It is the tree-wide half of `B-197`: no module may construct an `OkHttpClient` outside
// `FabricHttp`. But a unit test's declared inputs are its own module's classpath, so adding the
// defect back to `:feature-assistant` left `:core-common:testDebugUnitTest` **UP-TO-DATE** and the
// check simply did not run — measured here on 2026-09-22 while planting exactly that defect, which
// passed a full three-module run and failed the moment the task was forced.
//
// That is the failure `check-all.sh`'s own header is about: a gate that silently loses a check
// prints the same OK as one that ran. Declaring the sources the test actually reads is the fix —
// now a change to any module's `src/main/kotlin` invalidates this task, which is the truth.
//
// `org.gradle.caching=true` is on, so this also keeps a cache hit from standing in for a run.
//
// **`RedactionParityTest` is the second one, found by `B-204`'s sweep and measured the same way.**
// It reads `scripts/check-secrets.sh` to prove that every credential shape the gate refuses is a
// shape [Redaction] scrubs — including the direction a hand-written list cannot see, where a shape
// added to the gate has no example here. Measured 2026-09-22: a new alternation added to
// `PATTERNS` left `:core-common:testDebugUnitTest` UP-TO-DATE, so the drift detector did not
// detect the drift it exists for.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("*/src/main/kotlin/**/*.kt")
        },
    ).withPropertyName("treeWideKotlinSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("scripts/check-secrets.sh"))
        .withPropertyName("secretGate").withPathSensitivity(PathSensitivity.RELATIVE)
}
