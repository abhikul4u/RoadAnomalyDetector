/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : CsvExporter.kt
 *  Package : com.rads.detector.util
 *
 *  Serialises a list of persisted [DetectionReport]s to a CSV file in the app's
 *  external Downloads directory (no runtime permission needed on Android 10+).
 *  This is the backing implementation for the Reports screen's "Export CSV"
 *  action; the resulting file can then be shared via FileProvider. It uses a
 *  plain BufferedWriter (no CSV library) and applies standard RFC-4180 quoting.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.util

import android.content.Context
import android.os.Environment
import com.rads.detector.reporting.DetectionReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Fixed CSV header — column order is part of the public export contract and
// must not be reordered (downstream tooling/thesis analysis depends on it).
private const val CSV_HEADER =
    "id,timestamp_iso,class,severity,confidence,bbox_area_pct," +
        "latitude,longitude,accuracy_m,heading_deg,device_id_hash,uploaded"

/**
 * Builds a CSV file containing one row per report and returns the written file.
 *
 * The file is written to `getExternalFilesDir(DIRECTORY_DOWNLOADS)` so it is
 * visible under `Android/data/<applicationId>/files/Download/` in the Files app
 * and requires no storage permission on API 29+. The call performs blocking IO
 * and therefore hops onto [Dispatchers.IO] internally; callers may invoke it
 * from any coroutine context.
 *
 * @param context used to resolve the app-specific external Downloads directory.
 * @param reports the reports to serialise; assumed to be the full set the user
 *                wishes to export (caller decides ordering — it is preserved).
 * @return the [File] that was written.
 */
suspend fun exportReportsToCsv(
    context: Context,
    reports: List<DetectionReport>,
): File = withContext(Dispatchers.IO) {
    // Resolve (and create, if needed) the app-specific external Downloads dir.
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: context.filesDir // Fallback to internal storage if external is unavailable.
    if (!dir.exists()) dir.mkdirs()

    // File name carries a local-time stamp so successive exports never collide.
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val file = File(dir, "rads_reports_$stamp.csv")

    // ISO 8601 with explicit local UTC offset, e.g. 2026-05-30T14:32:08+05:30.
    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    // BufferedWriter over a FileOutputStream — no CSV library, per spec.
    BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)).use { w ->
        w.write(CSV_HEADER)
        w.write("\n")
        for (r in reports) {
            // Indoor sentinel: both coordinates exactly 0.0 means "no GPS at
            // insert time" — emit empty lat/lon cells rather than "0.0,0.0".
            val indoor = r.latitude == 0.0 && r.longitude == 0.0
            val lat = if (indoor) "" else String.format(Locale.US, "%.6f", r.latitude)
            val lon = if (indoor) "" else String.format(Locale.US, "%.6f", r.longitude)

            val row = listOf(
                r.id.toString(),
                isoFormat.format(Date(r.timestampUtc)),
                r.className,
                r.severity,
                String.format(Locale.US, "%.4f", r.confidence),
                String.format(Locale.US, "%.2f", r.bboxAreaFraction * 100),
                lat,
                lon,
                String.format(Locale.US, "%.1f", r.accuracyMeters),
                String.format(Locale.US, "%.1f", r.headingDegrees),
                r.deviceIdHash,
                r.uploaded.toString(),
            ).joinToString(",") { escapeCsv(it) }

            w.write(row)
            w.write("\n")
        }
    }
    file
}

/**
 * Escapes a single CSV field per RFC 4180: if the value contains a comma,
 * double-quote, or newline, wrap it in double-quotes and double any embedded
 * quotes. Plain values are returned unchanged.
 *
 * @param value the raw field text.
 * @return a CSV-safe representation of [value].
 */
private fun escapeCsv(value: String): String {
    val needsQuoting = value.contains(',') || value.contains('"') ||
        value.contains('\n') || value.contains('\r')
    if (!needsQuoting) return value
    val escaped = value.replace("\"", "\"\"") // Double any literal quotes.
    return "\"$escaped\""
}
