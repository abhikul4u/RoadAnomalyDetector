/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : SettingsActivity.kt
 *  Package : com.rads.detector
 *
 *  A read-only diagnostics/settings screen that surfaces the detector's static
 *  configuration (model file, input dimensions, class count, and the confidence
 *  and IoU thresholds) and the current size of the pending detection-report
 *  queue. It also offers a maintenance action to clear that queue from the local
 *  database, which is useful during testing and demos of the pipeline.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.rads.detector.config.Config
import com.rads.detector.databinding.ActivitySettingsBinding
import com.rads.detector.util.Logger
import com.rads.detector.util.exportReportsToCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings/diagnostics Activity. Displays the immutable model configuration and
 * detection thresholds, shows how many reports are queued locally, and lets the
 * user clear that queue.
 */
class SettingsActivity : AppCompatActivity() {

    // Log tag used by [Logger] to attribute messages to this screen.
    private val tag = "SettingsActivity"

    // View-binding handle for activity_settings.xml.
    private lateinit var binding: ActivitySettingsBinding

    /**
     * Sets up the settings UI: enables the toolbar up/back button, populates the
     * model-info and threshold labels from the static [Config], shows the queue
     * count, and wires the "clear queue" action.
     *
     * @param savedInstanceState saved instance state, unused here.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Show the up arrow so the user can navigate back to MainActivity.
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Render the static model configuration (filename, expected input size,
        // and number of output classes) for transparency/debugging.
        binding.tvModelInfo.text = getString(
            R.string.fmt_model_info,
            Config.MODEL_FILENAME,
            Config.INPUT_WIDTH,
            Config.INPUT_HEIGHT,
            Config.NUM_CLASSES,
        )
        // Render the detection thresholds: confidence cutoff and the IoU value
        // used during non-max suppression.
        binding.tvThresholds.text = getString(
            R.string.fmt_thresholds,
            Config.CONFIDENCE_THRESHOLD,
            Config.IOU_THRESHOLD,
        )

        // Populate the queue-size label with the current count.
        refreshQueueCount()

        // Open the in-app Reports history screen (list, detail, CSV export).
        binding.btnViewReports.setOnClickListener {
            startActivity(Intent(this, ReportsActivity::class.java))
        }

        // Export all stored reports to a CSV file (then offer to share it).
        binding.btnExportCsv.setOnClickListener {
            exportCsv()
        }

        // Clear all queued reports when the button is tapped. The delete runs on
        // the IO dispatcher (database work off the main thread) and the visible
        // count is refreshed once the deletion completes.
        binding.btnClearQueue.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    (application as RadsApplication).database
                        .detectionReportDao().clearAll()
                }
                refreshQueueCount()
            }
        }
    }

    /**
     * Handles taps on the toolbar's up/back button by finishing this Activity
     * and returning to the previous screen.
     *
     * @return true to indicate the navigation event was handled.
     */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * Queries the current number of queued detection reports (on the IO
     * dispatcher to keep the database read off the UI thread) and updates the
     * on-screen count label with the result.
     */
    private fun refreshQueueCount() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                (application as RadsApplication).database.detectionReportDao().count()
            }
            binding.tvQueueCount.text = getString(R.string.fmt_settings_queue, count)
        }
    }

    /**
     * Reads all stored reports off the main thread and writes them to a CSV file
     * in the app's external Downloads directory. Shows a Toast and skips the
     * write if there is nothing to export; otherwise shows a Snackbar offering
     * to share the file. Mirrors the Reports screen's toolbar export action.
     */
    private fun exportCsv() {
        lifecycleScope.launch {
            val dao = (application as RadsApplication).database.detectionReportDao()
            val reports = withContext(Dispatchers.IO) { dao.getRecent(limit = 1000) }
            // Nothing stored yet — tell the user and don't create an empty file.
            if (reports.isEmpty()) {
                Toast.makeText(
                    this@SettingsActivity, R.string.toast_no_reports_to_export, Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            try {
                val file = withContext(Dispatchers.IO) {
                    exportReportsToCsv(this@SettingsActivity, reports)
                }
                Logger.i(tag, "Exported ${reports.size} reports to ${file.absolutePath}")
                showExportSnackbar(file, reports.size)
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
        // Authority must match the provider in the manifest; packageName already
        // includes any build-type suffix (e.g. ".debug").
        val authority = "$packageName.fileprovider"
        val uri = FileProvider.getUriForFile(this, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.action_share)))
    }
}
