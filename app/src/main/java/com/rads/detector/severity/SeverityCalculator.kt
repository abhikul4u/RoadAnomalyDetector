/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : SeverityCalculator.kt
 *  Package : com.rads.detector.severity
 *
 *  Pure decision logic that turns a raw detection (its class label plus the
 *  relative size of its bounding box) into a [SeverityLevel]. This is the
 *  bridge between the vision model's output and the alerting layer: the
 *  detector says WHAT was seen, this calculator says HOW DANGEROUS it is, and
 *  the alert manager then decides how loudly/strongly to warn the driver.
 *  The rules implement the severity scoring scheme from Chapter 4, Section 4.3.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.severity

// Config centralises the class-label constants and the bbox-area thresholds so
// the scoring policy can be tuned in one place rather than hard-coded here.
import com.rads.detector.config.Config

/**
 * Implements the severity scoring sub-system from Chapter 4, Section 4.3.
 *
 * Severity is determined by:
 *   1. Class-based prior (MH=Critical, WLPH=High, PH=Medium)
 *   2. Bounding-box area modifier for PH and WLPH:
 *        > 8% of frame area → bump severity UP one level
 *        < 2% of frame area → bump severity DOWN one level
 *
 * MH severity is always CRITICAL regardless of bbox size (per Chapter 4):
 * "open manhole is an immediate high hazard ... regardless of the size of
 * the manhole."
 *
 * Implemented as a stateless `object` (singleton): it holds only constant
 * lookup data and a pure function, so a single shared instance is ideal and
 * thread-safe.
 */
object SeverityCalculator {

    /**
     * Per-class default severity prior.
     *
     * The starting ("base") severity each detected class is assigned before any
     * size-based adjustment. Rationale per Chapter 4: an open manhole (MH) is the
     * most dangerous, a water-logged pothole (WLPH) is next because its depth is
     * hidden, and a plain pothole (PH) is moderate.
     */
    private val classPriors: Map<String, SeverityLevel> = mapOf(
        Config.CLASS_MH to SeverityLevel.CRITICAL,  // Open manhole — top hazard.
        Config.CLASS_WLPH to SeverityLevel.HIGH,    // Water-logged pothole — hidden depth.
        Config.CLASS_PH to SeverityLevel.MEDIUM,    // Plain pothole — moderate.
    )

    /**
     * Compute the severity for a single detection.
     *
     * Starts from the class prior, short-circuits MH to CRITICAL, and otherwise
     * nudges the level up or down based on how much of the frame the bounding
     * box occupies (a proxy for proximity / physical size).
     *
     * @param className One of MH, PH, WLPH.
     * @param normalizedBboxArea bbox area as a fraction of total frame area, [0, 1].
     * @return The resulting [SeverityLevel]; LOW if the class is unrecognised.
     */
    fun compute(className: String, normalizedBboxArea: Float): SeverityLevel {
        // Look up the starting severity for this class. An unknown label falls
        // back to LOW so an unexpected/garbage class can never trigger a loud alert.
        val base = classPriors[className] ?: SeverityLevel.LOW

        // MH is always CRITICAL regardless of size — explicit per Chapter 4.
        // We return before the size modifier so a small/distant manhole is never
        // down-graded: an open manhole is an immediate hazard at any apparent size.
        if (className == Config.CLASS_MH) return SeverityLevel.CRITICAL

        // For PH and WLPH, apparent size modulates urgency: a large bbox implies
        // the hazard is close/big (escalate), a tiny bbox implies far/small (relax).
        // Boxes between the two thresholds keep the class prior unchanged.
        return when {
            normalizedBboxArea > Config.SEVERITY_LARGE_BBOX_FRAC -> base.bumpUp()   // Large/near → escalate.
            normalizedBboxArea < Config.SEVERITY_SMALL_BBOX_FRAC -> base.bumpDown() // Small/far  → de-escalate.
            else -> base                                                           // Mid-range → keep prior.
        }
    }
}
