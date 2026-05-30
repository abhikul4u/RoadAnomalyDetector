/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : ReportsActivity.kt
 *  Package : com.rads.detector
 *
 *  In-app history screen that lists every persisted detection report newest
 *  first, with a summary header (totals + per-class/per-severity breakdowns +
 *  last-detection time). It is the entry point for two maintenance actions:
 *  exporting all reports to CSV (shareable via FileProvider) and clearing the
 *  entire history. Tapping a row opens [ReportDetailActivity]. All database work
 *  runs on Dispatchers.IO; the list refreshes in onResume so deletions made on
 *  the detail screen are reflected on return.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.rads.detector.databinding.ActivityReportsBinding
import com.rads.detector.reporting.DetectionReport
import com.rads.detector.storage.ClassCount
import com.rads.detector.storage.SeverityCount
import com.rads.detector.ui.ReportsAdapter
import com.rads.detector.ui.prettySeverity
import com.rads.detector.util.Logger
import com.rads.detector.util.exportReportsToCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lists stored detection reports and hosts the export/clear actions.
 */
class ReportsActivity : AppCompatActivity() {

    // Log tag used by [Logger] to attribute messages to this screen.
    private val tag = "ReportsActivity"

    // View-binding handle for activity_reports.xml.
    private lateinit var binding: ActivityReportsBinding

    // Adapter backing the RecyclerView; taps open the detail screen.
    private lateinit var adapter: ReportsAdapter

    // The most recently loaded reports, kept so the CSV export can reuse exactly
    // what the list is showing without re-querying.
    private var reports: List<DetectionReport> = emptyList()

    /**
     * Inflates the layout, installs the toolbar as the action bar, and sets up
     * the RecyclerView. Data is loaded in [onResume] rather than here so the
     * list is always fresh when the screen becomes visible.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Theme has no action bar, so promote our toolbar to be one. This gives
        // us the options menu (Export/Clear) and the Up button for free.
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_arrow_back_24)

        adapter = ReportsAdapter { report -> openDetail(report) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
    }

    /**
     * Refreshes the list every time the screen is shown so changes made
     * elsewhere (e.g. a delete on the detail screen) are reflected.
     */
    override fun onResume() {
        super.onResume()
        loadData()
    }

    /**
     * Loads the reports and the breakdown aggregations off the main thread, then
     * updates the list, summary card, and empty state on the main thread.
     */
    private fun loadData() {
        lifecycleScope.launch {
            val dao = (application as RadsApplication).database.detectionReportDao()
            // All reads happen together on IO; UI updates resume on Main.
            val loaded = withContext(Dispatchers.IO) {
                val list = dao.getRecent(limit = 1000)
                val classes = dao.classBreakdown()
                val severities = dao.severityBreakdown()
                Triple(list, classes, severities)
            }
            reports = loaded.first
            renderList(reports)
            renderSummary(reports, loaded.second, loaded.third)
        }
    }

    /**
     * Shows either the list (with the summary card) or the empty-state message,
     * depending on whether any reports exist.
     */
    private fun renderList(list: List<DetectionReport>) {
        val empty = list.isEmpty()
        binding.recycler.visibility = if (empty) View.GONE else View.VISIBLE
        binding.summaryCard.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        adapter.submit(list)
    }

    /**
     * Populates the summary header: total count, per-class and per-severity
     * breakdowns, and the relative time of the most recent detection.
     */
    private fun renderSummary(
        list: List<DetectionReport>,
        classes: List<ClassCount>,
        severities: List<SeverityCount>,
    ) {
        binding.tvTotal.text = getString(R.string.fmt_total_reports, list.size)

        val sep = getString(R.string.breakdown_separator)
        // Per-class line, e.g. "MH: 12  •  PH: 28  •  WLPH: 7".
        binding.tvClassBreakdown.text = classes.joinToString(sep) {
            getString(R.string.fmt_breakdown_chunk, it.className, it.count)
        }
        // Per-severity line with title-cased labels, e.g. "Critical: 4  •  ...".
        binding.tvSeverityBreakdown.text = severities.joinToString(sep) {
            getString(R.string.fmt_breakdown_chunk, prettySeverity(it.severity), it.count)
        }

        // "Last detection" uses the newest row (list is sorted newest-first).
        val newest = list.firstOrNull()
        binding.tvLastDetection.text = if (newest != null) {
            val rel = DateUtils.getRelativeTimeSpanString(
                newest.timestampUtc, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            getString(R.string.fmt_last_detection, rel)
        } else {
            ""
        }
    }

    /** Launches the detail screen for the tapped [report]. */
    private fun openDetail(report: DetectionReport) {
        val intent = Intent(this, ReportDetailActivity::class.java)
            .putExtra(ReportDetailActivity.EXTRA_REPORT_ID, report.id)
        startActivity(intent)
    }

    // --- Toolbar menu --------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_reports, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_export_csv -> {
                exportCsv()
                true
            }
            R.id.action_clear_all -> {
                confirmClearAll()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // --- Export CSV ----------------------------------------------------------

    /**
     * Writes the currently loaded reports to a CSV file, then shows a Snackbar
     * with a SHARE action. If there is nothing to export, shows a Toast and
     * skips the file write.
     */
    private fun exportCsv() {
        if (reports.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_reports_to_export, Toast.LENGTH_SHORT).show()
            return
        }
        val snapshot = reports
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    exportReportsToCsv(this@ReportsActivity, snapshot)
                }
                Logger.i(tag, "Exported ${snapshot.size} reports to ${file.absolutePath}")
                showExportSnackbar(file, snapshot.size)
            } catch (t: Throwable) {
                Logger.e(tag, "CSV export failed", t)
                Snackbar.make(binding.root, "Export failed: ${t.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /** Shows the post-export Snackbar with a SHARE action for the CSV file. */
    private fun showExportSnackbar(file: File, count: Int) {
        Snackbar.make(
            binding.root,
            getString(R.string.exported_snackbar, count, file.name),
            Snackbar.LENGTH_LONG,
        ).setAction(R.string.action_share) {
            shareCsv(file)
        }.show()
    }

    /** Fires a chooser to share the CSV file as an attachment via FileProvider. */
    private fun shareCsv(file: File) {
        // Authority must match the provider declared in the manifest. packageName
        // already includes any build-type suffix (e.g. ".debug").
        val authority = "$packageName.fileprovider"
        val uri = FileProvider.getUriForFile(this, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.action_share)))
    }

    // --- Clear all -----------------------------------------------------------

    /** Confirms then deletes every stored report. */
    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_clear_all, reports.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_clear_all) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        (application as RadsApplication).database.detectionReportDao().clearAll()
                    }
                    loadData()
                }
            }
            .show()
    }
}
