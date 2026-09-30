plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}
android {
    namespace = "ai.passioncode.fabricvr.notes"
    compileSdk = 35
    defaultConfig { minSdk = 34 }
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
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
    // `MigrationTestHelper` reads the exported schema from assets. The androidTest source set has
    // had this line since the schemas existed; the unit-test one did not, which is why a JVM
    // migration test had never been possible here — `H-24`.
    sourceSets["test"].assets.srcDir("$projectDir/schemas")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// **The schemas directory is both a KSP OUTPUT and a test ASSET SOURCE, and nothing ordered the
// two.** In one invocation the asset merge could run before KSP regenerated the schema, so
// `MigrationTest` validated the new database against the PREVIOUS version's JSON — measured
// 2026-09-21: a tokenizer change reported `Migration didn't properly handle: note_fts` on the
// first run and passed on the second, with no file edited in between. A test that is green
// depending on invocation order is worse than no test, because the green is the one you keep.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") })
}

dependencies {
    api(project(":core-common"))
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}

// AGP 8.11.1 pulls a kotlin-stdlib newer than the compiler this project runs;
// pin it to the compiler's own version so metadata versions cannot disagree.
configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:" + libs.versions.kotlin.get())
}
