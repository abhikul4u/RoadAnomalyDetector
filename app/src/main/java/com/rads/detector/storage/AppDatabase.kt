/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : AppDatabase.kt
 *  Package : com.rads.detector.storage
 *
 *  The Room database definition for RADS: the single on-device SQLite store
 *  that holds every persisted [DetectionReport]. It declares the schema
 *  (entities + version), exposes the DAO used to read/write reports, and
 *  provides a thread-safe singleton accessor so the whole app shares one
 *  database connection.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.storage

import android.content.Context
// Room building blocks: @Database marks the schema, RoomDatabase is the base
// class, and Room.databaseBuilder constructs the concrete implementation.
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.rads.detector.reporting.DetectionReport

/**
 * Room database that owns the persistence layer for detection reports.
 *
 * The `@Database` annotation wires the schema together:
 *  - `entities` lists every table-backed class (here, just [DetectionReport]).
 *  - `version` is the schema version; bump it (and supply a migration) whenever
 *    the entity shape changes.
 *  - `exportSchema = false` disables writing the schema JSON to disk, which we
 *    skip since schema-history validation isn't part of this project.
 *
 * Room generates the concrete subclass at compile time, so this class stays
 * abstract.
 */
@Database(
    entities = [DetectionReport::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    /** Accessor for the report DAO; Room provides the implementation. */
    abstract fun detectionReportDao(): DetectionReportDao

    companion object {
        // The process-wide singleton instance. @Volatile guarantees that writes
        // by one thread are immediately visible to others, which is required for
        // the double-checked locking below to be correct.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns the shared [AppDatabase], creating it on first use.
         *
         * Uses the double-checked locking idiom so the (relatively expensive)
         * database build happens at most once even under concurrent access,
         * while subsequent calls take the fast lock-free path.
         *
         * @param context any context; the application context is used to avoid
         *   leaking an Activity/Service.
         * @return the singleton database instance.
         */
        fun getInstance(context: Context): AppDatabase {
            // Fast path: already built. Otherwise enter the synchronized block.
            return INSTANCE ?: synchronized(this) {
                // Re-check inside the lock in case another thread built it while
                // we were waiting on the monitor.
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,   // app context, not the caller's
                    AppDatabase::class.java,
                    "rads_database"               // on-disk database file name
                ).build().also { INSTANCE = it }  // cache for future callers
            }
        }
    }
}
