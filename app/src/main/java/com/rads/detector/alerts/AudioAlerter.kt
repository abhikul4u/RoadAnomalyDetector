/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : AudioAlerter.kt
 *  Package : com.rads.detector.alerts
 *
 *  Audio output channel for the alert sub-system. Wraps Android's SoundPool to
 *  deliver short, low-latency "earcons" (sonification tones) that escalate with
 *  hazard severity. Clips are pre-decoded at construction so that when the
 *  detection pipeline reports a hazard the sound fires with no decode delay —
 *  critical for giving the driver timely warning.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.alerts

// Context provides access to the app's packaged resources (the raw audio clips).
import android.content.Context
// AudioAttributes lets us describe HOW the sound should be routed/ducked by the
// system audio policy (notification-style sonification rather than media music).
import android.media.AudioAttributes
// SoundPool is purpose-built for many short, latency-sensitive sound effects.
import android.media.SoundPool
// Generated resource references (R.raw.*) point at the bundled alert clips.
import com.rads.detector.R
// Severity selects which pre-loaded clip to play.
import com.rads.detector.severity.SeverityLevel
// Thin logging wrapper used to record a non-fatal failure to load clips.
import com.rads.detector.util.Logger

/**
 * Low-latency audio alerts using SoundPool. Pre-loads small WAV/OGG clips
 * at app start so playback is instant when a detection happens.
 *
 * The alerter degrades gracefully: if the clips fail to load (e.g. missing or
 * corrupt resource) it falls back to a silent map and simply never plays,
 * rather than crashing the detection session.
 *
 * @param context Used at construction time to resolve and decode the raw audio
 *                resources. Not retained beyond the constructor.
 */
class AudioAlerter(context: Context) {

    // Logcat tag for diagnostics emitted from this class.
    private val tag = "AudioAlerter"

    // The SoundPool engine. Configured for at most two simultaneous streams
    // (overlapping alerts are rare and we don't want a cacophony) and tagged as
    // NOTIFICATION/SONIFICATION so the OS treats it like an alert tone — e.g.
    // routed appropriately and allowed to play over navigation audio.
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)             // It's an alert, not media.
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION) // A functional UI tone.
                .build()
        )
        .build()

    // R.raw resource names match the WAV files in res/raw/ (alert_medium.wav etc.)
    // We map each alerting severity to the SoundPool sample ID returned by load().
    // load() is asynchronous (decoding happens off-thread) but kicking it off here
    // at startup means the samples are ready by the time the first hazard appears.
    // LOW is intentionally absent — low-severity anomalies do not produce audio.
    // The whole block is wrapped in try/catch so a resource problem degrades to a
    // silent (empty) map instead of bringing down the app.
    private val soundIds: Map<SeverityLevel, Int> = try {
        mapOf(
            SeverityLevel.MEDIUM to soundPool.load(context, R.raw.alert_medium, 1),
            SeverityLevel.HIGH to soundPool.load(context, R.raw.alert_high, 1),
            SeverityLevel.CRITICAL to soundPool.load(context, R.raw.alert_critical, 1),
        )
    } catch (t: Throwable) {
        // Non-fatal: log and continue with no sounds rather than crashing.
        Logger.w(tag, "Failed to load alert sounds — silent fallback", t)
        emptyMap()
    }

    /**
     * Play the alert tone associated with the given [severity].
     *
     * No-ops silently if there is no clip for the severity (e.g. LOW, or when
     * the silent fallback is active), so callers can invoke it unconditionally.
     *
     * @param severity The hazard severity whose corresponding earcon to play.
     */
    fun play(severity: SeverityLevel) {
        // Look up the pre-loaded sample; missing entry => nothing to play.
        val id = soundIds[severity] ?: return
        // Play once at full volume on both channels, default priority, no loop,
        // normal playback rate. Args: (id, leftVol, rightVol, priority, loop, rate).
        soundPool.play(id, 1f, 1f, 1, 0, 1f)
    }

    /**
     * Release the native SoundPool and all decoded samples.
     *
     * After this the alerter must not be used again. Called from
     * [AlertManager.release] during session teardown to avoid leaking the
     * native audio resources held by SoundPool.
     */
    fun release() {
        soundPool.release()
    }
}
