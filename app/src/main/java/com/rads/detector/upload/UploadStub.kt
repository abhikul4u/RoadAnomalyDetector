/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : UploadStub.kt
 *  Package : com.rads.detector.upload
 *
 *  Placeholder seam for the (not-yet-implemented) backend sync feature. It
 *  defines the contract by which locally-stored detection reports will
 *  eventually be pushed to a server, but for now performs no network I/O and
 *  always signals failure so reports stay persisted on-device. Isolating the
 *  upload concern behind this object lets the rest of the pipeline be written
 *  and tested today without waiting on the Phase 2 networking work.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.upload

import com.rads.detector.reporting.DetectionReport
import com.rads.detector.util.Logger

/**
 * Placeholder for backend report upload. Currently a no-op.
 *
 * Phase 2 implementation:
 *   - Replace [upload] body with an actual HTTP POST (Retrofit / OkHttp)
 *   - Wire it into a [androidx.work.CoroutineWorker] for batch uploads
 *   - Mark reports as uploaded via [DetectionReportDao.markUploaded]
 *
 * The rest of the app doesn't need to change when this gets implemented.
 *
 * Declared as an `object` (singleton) because uploading is a stateless service
 * with no per-instance configuration; callers reference it directly.
 */
object UploadStub {

    // Log tag for tracing stub invocations in logcat during development.
    private const val TAG = "UploadStub"

    /**
     * Attempt to upload a single [DetectionReport] to the backend.
     *
     * Marked `suspend` up front so the eventual network implementation can do
     * asynchronous I/O without changing this signature or any call sites.
     *
     * @param report the detection record that would be transmitted.
     * @return true if (when implemented) the upload succeeded.
     * Currently always returns false — caller should retain the report.
     */
    suspend fun upload(report: DetectionReport): Boolean {
        // Log which report was "uploaded" so the integration point is observable.
        Logger.d(TAG, "Upload stub called for report ${report.id} (no-op for now)")
        // Always report failure so the caller keeps the report queued for a real upload later.
        return false
    }
}
