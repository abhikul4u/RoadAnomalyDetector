/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : ReportDetailActivity.kt
 *  Package : com.rads.detector
 *
 *  Full-record view for a single detection report, opened from the Reports list.
 *  It shows every [DetectionReport] field formatted readably, offers an "Open in
 *  Maps" button (geo: intent to an external maps app), a "Share" toolbar action
 *  (ACTION_SEND plain text for messaging/email), and a destructive "Delete"
 *  button that removes this one report after confirmation. The report is loaded
 *  by id (passed via intent extra) off the main thread.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rads.detector.databinding.ActivityReportDetailBinding
import com.rads.detector.reporting.DetectionReport
import com.rads.detector.ui.isIndoorReport
import com.rads.detector.ui.prettySeverity
import com.rads.detector.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detail screen for one [DetectionReport].
 */
class ReportDetailActivity : AppCompatActivity() {

    // Log tag used by [Logger] to attribute messages to this screen.
    private val tag = "ReportDetailActivity"

    // View-binding handle for activity_report_detail.xml.
    private lateinit var binding: ActivityReportDetailBinding

    // The currently displayed report; null until loaded (or if not found).
    private var report: DetectionReport? = null

    // Long, human-readable timestamp, e.g. "Friday, May 30 2026 at 14:32:08 IST".
    private val fullTimeFormat = SimpleDateFormat("EEEE, MMM d yyyy 'at' HH:mm:ss z", Locale.getDefault())

    /**
     * Reads the report id from the launching intent, installs the toolbar, and
     * loads the record. If the id is missing or the row no longer exists, the
     * screen simply finishes.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Promote our toolbar to the action bar (theme has none) for the Up
        // button and the programmatically-added Share action.
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_arrow_back_24)

        val id = intent.getLongExtra(EXTRA_REPORT_ID, -1L)
        if (id <= 0L) {
            Logger.w(tag, "No valid report id supplied; closing")
            finish()
            return
        }
        loadReport(id)

        // Delete is wired immediately; it no-ops harmlessly until the report
        // loads (guarded inside confirmDelete).
        binding.btnDelete.setOnClickListener { confirmDelete() }
    }

    /**
     * Loads the report by id on the IO dispatcher and renders it, or finishes
     * the screen if the row is not found.
     */
    private fun loadReport(id: Long) {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                (application as RadsApplication).database.detectionReportDao().getById(id)
            }
            if (loaded == null) {
                Logger.w(tag, "Report $id not found; closing")
                finish()
                return@launch
            }
            report = loaded
            render(loaded)
        }
    }

    /** Fills every field view from [r] and wires the maps button. */
    private fun render(r: DetectionReport) {
        binding.tvClass.text = getString(R.string.detail_class, r.className)
        binding.tvSeverity.text = getString(R.string.detail_severity, prettySeverity(r.severity))
        binding.tvConfidence.text = getString(R.string.detail_confidence, r.confidence)
        binding.tvBbox.text = getString(R.string.detail_bbox, r.bboxAreaFraction * 100)
        binding.tvAccuracy.text = getString(R.string.detail_accuracy, r.accuracyMeters)
        binding.tvHeading.text = getString(R.string.detail_heading, r.headingDegrees)
        binding.tvTime.text = getString(R.string.detail_time, fullTimeFormat.format(Date(r.timestampUtc)))
        binding.tvDevice.text = getString(R.string.detail_device, r.deviceIdHash)
        binding.tvUploaded.text = getString(R.string.detail_uploaded, r.uploaded.toString())
        binding.tvId.text = getString(R.string.detail_id, r.id)

        // Location: real coordinates, or the "Indoor" marker when there is no
        // GPS fix — in which case the maps button is hidden (nothing to open).
        if (isIndoorReport(r)) {
            binding.tvLocation.text = getString(R.string.detail_location, getString(R.string.indoor_marker))
            binding.btnOpenMaps.visibility = View.GONE
        } else {
            val coords = getString(R.string.fmt_coords, r.latitude, r.longitude)
            binding.tvLocation.text = getString(R.string.detail_location, coords)
            binding.btnOpenMaps.visibility = View.VISIBLE
            binding.btnOpenMaps.setOnClickListener { openInMaps(r) }
        }
    }

    /**
     * Fires an ACTION_VIEW geo: intent so an external maps app can show/pin the
     * report's coordinates, labelled with its anomaly class.
     */
    private fun openInMaps(r: DetectionReport) {
        val label = Uri.encode("${r.className} (${prettySeverity(r.severity)})")
        val geo = Uri.parse("geo:${r.latitude},${r.longitude}?q=${r.latitude},${r.longitude}($label)")
        val intent = Intent(Intent.ACTION_VIEW, geo)
        // Launch directly and catch the not-found case. (resolveActivity is
        // unreliable under Android 11+ package-visibility without a <queries>
        // entry, so a try/catch is the robust pattern here.)
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Logger.w(tag, "No app available to handle geo: intent", e)
        }
    }

    /**
     * Builds a plain-text summary of the report and opens the system share sheet
     * (ACTION_SEND) so it can be sent via messaging/email apps.
     */
    private fun shareReport(r: DetectionReport) {
        val location = if (isIndoorReport(r)) {
            getString(R.string.indoor_marker)
        } else {
            getString(R.string.fmt_coords, r.latitude, r.longitude)
        }
        val text = buildString {
            appendLine("RADS Detection Report #${r.id}")
            appendLine("Class: ${r.className}")
            appendLine("Severity: ${prettySeverity(r.severity)}")
            appendLine(String.format(Locale.US, "Confidence: %.4f", r.confidence))
            appendLine(String.format(Locale.US, "Bounding box area: %.2f%%", r.bboxAreaFraction * 100))
            appendLine("Location: $location")
            appendLine(String.format(Locale.US, "GPS accuracy: %.1f m", r.accuracyMeters))
            appendLine(String.format(Locale.US, "Heading: %.1f°", r.headingDegrees))
            appendLine("Time: ${fullTimeFormat.format(Date(r.timestampUtc))}")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.action_share)))
    }

    /** Confirms then deletes this single report, returning to the list. */
    private fun confirmDelete() {
        val r = report ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_delete_single)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        (application as RadsApplication).database.detectionReportDao().deleteById(r.id)
                    }
                    Logger.i(tag, "Deleted report ${r.id}")
                    finish()
                }
            }
            .show()
    }

    // --- Toolbar menu (Share is added in code to stay within file scope) -----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Add a single "Share" action shown as an icon in the top-right.
        val item = menu.add(Menu.NONE, MENU_SHARE, Menu.NONE, R.string.action_share)
        item.setIcon(R.drawable.ic_share_24)
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            MENU_SHARE -> {
                report?.let { shareReport(it) }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        /** Intent extra carrying the primary key of the report to display. */
        const val EXTRA_REPORT_ID = "extra_report_id"

        // Local id for the programmatically-added Share menu item.
        private const val MENU_SHARE = 1
    }
}
