/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : settings.gradle.kts
 *
 *  Gradle settings script — evaluated before any build script. It defines which
 *  modules make up the build, the root project name, and the repositories used
 *  to resolve Gradle plugins and library dependencies. This is where the build's
 *  overall structure and dependency sources are configured.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */

// `pluginManagement` controls where Gradle looks to download the PLUGINS
// declared in the build scripts (e.g. the Android and Kotlin plugins).
pluginManagement {
    repositories {
        google()              // Google's Maven repo — hosts the Android Gradle Plugin.
        mavenCentral()        // Maven Central — hosts Kotlin and most JVM plugins.
        gradlePluginPortal()  // Official Gradle plugin portal — community plugins (e.g. KSP).
    }
}

// `dependencyResolutionManagement` controls where Gradle looks to download the
// LIBRARY DEPENDENCIES (the artifacts requested in each module's build script).
dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS forbids modules from declaring their own repositories.
    // This guarantees every dependency is resolved only from the centralized list
    // below, which keeps builds reproducible and easier to audit.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()        // AndroidX, CameraX, Material, Play Services artifacts.
        mavenCentral()  // Kotlin, coroutines, TensorFlow Lite, etc.
    }
}

// The name shown for the root project (e.g. in IDE and build reports).
rootProject.name = "RoadAnomalyDetector"

// Register the single application module `:app` as part of this build.
include(":app")
