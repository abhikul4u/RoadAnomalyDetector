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

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rads.detector.config.Config
import com.rads.detector.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings/diagnostics Activity. Displays the immutable model configuration and
 * detection thresholds, shows how many reports are queued locally, and lets the
 * user clear that queue.
 */
class SettingsActivity : AppCompatActivity() {

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
}
