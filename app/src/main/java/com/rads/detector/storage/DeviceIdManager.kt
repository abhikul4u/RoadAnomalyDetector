/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : DeviceIdManager.kt
 *  Package : com.rads.detector.storage
 *
 *  Privacy-preserving source of the device identifier that gets stamped onto
 *  every [DetectionReport]. It mints a random UUID once, stores it locally, and
 *  only ever exposes a truncated SHA-256 hash of it. This lets the backend
 *  group reports by device for analytics/deduplication while ensuring the raw,
 *  potentially traceable identifier never leaves the phone.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.storage

import android.content.Context
// KTX helper that wraps SharedPreferences.Editor and commits automatically.
import androidx.core.content.edit
// SHA-256 implementation and UUID generator for the id pipeline.
import java.security.MessageDigest
import java.util.UUID

/**
 * Provides a stable, anonymous device identifier for telemetry. We generate
 * a random UUID once and hash it — the raw UUID is never sent off-device.
 *
 * Implemented as an `object` (singleton) since it holds no per-instance state;
 * persistence is delegated to SharedPreferences.
 */
object DeviceIdManager {

    // SharedPreferences file name that holds the raw device UUID.
    private const val PREFS = "device_id_prefs"
    // Key under which the raw UUID string is stored.
    private const val KEY_UUID = "device_uuid"

    /**
     * Returns the stable, anonymised device id (first 16 hex chars of the
     * SHA-256 of a locally generated UUID). Generates and persists the UUID on
     * first call; reuses it thereafter so the id stays constant across runs.
     *
     * @param context context used to access SharedPreferences.
     * @return a 16-character hex hash that anonymously identifies this install.
     */
    fun getHashedDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Read the stored UUID, or null if this is the first ever call.
        var uuid = prefs.getString(KEY_UUID, null)
        if (uuid == null) {
            // First run: create a fresh random UUID and persist it so the hash
            // remains identical on every subsequent launch.
            uuid = UUID.randomUUID().toString()
            prefs.edit { putString(KEY_UUID, uuid) }
        }
        // Expose only a truncated hash — never the raw UUID. 16 hex chars (64
        // bits) is ample to keep collisions negligible for grouping purposes.
        return sha256(uuid).take(16)
    }

    /**
     * Computes the lowercase hex SHA-256 digest of the given string.
     *
     * @param input the string to hash (here, the raw device UUID).
     * @return the full 64-character hexadecimal digest.
     */
    private fun sha256(input: String): String {
        // Hash the UTF-8 bytes of the input with SHA-256.
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        // Render each byte as two zero-padded lowercase hex digits.
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
