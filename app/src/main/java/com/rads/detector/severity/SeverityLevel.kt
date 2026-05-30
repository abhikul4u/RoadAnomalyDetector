/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : SeverityLevel.kt
 *  Package : com.rads.detector.severity
 *
 *  Defines the canonical severity scale shared across the entire application —
 *  the detector/severity calculator produce it, the alert system consumes it to
 *  choose audio/haptic patterns, and reports use it for prioritisation. Because
 *  it is an ordered enum, callers can both compare levels and step between
 *  adjacent levels, which the size-based severity adjustment relies on.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.severity

/**
 * Four-level severity scale used by the alert system and report priority.
 * Order matters: ordinal is used for comparisons and stepping up/down.
 *
 * Declaration order defines the ordinal (LOW=0, MEDIUM=1, HIGH=2, CRITICAL=3),
 * so listing them from least to most severe is load-bearing — comparisons and
 * the [bumpUp]/[bumpDown] helpers all depend on it.
 *
 * @property displayName Human-readable label for UI and report rendering.
 */
enum class SeverityLevel(val displayName: String) {
    LOW("Low"),          // ordinal 0 — least severe.
    MEDIUM("Medium"),    // ordinal 1
    HIGH("High"),        // ordinal 2
    CRITICAL("Critical"); // ordinal 3 — most severe.

    /**
     * Step up by one level (capped at CRITICAL).
     *
     * Used by the severity calculator to escalate a hazard when its bounding
     * box is large. Calling this on CRITICAL safely returns CRITICAL.
     *
     * @return The next-higher severity, or this level if already at the top.
     */
    fun bumpUp(): SeverityLevel {
        // `entries` is the ordered list of enum constants (index == ordinal).
        val values = entries
        // Move one step up but never past the last (most severe) constant.
        val nextIdx = (ordinal + 1).coerceAtMost(values.size - 1)
        return values[nextIdx]
    }

    /**
     * Step down by one level (floored at LOW).
     *
     * Used to de-escalate a hazard whose bounding box is very small/distant.
     * Calling this on LOW safely returns LOW.
     *
     * @return The next-lower severity, or this level if already at the bottom.
     */
    fun bumpDown(): SeverityLevel {
        // `entries` is the ordered list of enum constants (index == ordinal).
        val values = entries
        // Move one step down but never below the first (least severe) constant.
        val nextIdx = (ordinal - 1).coerceAtLeast(0)
        return values[nextIdx]
    }
}
