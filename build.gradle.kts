/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : build.gradle.kts (root / top-level)
 *
 *  This is the top-level Gradle build script for the whole project. It does not
 *  build any code itself; instead it declares the Gradle plugins (and their
 *  versions) that the sub-modules are allowed to apply. Declaring them here with
 *  `apply false` registers each plugin on the build classpath exactly once and
 *  pins a single version, so every module stays consistent.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */

// The `plugins` block lists every Gradle plugin used anywhere in the project.
// `apply false` means: make the plugin available to sub-modules but do NOT apply
// it to this root project (the root has no source code to build).
plugins {
    // Android Gradle Plugin (AGP) — drives the entire Android build pipeline
    // (compiling resources, packaging the APK/AAB, running lint, etc.).
    id("com.android.application") version "8.5.2" apply false

    // Kotlin Android plugin — enables compiling Kotlin source for Android and
    // wires Kotlin into the AGP build. Its version is kept in lock-step with KSP.
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false

    // KSP (Kotlin Symbol Processing) — fast annotation processor used here by
    // Room to generate database/DAO code at compile time. The KSP version must
    // match the Kotlin version (1.9.24) to remain ABI-compatible.
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
