/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : LocationProvider.kt
 *  Package : com.rads.detector.location
 *
 *  Thin wrapper around the Google Play Services Fused Location Provider that
 *  supplies the current GPS position to the rest of the app. The detection
 *  pipeline queries the most recent fix when it logs an anomaly so each report
 *  is geo-tagged. Sampling cadence and accuracy are driven by Config to keep
 *  battery impact low while still locating hazards on the road network.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rads.detector.config.Config
import com.rads.detector.util.Logger
import java.util.concurrent.atomic.AtomicReference

/**
 * GPS sampler. Updates at 1 Hz by default, sufficient resolution for road
 * anomaly reporting while saving battery.
 *
 * Wraps the Fused Location Provider and exposes a simple lifecycle
 * ([start] / [stop]) plus a non-blocking [current] accessor. Latitude/longitude
 * are pushed in via a callback and cached so callers never have to await a fix
 * synchronously on the detection hot path.
 *
 * @param context Android context used to obtain the fused client, check the
 *   runtime location permission, and supply the main [android.os.Looper] that
 *   callbacks are delivered on.
 */
class LocationProvider(private val context: Context) {

    // Log tag identifying messages from this component in logcat.
    private val tag = "LocationProvider"

    // Google Play Services fused client — fuses GPS, Wi-Fi and cell signals
    // into a single battery-efficient location stream.
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    // Most recent fix, held in an AtomicReference so it can be written from the
    // callback thread and read from the detection thread without locking.
    private val current = AtomicReference<Location?>(null)

    // Receives location updates from the fused client; we keep only the latest
    // fix and overwrite the cached value each time one arrives.
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // lastLocation may be null if the batch is empty; only cache real fixes.
            result.lastLocation?.let {
                current.set(it)
            }
        }
    }

    // Describes the desired update cadence/accuracy. HIGH_ACCURACY favours GPS
    // for precise positioning; interval and min displacement come from Config so
    // sampling behaviour is centrally tunable.
    private val request = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        Config.LOCATION_INTERVAL_MS
    )
        .setMinUpdateDistanceMeters(Config.LOCATION_MIN_DISPLACEMENT_M)
        .build()

    /**
     * Begin receiving location updates.
     *
     * No-ops with a warning if the fine-location permission has not been
     * granted, so callers can invoke this unconditionally. The
     * `@SuppressLint("MissingPermission")` is safe because [hasPermission]
     * gates the actual request.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        // Guard: without permission the request would throw a SecurityException.
        if (!hasPermission()) {
            Logger.w(tag, "Location permission missing; cannot start")
            return
        }
        // Deliver callbacks on the main looper so consumers see a consistent thread.
        fused.requestLocationUpdates(request, callback, context.mainLooper)
        Logger.i(tag, "Location updates started")
    }

    /**
     * Stop receiving location updates and release the callback so the client
     * can stop sampling GPS (important for battery when the app is paused).
     */
    fun stop() {
        fused.removeLocationUpdates(callback)
    }

    /**
     * @return the latest cached [Location], or null if no fix has arrived yet.
     */
    /** Latest known location, or null if none yet. */
    fun current(): Location? = current.get()

    /**
     * @return true if the app currently holds the ACCESS_FINE_LOCATION runtime
     *   permission, which is required before location updates may be requested.
     */
    fun hasPermission(): Boolean {
        // ContextCompat handles the API-level differences in permission checks.
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
