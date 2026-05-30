/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : PerfTimer.kt
 *  Package : com.rads.detector.util
 *
 *  Simple stopwatch with a rolling average, used to profile how long a single
 *  model inference (or any timed stage) takes. Each timed span is recorded into
 *  a fixed-size window so the average latency in milliseconds can be displayed
 *  for diagnostics and used to verify the pipeline stays within its per-frame
 *  performance budget.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.util

/**
 * Rolling average tracker for inference timing.
 *
 * Usage: call [start] immediately before the work to be measured and [stop]
 * immediately after; query [avgMs] for the smoothed latency over the window.
 *
 * @param windowSize number of most-recent samples to average over.
 */
class PerfTimer(private val windowSize: Int = 50) {

    // Sliding window of recent elapsed durations in nanoseconds; oldest samples
    // are evicted once the window is full.
    private val samples = ArrayDeque<Long>(windowSize)

    // Timestamp (nanoseconds) captured by the most recent start() call.
    private var startNs: Long = 0L

    /**
     * Mark the beginning of a timed span by capturing a monotonic start time.
     */
    fun start() {
        // nanoTime is monotonic, so it is unaffected by system clock changes.
        startNs = System.nanoTime()
    }

    /**
     * End the current timed span, record its duration, and return it.
     *
     * `@Synchronized` guards the shared [samples] deque so timing can be read
     * and written from different threads safely.
     *
     * @return the elapsed time of this span in nanoseconds.
     */
    @Synchronized
    fun stop(): Long {
        // Duration since the matching start() call.
        val elapsedNs = System.nanoTime() - startNs
        samples.addLast(elapsedNs)
        // Keep the window bounded by discarding the oldest sample.
        if (samples.size > windowSize) samples.removeFirst()
        return elapsedNs
    }

    /**
     * @return the average duration over the current window, converted to
     *   milliseconds, or 0 when no samples have been recorded yet.
     */
    @Synchronized
    fun avgMs(): Float {
        // No data yet — avoid averaging an empty collection.
        if (samples.isEmpty()) return 0f
        // Mean elapsed time in nanoseconds across the window.
        val avgNs = samples.average()
        // Convert ns -> ms for a human-friendly, UI-ready figure.
        return (avgNs / 1_000_000.0).toFloat()
    }
}
