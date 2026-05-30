/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : FpsMeter.kt
 *  Package : com.rads.detector.util
 *
 *  Lightweight, thread-safe frames-per-second meter used to monitor the live
 *  throughput of the camera/inference loop. It records frame arrival times in a
 *  fixed-size sliding window and derives a smoothed FPS figure, which the UI
 *  and diagnostics can surface to confirm the pipeline is keeping up with the
 *  configured target rate.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.util

/**
 * Rolling FPS calculator using a sliding window of timestamps.
 * Thread-safe via synchronized.
 *
 * A sliding window is used (rather than a lifetime average) so the reported
 * rate reflects current conditions and responds quickly to slow-downs.
 *
 * @param windowSize maximum number of recent frame timestamps to retain; larger
 *   values smooth the reading more but react to changes more slowly.
 */
class FpsMeter(private val windowSize: Int = 30) {

    // Ring-like buffer of recent frame timestamps (nanoseconds). Oldest entries
    // are evicted from the front once the window is full.
    private val timestamps = ArrayDeque<Long>(windowSize)

    /**
     * Record that a frame just occurred. Call once per processed frame.
     *
     * `@Synchronized` because [tick] may be invoked from the camera thread
     * while [fps] is read from the UI thread.
     */
    @Synchronized
    fun tick() {
        // Capture a monotonic timestamp unaffected by wall-clock adjustments.
        val now = System.nanoTime()
        timestamps.addLast(now)
        // Keep the window bounded by dropping the oldest sample when full.
        if (timestamps.size > windowSize) {
            timestamps.removeFirst()
        }
    }

    /**
     * Compute the current frame rate from the timestamps in the window.
     *
     * @return frames per second, or 0 when there are too few samples or no
     *   measurable time has elapsed (avoids divide-by-zero).
     */
    @Synchronized
    fun fps(): Float {
        // Need at least two timestamps to measure an interval.
        if (timestamps.size < 2) return 0f
        val first = timestamps.first()
        val last = timestamps.last()
        // Elapsed wall time spanned by the window, in seconds.
        val seconds = (last - first) / 1_000_000_000f
        // Guard against zero/negative spans (e.g. identical timestamps).
        if (seconds <= 0f) return 0f
        // N timestamps bound (N-1) intervals; rate = intervals / elapsed seconds.
        return (timestamps.size - 1) / seconds
    }

    /**
     * Clear all recorded timestamps, resetting the meter (e.g. when the
     * pipeline restarts) so stale frames don't skew the next reading.
     */
    @Synchronized
    fun reset() {
        timestamps.clear()
    }
}
