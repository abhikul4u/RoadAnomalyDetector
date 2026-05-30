/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : Logger.kt
 *  Package : com.rads.detector.util
 *
 *  Centralized logging facade wrapping Android's android.util.Log. Verbose,
 *  debug, info and warning messages are emitted only when the app is built in
 *  debug mode, keeping release builds quiet and avoiding leaking diagnostic
 *  detail; errors are always logged so genuine failures remain visible. Every
 *  RADS component logs through this object so the verbosity policy lives in one
 *  place.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.util

import android.util.Log

/**
 * Production-safe logging wrapper. Only logs in debug builds.
 * For release: send errors to your crash reporter of choice.
 *
 * Singleton `object` so all modules share the same verbosity flag set once at
 * startup via [init].
 */
object Logger {

    // Master switch controlling whether non-error logs are emitted. Defaults to
    // false (silent) so logging is off until explicitly enabled for debug builds.
    private var debug = false

    /**
     * Configure the logger's verbosity. Call once during app startup.
     *
     * @param isDebug pass BuildConfig.DEBUG so verbose/debug/info/warn logs are
     *   enabled in development builds and suppressed in release builds.
     */
    fun init(isDebug: Boolean) {
        debug = isDebug
    }

    /**
     * Verbose-level log; emitted only in debug builds.
     * @param tag source component identifier. @param msg message to log.
     */
    fun v(tag: String, msg: String) {
        if (debug) Log.v(tag, msg)
    }

    /**
     * Debug-level log; emitted only in debug builds.
     * @param tag source component identifier. @param msg message to log.
     */
    fun d(tag: String, msg: String) {
        if (debug) Log.d(tag, msg)
    }

    /**
     * Info-level log; emitted only in debug builds.
     * @param tag source component identifier. @param msg message to log.
     */
    fun i(tag: String, msg: String) {
        if (debug) Log.i(tag, msg)
    }

    /**
     * Warning-level log; emitted only in debug builds.
     * @param tag source component identifier.
     * @param msg message to log.
     * @param t optional throwable whose stack trace will be included.
     */
    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (debug) Log.w(tag, msg, t)
    }

    /**
     * Error-level log. Unlike the other levels this is always emitted, even in
     * release builds, because errors must remain visible for diagnosis.
     *
     * @param tag source component identifier.
     * @param msg message to log.
     * @param t optional throwable whose stack trace will be included.
     */
    /** Errors always logged, even in release. */
    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
    }
}
