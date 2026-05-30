/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : PersistenceTracker.kt
 *  Package : com.rads.detector.reporting
 *
 *  Temporal smoothing layer that sits between the per-frame object detector and
 *  the reporting/alerting stage. Raw single-frame detections are noisy, so this
 *  tracker correlates detections across consecutive frames (using bounding-box
 *  IoU) and only promotes an anomaly to "stable" once it has persisted for
 *  several frames. It also deduplicates, ensuring each physical pothole is
 *  reported exactly once even though it appears in many frames as the vehicle
 *  approaches it.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.reporting

// RectF holds bounding-box coordinates (left/top/right/bottom) for IoU maths.
import android.graphics.RectF
// Centralised tuning constants (IoU threshold, min frames to report, etc.).
import com.rads.detector.config.Config
// The per-frame detection record emitted by the detector stage.
import com.rads.detector.detection.Detection

/**
 * Tracks detections across frames to filter out spurious one-frame
 * false positives. A detection is only "stable" (eligible for reporting and
 * alerts) once it has been seen in at least MIN_FRAMES_TO_REPORT
 * consecutive frames within roughly the same spatial location.
 *
 * Also remembers which tracks have already been reported, so the same
 * pothole doesn't get logged dozens of times as the car drives past it.
 *
 * NOTE: this class keeps mutable per-frame state ([tracks], [frameCounter]) and
 * is therefore NOT thread-safe; it is expected to be driven from a single
 * inference thread, one [update] call per frame.
 */
class PersistenceTracker {

    /**
     * Internal bookkeeping for a single tracked anomaly across frames.
     *
     * @property className anomaly class this track represents; tracks only ever
     *   match detections of the same class.
     * @property bbox most recent bounding box, updated each time the track is
     *   matched so it "follows" the object as it moves in the frame.
     * @property hitCount number of frames this track has been matched in; the
     *   core evidence used to decide stability.
     * @property lastSeenFrame frame index of the last match, used to expire
     *   tracks that have disappeared.
     * @property reported whether a report/alert has already been emitted for
     *   this track, guaranteeing single-shot reporting.
     */
    private data class Track(
        val className: String,
        var bbox: RectF,
        var hitCount: Int,
        var lastSeenFrame: Long,
        var reported: Boolean,
    )

    // Live set of tracks currently being followed across frames.
    private val tracks = mutableListOf<Track>()
    // Monotonic frame index; incremented once per update() and used both as a
    // timestamp for lastSeenFrame and to compute staleness.
    private var frameCounter: Long = 0L

    /**
     * Process detections from the latest inference. Returns the subset of
     * detections that just crossed the "stable" threshold AND have NOT been
     * reported before (caller should generate reports + alerts for these).
     *
     * @param detections all detections produced by the model for the current
     *   frame.
     * @return only the detections that became newly stable on this frame; an
     *   empty list is the common case.
     */
    fun update(detections: List<Detection>): List<Detection> {
        // Advance the logical clock for this frame.
        frameCounter++
        // Accumulates detections that transition to stable during this call.
        val newlyStable = mutableListOf<Detection>()

        // Match each incoming detection to an existing track or create a new one.
        // `matched` marks which incoming detections have been consumed by a
        // track so they are not also spawned as brand-new tracks below.
        val matched = BooleanArray(detections.size)
        for (track in tracks) {
            // Greedy nearest-match: find the unclaimed same-class detection with
            // the highest overlap (IoU) against this track's last bbox.
            var bestIdx = -1
            var bestIou = 0f
            for ((i, det) in detections.withIndex()) {
                if (matched[i]) continue                 // already claimed by another track
                if (det.className != track.className) continue // classes must agree
                val u = iou(track.bbox, det.bbox)
                if (u > bestIou) {
                    bestIou = u
                    bestIdx = i
                }
            }
            // Accept the match only if overlap clears the configured threshold,
            // otherwise the detection is treated as a different object.
            if (bestIdx >= 0 && bestIou >= Config.PERSISTENCE_IOU) {
                matched[bestIdx] = true
                // Refresh the track to follow the object and record this sighting.
                track.bbox = detections[bestIdx].bbox
                track.hitCount++
                track.lastSeenFrame = frameCounter

                // Promote to "stable" exactly once, the first frame it has been
                // seen enough times. `reported` then suppresses duplicates.
                if (!track.reported && track.hitCount >= Config.MIN_FRAMES_TO_REPORT) {
                    track.reported = true
                    newlyStable.add(detections[bestIdx])
                }
            }
        }

        // New tracks for unmatched detections: anything not absorbed above is a
        // first sighting and starts a fresh track with a single hit.
        for ((i, det) in detections.withIndex()) {
            if (matched[i]) continue
            tracks.add(
                Track(
                    className = det.className,
                    bbox = det.bbox,
                    hitCount = 1,
                    lastSeenFrame = frameCounter,
                    reported = false,
                )
            )
        }

        // Drop stale tracks (not seen for 30 frames ≈ 3 seconds at 10 FPS).
        // This frees memory and lets the same physical spot be reported again on
        // a later, separate pass if it reappears after a long gap.
        tracks.removeAll { frameCounter - it.lastSeenFrame > STALE_THRESHOLD_FRAMES }

        return newlyStable
    }

    /**
     * Clears all tracking state. Call when starting a new session/route so
     * stale tracks from a previous run cannot leak into a new one.
     */
    fun reset() {
        tracks.clear()
        frameCounter = 0L
    }

    /**
     * Computes Intersection-over-Union of two boxes — the standard overlap
     * metric used to decide whether two boxes describe the same object.
     *
     * @param a first bounding box.
     * @param b second bounding box.
     * @return overlap ratio in [0,1]; 0 when the boxes do not intersect or have
     *   zero area.
     */
    private fun iou(a: RectF, b: RectF): Float {
        // Intersection rectangle = the inner edges of the two boxes.
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        // Clamp negative dimensions to 0 so non-overlapping boxes give area 0.
        val iw = (interRight - interLeft).coerceAtLeast(0f)
        val ih = (interBottom - interTop).coerceAtLeast(0f)
        val inter = iw * ih
        // Areas of each box.
        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)
        // Union = combined area minus the double-counted intersection.
        val union = aArea + bArea - inter
        // Guard against division by zero for degenerate (zero-area) inputs.
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        // A track unseen for this many frames is considered gone and removed.
        // ~3 s at 10 FPS — long enough to survive brief occlusions/missed frames.
        private const val STALE_THRESHOLD_FRAMES = 30L
    }
}
