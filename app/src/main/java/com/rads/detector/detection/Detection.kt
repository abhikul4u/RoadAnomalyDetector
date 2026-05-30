/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : Detection.kt
 *  Package : com.rads.detector.detection
 *
 *  Immutable value object that represents a single road-anomaly detection
 *  emitted by the YOLO inference pipeline after Non-Maximum Suppression (NMS).
 *  Every box that survives post-processing is wrapped in one of these and then
 *  flows downstream to severity scoring, on-screen overlay rendering, and
 *  persistence/tracking. It is the common currency passed between the ML core
 *  and the rest of the app.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.detection

// RectF holds the bounding box as four floats (left, top, right, bottom). We use
// the float variant (not Rect) because model coordinates are sub-pixel precise.
import android.graphics.RectF
// SeverityLevel is the enum (LOW/MEDIUM/HIGH/...) assigned after a box is decoded;
// it lets downstream UI colour-code anomalies by how dangerous they are.
import com.rads.detector.severity.SeverityLevel

/**
 * One detection produced by the model after NMS.
 *
 * This is a plain [data class] (so it gets structural equality, copy() and a
 * readable toString() for free) carrying everything a consumer needs to draw
 * and reason about a single detected road anomaly. The geometric fields are
 * expressed in the model's INPUT coordinate space, not the camera frame — the
 * mapping back to the original frame is the responsibility of the preprocessor
 * which still remembers the letterbox transform.
 *
 * @param classId Index into the labels list (0=MH, 1=PH, 2=WLPH). Kept as a raw
 *   int so per-class logic (e.g. grouping during NMS) is cheap.
 * @param className Human-readable class name, resolved from the labels file so
 *   callers don't have to re-map the id.
 * @param confidence Detection confidence [0, 1] — the model's best class score
 *   for this box; used for sorting in NMS and for UI display.
 * @param bbox Bounding box in INPUT IMAGE coordinates (the 640x640 letterboxed
 *   space). Use [bboxInOriginalFrame] to map back to camera frame coords.
 * @param normalizedBboxArea bbox area as a fraction of total input area, [0, 1].
 *   This scale-invariant size proxy feeds the severity heuristic (a bigger
 *   pothole relative to the frame is generally more severe).
 */
data class Detection(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val bbox: RectF,
    val normalizedBboxArea: Float,
) {
    // Severity is computed AFTER construction (it depends on class + area), so it
    // lives outside the primary constructor as a mutable field. Default LOW keeps
    // the object valid even if severity is never explicitly assigned.
    // `internal set` means only code within this module may overwrite it — external
    // consumers can read the severity but cannot tamper with the scored result.
    var severity: SeverityLevel = SeverityLevel.LOW
        internal set
}
