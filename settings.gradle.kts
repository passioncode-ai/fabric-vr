// The JDK Gradle itself runs on is not the same thing as the bytecode target each module sets
// (`JavaVersion.VERSION_17`). Nothing in this build provisioned one, and the whole mechanism was a
// sentence in the README telling the reader to export JAVA_HOME. A fresh machine whose default
// `java` is too new fails with "Unsupported class file major version", which names neither the JDK
// nor the fix. This says both, before a single module is configured.
//
// **The bound is 17–21 and both ends are measured, not guessed.** The floor is what every module
// compiles to. The ceiling is the JDK of record: Gradle 8.13 running on JetBrains Runtime 21.0.10
// is what this project is developed and released on, and it is what `.github/workflows/ci.yml`
// pins. Gradle 8.13 itself tolerates newer JDKs, but AGP 8.11.1 with the pinned Kotlin/KSP trio
// (`DEC-0004`) has not been run on one here — and a bound that is wrong in the permissive
// direction turns a clear failure into a confusing one three modules later.
//
// Deliberately NOT a Java toolchain: provisioning a JDK changes what compiles six modules under a
// pinned toolchain, and that belongs behind CI rather than in front of a first clone.
val runningJdk = JavaVersion.current()
require(runningJdk >= JavaVersion.VERSION_17 && runningJdk <= JavaVersion.VERSION_21) {
    """

    Fabric VR needs JDK 17-21; Gradle is running on $runningJdk.
    Android Studio ships a suitable one:

      export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

    """.trimIndent()
}

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fabric-vr"
include(":app", ":core-common", ":core-notes", ":feature-vault", ":feature-stt", ":feature-assistant")
