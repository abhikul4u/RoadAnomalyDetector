/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : ReportBuilder.kt
 *  Package : com.rads.detector.reporting
 *
 *  Factory that converts a confirmed [Detection] into a fully populated,
 *  persistable [DetectionReport] by joining the detector's output with the
 *  device's current GPS fix and its anonymous device id. This is the final
 *  assembly step before a report is written to the database; it enforces the
 *  rule that a detection without a location is not worth keeping.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.reporting

// Android Context, needed only to resolve the hashed device id from prefs.
import android.content.Context
// The detector's per-detection record (class, severity, confidence, bbox).
import com.rads.detector.detection.Detection
// Abstraction over the current GPS fix (lat/lon/accuracy/bearing).
import com.rads.detector.location.LocationProvider
// Supplies the anonymous, hashed device identifier.
import com.rads.detector.storage.DeviceIdManager

/**
 * Builds a [DetectionReport] from a detection plus current GPS state.
 * Returns null if GPS is not yet available (we drop reports without
 * location — they have no civic value).
 *
 * @property context application context used to look up the device id hash.
 * @property locationProvider source of the latest known GPS fix.
 */
class ReportBuilder(
    private val context: Context,
    private val locationProvider: LocationProvider,
) {
    // Resolved lazily and cached: the hash is stable for the lifetime of the
    // install, so we compute it once on first use rather than per report.
    private val deviceIdHash: String by lazy { DeviceIdManager.getHashedDeviceId(context) }

    /**
     * Assemble a report for a single detection using the current location.
     *
     * @param det the confirmed detection to turn into a report.
     * @return a populated [DetectionReport], or null if no GPS fix is available
     *   yet (such reports are intentionally discarded).
     */
    fun build(det: Detection): DetectionReport? {
        // No location => no useful report; bail out early.
        val loc = locationProvider.current() ?: return null
        return DetectionReport(
            // Copy the detector's verdict fields straight through.
            className = det.className,
            severity = det.severity.name,          // enum -> stored as its name string
            confidence = det.confidence,
            bboxAreaFraction = det.normalizedBboxArea,
            // Stamp the geographic context from the current fix.
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = loc.accuracy,
            // Use bearing only when the fix actually carries one; else 0.
            headingDegrees = if (loc.hasBearing()) loc.bearing else 0f,
            // Capture time at the moment of assembly (UTC epoch millis).
            timestampUtc = System.currentTimeMillis(),
            // Attach the anonymous device id and mark as not-yet-uploaded.
            deviceIdHash = deviceIdHash,
            uploaded = false,
        )
    }
}
