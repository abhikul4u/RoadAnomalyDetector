/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : ReportsAdapter.kt
 *  Package : com.rads.detector.ui
 *
 *  RecyclerView adapter that binds a list of persisted [DetectionReport]s to the
 *  item_report row layout for the Reports screen. It paints the severity pill,
 *  formats the class/severity/confidence/location lines, swaps the upload-status
 *  cloud icon, and reports row taps back to the host via a click callback. This
 *  file also exposes small presentation helpers (severity color / pretty name /
 *  indoor check) reused by ReportDetailActivity so the two screens stay
 *  consistent.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.rads.detector.R
import com.rads.detector.databinding.ItemReportBinding
import com.rads.detector.reporting.DetectionReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for the reports list.
 *
 * @param onClick invoked with the tapped report so the host can open its detail.
 */
class ReportsAdapter(
    private val onClick: (DetectionReport) -> Unit,
) : RecyclerView.Adapter<ReportsAdapter.ReportViewHolder>() {

    // Backing data; replaced wholesale via [submit]. Ordering is decided by the
    // caller (the DAO returns newest-first), and preserved here as-is.
    private var items: List<DetectionReport> = emptyList()

    // Time-of-day formatter for the row's third line (device-local time zone).
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * Replaces the entire dataset and refreshes the list. For thesis-demo scale
     * (≤ ~1000 rows) a full rebind is simpler and fast enough; no DiffUtil.
     *
     * @param reports the new list to display.
     */
    @SuppressLint("NotifyDataSetChanged") // Full rebind is intentional at demo scale.
    fun submit(reports: List<DetectionReport>) {
        items = reports
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val binding = ItemReportBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ReportViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
        holder.bind(items[position])
    }

    /**
     * Holds the view references for one report row and binds a report to them.
     */
    inner class ReportViewHolder(
        private val binding: ItemReportBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * Binds a single [report] to this row's views.
         *
         * @param report the report to render.
         */
        fun bind(report: DetectionReport) {
            val ctx = binding.root.context

            // Left pill: solid color keyed to the report's severity bucket.
            binding.severityPill.setBackgroundColor(
                ContextCompat.getColor(ctx, severityColorRes(report.severity))
            )

            // Top line: "<class> · <Severity>".
            binding.tvTitle.text = ctx.getString(
                R.string.fmt_report_title, report.className, prettySeverity(report.severity)
            )

            // Second line: confidence (2dp) and bbox area as a percentage (1dp).
            binding.tvMeta.text = ctx.getString(
                R.string.fmt_report_meta, report.confidence, report.bboxAreaFraction * 100
            )

            // Third line: coordinates (or "Indoor") plus the time of day.
            val time = timeFormat.format(Date(report.timestampUtc))
            binding.tvLocation.text = if (isIndoorReport(report)) {
                ctx.getString(R.string.fmt_report_location_indoor, ctx.getString(R.string.indoor_marker), time)
            } else {
                ctx.getString(R.string.fmt_report_location, report.latitude, report.longitude, time)
            }

            // Right indicator: green cloud-done if uploaded, gray cloud-off if not.
            if (report.uploaded) {
                binding.ivUploaded.setImageResource(R.drawable.ic_cloud_done_24)
                binding.ivUploaded.setColorFilter(
                    ContextCompat.getColor(ctx, R.color.severity_low)
                )
            } else {
                binding.ivUploaded.setImageResource(R.drawable.ic_cloud_off_24)
                binding.ivUploaded.setColorFilter(Color.GRAY)
            }

            // Forward taps to the host activity.
            binding.root.setOnClickListener { onClick(report) }
        }
    }
}

/**
 * Maps a stored severity name to its color resource. Unknown values fall back
 * to the "low" (green) color so the UI never crashes on unexpected data.
 *
 * @param severity stored severity string (e.g. "CRITICAL").
 * @return the matching `@color` resource id.
 */
@ColorRes
fun severityColorRes(severity: String): Int = when (severity.uppercase(Locale.US)) {
    "CRITICAL" -> R.color.severity_critical
    "HIGH" -> R.color.severity_high
    "MEDIUM" -> R.color.severity_medium
    "LOW" -> R.color.severity_low
    else -> R.color.severity_low
}

/**
 * Converts a stored severity name (e.g. "MEDIUM") to title case ("Medium") for
 * display, leaving unrecognised values lower-cased-then-capitalised.
 *
 * @param severity stored severity string.
 * @return a human-friendly capitalised form.
 */
fun prettySeverity(severity: String): String =
    severity.lowercase(Locale.US).replaceFirstChar { it.uppercase() }

/**
 * The "Indoor" sentinel: a report whose latitude and longitude are both exactly
 * 0.0, meaning no GPS fix was available when it was created.
 *
 * Note: in the current pipeline ReportBuilder discards reports without a fix
 * entirely, so this is defensive — it future-proofs the UI for any path that
 * later persists fix-less reports.
 *
 * @param report the report to test.
 * @return true if the report has no usable coordinates.
 */
fun isIndoorReport(report: DetectionReport): Boolean =
    report.latitude == 0.0 && report.longitude == 0.0
