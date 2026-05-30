/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : AlertManager.kt
 *  Package : com.rads.detector.alerts
 *
 *  Central coordinator for user-facing alerting. It sits at the tail end of the
 *  detection pipeline: once detections have been classified and assigned a
 *  severity, this manager decides whether to fire audio and/or haptic feedback.
 *  Its key responsibility is debouncing — drivers see the same pothole across
 *  dozens of consecutive camera frames, so naive alerting would produce an
 *  unbearable stream of beeps; this class throttles alerts per-severity instead.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.alerts

// Android Context is needed to construct the audio/haptic sub-alerters
// (they require access to system services such as SoundPool and Vibrator).
import android.content.Context
// Detection is the per-anomaly result produced upstream by the detector,
// carrying (among other things) the SeverityLevel we key alerting decisions on.
import com.rads.detector.detection.Detection
// The severity enum drives both the alert pattern and the debounce interval.
import com.rads.detector.severity.SeverityLevel

/**
 * Coordinates audio + haptic alerts. Debounces so we don't spam the user when
 * the same anomaly is detected across many consecutive frames.
 *
 * The manager owns the lifecycle of the underlying [AudioAlerter] (which holds
 * native SoundPool resources) and the [HapticAlerter]. Callers feed it the
 * stable detections for each frame via [onDetections] and must call [release]
 * when the screen/session ends to free native audio handles.
 *
 * @param context Android context used to initialise the audio and haptic
 *                sub-systems. Only used during construction; not retained.
 */
class AlertManager(context: Context) {

    // The two output channels. We construct them eagerly so their resources
    // (SoundPool clips, Vibrator handle) are warm and ready for low-latency
    // playback the instant the first hazard is seen.
    private val audio = AudioAlerter(context)
    private val haptic = HapticAlerter(context)

    /**
     * Min interval between alerts of the same severity.
     *
     * These per-severity cooldowns are the heart of the debounce policy: more
     * dangerous anomalies are allowed to re-alert sooner (shorter interval) so
     * the driver is reminded more frequently, while low-stakes ones are spaced
     * out. LOW maps to 0L because LOW never alerts in practice (it sits below
     * the default [minSeverity] gate), so its interval is irrelevant.
     */
    private val minIntervalMsPerSeverity: Map<SeverityLevel, Long> = mapOf(
        SeverityLevel.LOW to 0L,          // Effectively unused — LOW is below the alert threshold.
        SeverityLevel.MEDIUM to 4_000L,   // 4 s: minor hazard, infrequent reminders are enough.
        SeverityLevel.HIGH to 2_500L,     // 2.5 s: notable hazard, re-alert more often.
        SeverityLevel.CRITICAL to 1_500L, // 1.5 s: dangerous, keep the driver continuously aware.
    )

    // Per-severity timestamp (epoch millis) of the last alert that actually fired.
    // Keyed by severity so each severity has its own independent cooldown clock;
    // a CRITICAL alert does not reset the MEDIUM cooldown and vice versa.
    private val lastAlertAt: MutableMap<SeverityLevel, Long> = mutableMapOf()

    // Runtime toggles (e.g. wired to settings UI) letting the user mute either
    // channel independently without tearing down the manager.
    var audioEnabled: Boolean = true
    var hapticEnabled: Boolean = true
    // Floor on which severities are allowed to alert at all. Defaults to MEDIUM
    // so that LOW-confidence/low-impact anomalies stay silent and avoid alert fatigue.
    var minSeverity: SeverityLevel = SeverityLevel.MEDIUM

    /**
     * Notify alert manager of newly stable detections (post-persistence filter).
     *
     * Called once per processed frame with the detections that have already
     * survived the temporal-persistence stage. The method reduces the frame to
     * its single worst hazard, applies the severity floor and the debounce
     * cooldown, and only then fires the enabled output channels.
     *
     * @param detections The stable detections for the current frame. May be
     *                    empty, in which case nothing happens.
     */
    fun onDetections(detections: List<Detection>) {
        // Pick highest severity in frame: one frame can contain several anomalies,
        // but we only ever raise a single alert representing the most urgent one.
        // ordinal increases with severity (LOW=0 .. CRITICAL=3), so maxByOrNull
        // on ordinal yields the worst hazard. Bail out if the frame had nothing.
        val top = detections.maxByOrNull { it.severity.ordinal } ?: return
        // Gate against the configured minimum: ignore anything below minSeverity
        // so the driver is not nagged about trivial road features.
        if (top.severity.ordinal < minSeverity.ordinal) return

        // Debounce check: compare wall-clock now against the last time we alerted
        // for THIS severity, and suppress if we are still inside its cooldown window.
        val now = System.currentTimeMillis()
        val last = lastAlertAt[top.severity] ?: 0L              // 0L => never alerted before.
        val minInterval = minIntervalMsPerSeverity[top.severity] ?: 0L
        if (now - last < minInterval) return                    // Too soon — swallow this alert.

        // We have decided to alert: record the time first so concurrent/rapid
        // follow-up frames see an up-to-date cooldown, then fire each enabled channel.
        lastAlertAt[top.severity] = now
        if (audioEnabled) audio.play(top.severity)   // Sonification appropriate to severity.
        if (hapticEnabled) haptic.pulse(top.severity) // Vibration pattern appropriate to severity.
    }

    /**
     * Release native resources held by the audio sub-system.
     *
     * Must be invoked when the detection session/screen is being destroyed.
     * The haptic alerter holds no releasable handles, so only [AudioAlerter]
     * (which owns a native SoundPool) needs explicit teardown.
     */
    fun release() {
        audio.release()
    }
}
