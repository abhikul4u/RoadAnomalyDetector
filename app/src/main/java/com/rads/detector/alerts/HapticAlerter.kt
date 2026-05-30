/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : HapticAlerter.kt
 *  Package : com.rads.detector.alerts
 *
 *  Haptic (vibration) output channel for the alert sub-system. Provides a
 *  non-visual, non-audible warning path — useful when the phone is mounted and
 *  ambient noise or audio routing makes the beep easy to miss. Each severity
 *  maps to a distinct vibration "signature" of escalating intensity and length
 *  so the driver can feel how urgent a hazard is without looking at the screen.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.alerts

// Context is used to fetch the system vibrator service.
import android.content.Context
// Build lets us branch on API level for the Vibrator-acquisition API change.
import android.os.Build
// VibrationEffect models modern, amplitude-controlled vibration patterns.
import android.os.VibrationEffect
// Vibrator is the device interface that actually performs the vibration.
import android.os.Vibrator
// VibratorManager is the Android 12+ (API 31) entry point for obtaining a Vibrator.
import android.os.VibratorManager
// Severity selects which vibration pattern to emit.
import com.rads.detector.severity.SeverityLevel

/**
 * Haptic alerts via the Vibrator API. Different patterns per severity.
 *
 * The class resolves the correct Vibrator handle once at construction,
 * accounting for the API-31 change where vibrators are obtained through a
 * [VibratorManager] rather than directly. All vibration calls degrade safely
 * to no-ops on devices without a vibrator.
 *
 * @param context Used at construction time to obtain the platform vibrator
 *                service. Not retained beyond the constructor.
 */
class HapticAlerter(context: Context) {

    // Resolve the Vibrator once. On Android 12 (S / API 31) and above the direct
    // VIBRATOR_SERVICE is deprecated, so we go through VibratorManager and take
    // its default vibrator; below that we fall back to the legacy service. The
    // safe casts (as?) and nullable type mean a device with no vibrator simply
    // yields null and all downstream pulses become no-ops.
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        // Legacy path: suppress the deprecation warning since this branch only
        // runs on pre-API-31 devices where this is the correct call.
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Emit the vibration pattern corresponding to the given [severity].
     *
     * Severity-to-pattern mapping (escalating urgency):
     *  - LOW      : no vibration at all (returns early).
     *  - MEDIUM   : a single short buzz.
     *  - HIGH     : a double pulse at strong amplitude.
     *  - CRITICAL : a triple pulse at maximum amplitude with a long final buzz.
     *
     * Safely does nothing if there is no usable vibrator on the device.
     *
     * @param severity The hazard severity whose haptic signature to play.
     */
    fun pulse(severity: SeverityLevel) {
        // No vibrator resolved at construction => nothing to do.
        val v = vibrator ?: return
        // Even if we have a Vibrator object, the hardware may not support
        // vibration; guard so we never attempt an unsupported effect.
        if (!v.hasVibrator()) return

        // Build the per-severity effect. Waveforms use paired arrays:
        //   timings[]    = durations in ms (index 0 is the initial off-delay),
        //   amplitudes[] = strength 0..255 for each corresponding segment,
        //   repeat index = -1 means play once (no looping).
        val effect = when (severity) {
            // LOW never warrants a physical buzz — bail out before vibrating.
            SeverityLevel.LOW -> return
            // MEDIUM: one 80 ms buzz at the device's default amplitude.
            SeverityLevel.MEDIUM -> VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
            // HIGH: two 80 ms buzzes (180/255 strength) separated by a 60 ms gap.
            SeverityLevel.HIGH -> VibrationEffect.createWaveform(
                longArrayOf(0, 80, 60, 80),    // off, on, off, on
                intArrayOf(0, 180, 0, 180),    // amplitude per segment
                -1                             // do not repeat
            )
            // CRITICAL: three buzzes at full strength (255), ending with a longer
            // 200 ms pulse to convey maximum urgency.
            SeverityLevel.CRITICAL -> VibrationEffect.createWaveform(
                longArrayOf(0, 150, 80, 150, 80, 200), // off, on, off, on, off, on(long)
                intArrayOf(0, 255, 0, 255, 0, 255),    // max amplitude on each "on"
                -1                                     // do not repeat
            )
        }
        // Hand the constructed effect to the hardware to play.
        v.vibrate(effect)
    }
}
