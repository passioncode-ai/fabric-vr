plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "ai.passioncode.fabricvr.stt"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    // Quest 3's XR2 Gen 2 is armv8.2 with dot-product and fp16 arithmetic. Without
                    // this, ggml cross-compiles at the armv8-a baseline and the quantised matmul
                    // falls to the scalar path — the model then looks slow when the build is.
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    // 16 KB page alignment (`B-104`). NDK r27 still links at 4 KB unless asked; r28
                    // made this the default. Measured with `llvm-readelf -lW`: every LOAD segment
                    // of `libfabricvr_whisper.so` was 0x1000 while the NDK's own libc++_shared was
                    // already 0x4000, and a 4 KB-aligned library does not load on a 16 KB-page
                    // kernel. `check-native.sh` refuses a tree without this line.
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}
dependencies {
    api(project(":core-notes"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    // `B-198`: one redirect case crosses a real TLS handshake. Test-only — it never reaches the APK.
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

// reads-outside-module: NativeBridgeMemoryTest — declared: compiles and runs src/main/cpp and src/test/cpp, both registered below
//
// **The JVM suite reads C++ now, so C++ is one of its inputs** (`B-204`, at last found in the
// shape `DEC-0079` predicted it in).
//
// `NativeBridgeMemoryTest` compiles `src/main/cpp/fabricvr_whisper.cpp` and the harness beside it
// and runs the result. Gradle's up-to-date check knows nothing about that: the task's declared
// inputs are Kotlin sources, resources and the classpath, so **editing the bridge leaves
// `testDebugUnitTest` UP-TO-DATE and the test does not run.** Measured 2026-09-22 by planting the
// missing null check back into the bridge — the suite printed BUILD SUCCESSFUL without executing
// a single scenario, which is the one failure mode a test that lives outside its own language
// has and the reason this block is not optional.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/main/cpp"), fileTree("src/test/cpp"))
        .withPropertyName("nativeBridgeSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// AGP 8.11.1 pulls a kotlin-stdlib newer than the compiler this project runs;
// pin it to the compiler's own version so metadata versions cannot disagree.
configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:" + libs.versions.kotlin.get())
}

// ---------------------------------------------------------------------------------------------
// **`B-204`. A test whose task does not declare what it reads can be UP-TO-DATE while the thing
// it scans changes, and a check that did not run prints what a check that passed prints.**
//
// `CrossModuleTestInputsTest` is the tree-wide rule that keeps the class from coming back: every
// unit test carrying one of its escape markers must be named in its own module's build file with
// a verdict and a reason, and a name that has stopped being true must go. It scans every module's
// `src/test` and every `build.gradle.kts`, so by its own rule this task declares both — and this
// module hosts it because whichever one does re-runs its whole suite on any test edit anywhere,
// and `:feature-stt` answers in seconds where `:app` takes half a minute.
//
// reads-outside-module: CrossModuleTestInputsTest — declared: every module's `src/test` and every `build.gradle.kts`, in the input below.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("*/src/test/**/*.kt")
            include("*/build.gradle.kts")
        },
    ).withPropertyName("treeWideTestSourcesAndBuildFiles").withPathSensitivity(PathSensitivity.RELATIVE)
}
