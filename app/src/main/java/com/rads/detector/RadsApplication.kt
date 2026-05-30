/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : RadsApplication.kt
 *  Package : com.rads.detector
 *
 *  The custom Application subclass that owns process-wide, long-lived singletons
 *  for the detection pipeline. It constructs the shared Room database used to
 *  queue detection reports and initializes logging once at startup, then exposes
 *  these to the rest of the app (Activities reach the database via the running
 *  Application instance).
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector

import android.app.Application
import com.rads.detector.storage.AppDatabase
import com.rads.detector.util.Logger

/**
 * Application-scope initialization: database, logging, anything that needs
 * to live longer than any single Activity.
 */
class RadsApplication : Application() {

    /**
     * Lazily-initialized database instance for the whole app.
     *
     * Declared `by lazy` so the (relatively expensive) Room database is only
     * built on first access rather than eagerly in [onCreate], and so the same
     * instance is shared everywhere it is referenced.
     */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    /**
     * Called once when the process starts, before any Activity. Publishes the
     * singleton [instance] and initializes the [Logger] with the build's debug
     * flag so verbose logs are enabled only in debug builds.
     */
    override fun onCreate() {
        super.onCreate()
        // Expose this Application instance globally for components that need it.
        instance = this
        // Enable verbose logging only for debug builds; quiet in release.
        Logger.init(BuildConfig.DEBUG)
        Logger.i(TAG, "RADS application starting")
    }

    companion object {
        // Log tag for application-level messages.
        private const val TAG = "RadsApplication"

        /**
         * Process-wide handle to the single [RadsApplication]. The private setter
         * means only this class (in [onCreate]) may assign it, keeping the
         * reference read-only to the rest of the codebase.
         */
        lateinit var instance: RadsApplication
            private set
    }
}
