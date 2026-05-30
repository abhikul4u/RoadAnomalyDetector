/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : MainActivity.kt
 *  Package : com.rads.detector
 *
 *  The primary screen of the application and the orchestrator of the live
 *  detection pipeline. It wires together the camera feed, the TensorFlow Lite
 *  detector, location sampling, on-screen alerts, and persistence of confirmed
 *  road-anomaly reports. In short, this Activity is where camera frames flow in,
 *  get analyzed for anomalies, and confirmed detections are surfaced to the user
 *  and saved to the local database queue.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rads.detector.alerts.AlertManager
import com.rads.detector.camera.CameraManager
import com.rads.detector.camera.FrameAnalyzer
import com.rads.detector.databinding.ActivityMainBinding
import com.rads.detector.detection.Detector
import com.rads.detector.detection.ImagePreprocessor
import com.rads.detector.location.LocationProvider
import com.rads.detector.reporting.PersistenceTracker
import com.rads.detector.reporting.ReportBuilder
import com.rads.detector.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main screen of the RADS app: hosts the camera preview and drives the
 * end-to-end real-time detection loop.
 *
 * Responsibilities:
 *  - Inflate the UI and set up collaborators that do not depend on the detector.
 *  - Asynchronously load the (potentially heavy) ML detector off the UI thread.
 *  - Feed camera frames through the detector and render results on an overlay.
 *  - Confirm detections via a persistence/stability filter, raise alerts, and
 *    enqueue reports into the local database for later upload/inspection.
 *  - Clean up all hardware/background resources when the screen is destroyed.
 */
class MainActivity : AppCompatActivity() {

    // Log tag used by [Logger] to attribute messages to this Activity.
    private val tag = "MainActivity"

    // View-binding handle for activity_main.xml; gives type-safe access to views.
    private lateinit var binding: ActivityMainBinding
    // The ML detector; nullable because it is created asynchronously and may
    // fail to load (e.g. missing/invalid model file).
    private var detector: Detector? = null
    // Converts raw camera frames into the tensor format the detector expects.
    private lateinit var preprocessor: ImagePreprocessor
    // Owns the CameraX session; nullable because it is only created once the
    // detector is ready, and released on destroy.
    private var cameraManager: CameraManager? = null
    // Supplies the latest GPS fix used to geotag anomaly reports.
    private lateinit var locationProvider: LocationProvider
    // Assembles a persistable report (anomaly + location + metadata) per detection.
    private lateinit var reportBuilder: ReportBuilder
    // Plays audible/visual alerts for confirmed detections; created with detector.
    private var alertManager: AlertManager? = null
    // Temporal filter that only lets through detections that persist across
    // several frames, suppressing one-off false positives.
    private val persistenceTracker = PersistenceTracker()

    /**
     * Standard Activity creation hook. Inflates the layout, wires up the
     * lightweight collaborators immediately, and kicks off asynchronous loading
     * of the heavyweight ML detector so the UI never blocks.
     *
     * @param savedInstanceState previously saved state, if the Activity is being
     *        recreated; unused here because detection state is transient.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up things that don't need the detector — these are cheap to build
        // and are required regardless of whether the model loads successfully.
        preprocessor = ImagePreprocessor()
        locationProvider = LocationProvider(this)
        reportBuilder = ReportBuilder(this, locationProvider)
        // Begin sampling GPS now so a fix is available by the time detections occur.
        locationProvider.start()

        // Navigate to the settings screen when the user taps the settings button.
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Start observing the persisted report queue so the on-screen counter
        // stays in sync with the database.
        observeReportQueue()

        // Show loading overlay and initialize detector on background thread.
        // The overlay communicates that the app is busy loading the model.
        binding.loadingOverlay.visibility = View.VISIBLE
        initializeDetectorAsync()
    }

    /**
     * Loads the ML [Detector] on a background dispatcher (model loading and
     * delegate initialization are expensive and must not stall the UI thread),
     * then — only on success — wires up the camera and alert pipeline back on
     * the main thread.
     *
     * On failure (e.g. the bundled `model.tflite` is missing or corrupt) the
     * loading overlay is hidden and an error message is shown instead.
     */
    private fun initializeDetectorAsync() {
        lifecycleScope.launch {
            // Perform the blocking detector construction off the main thread.
            val initialized = withContext(Dispatchers.IO) {
                try {
                    val d = Detector(this@MainActivity)
                    detector = d
                    Logger.i(tag, "Detector initialized with delegate: ${d.activeDelegate}")
                    true
                } catch (t: Throwable) {
                    // Any throwable here means the model could not be used; report
                    // it and signal failure so the UI can show an error state.
                    Logger.e(tag, "Detector init failed — model.tflite missing or invalid?", t)
                    false
                }
            }

            // If the detector did not load, surface an error and stop here —
            // there is no point starting the camera with no model to run.
            if (!initialized) {
                binding.loadingOverlay.visibility = View.GONE
                binding.tvStatus.text = getString(R.string.error_model_load)
                binding.tvStatus.visibility = View.VISIBLE
                return@launch
            }

            // Detector ready — start camera + alerts on main thread.
            // Re-read the field into a local val to guard against it being nulled.
            val d = detector ?: return@launch
            alertManager = AlertManager(this@MainActivity)

            // The analyzer bridges CameraX frames to the detector: each frame is
            // preprocessed, run through [d], and the results delivered via callback.
            val analyzer = FrameAnalyzer(d, preprocessor) { detections, w, h ->
                onDetectionResult(detections, w, h)
            }

            // Build and immediately start the camera session bound to this
            // Activity's lifecycle so it auto-stops when the screen is paused.
            cameraManager = CameraManager(
                context = this@MainActivity,
                lifecycleOwner = this@MainActivity,
                previewView = binding.previewView,
                analyzer = analyzer,
            ).also { it.start() }

            // Show which compute delegate (CPU/GPU/NNAPI) is actually in use and
            // remove the loading overlay now that the live feed is running.
            binding.tvDelegate.text = getString(R.string.fmt_delegate, d.activeDelegate)
            binding.loadingOverlay.visibility = View.GONE
        }
    }

    /**
     * Callback invoked (on a background/analysis thread) for every analyzed
     * camera frame. It updates the UI overlay, applies the temporal stability
     * filter, raises alerts, and persists confirmed detections.
     *
     * @param detections the raw detections found in the current frame.
     * @param sourceW    width in pixels of the source frame the detections refer
     *                   to, used to scale bounding boxes onto the preview.
     * @param sourceH    height in pixels of the source frame, used for scaling.
     */
    private fun onDetectionResult(
        detections: List<com.rads.detector.detection.Detection>,
        sourceW: Int,
        sourceH: Int,
    ) {
        // Bail out if the detector has been torn down (e.g. Activity destroyed).
        val d = detector ?: return
        // UI mutations must happen on the main thread regardless of which thread
        // the analyzer delivered the result on.
        runOnUiThread {
            // Tell the overlay the source frame dimensions so it can map detection
            // coordinates onto the (possibly differently-sized) preview view.
            binding.overlay.setSourceSize(sourceW, sourceH)
            binding.overlay.submitDetections(detections)
            // Surface a rough performance figure (average inference time in ms).
            binding.tvFps.text = getString(
                R.string.fmt_perf,
                d.avgInferenceMs().toInt()
            )
            // If we have a current GPS fix, display the coordinates.
            locationProvider.current()?.let { loc ->
                binding.tvGps.text = getString(R.string.fmt_gps, loc.latitude, loc.longitude)
            }
        }

        // Pass detections through the stability filter; only those that have been
        // seen consistently across frames are returned as "stable" (confirmed).
        val stable = persistenceTracker.update(detections)
        // Nothing confirmed this frame — skip alerting and persistence.
        if (stable.isEmpty()) return

        // Notify the user about the newly confirmed anomalies.
        alertManager?.onDetections(stable)

        // Persist each confirmed detection on the IO dispatcher so database writes
        // never block the detection/UI threads.
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = (application as RadsApplication).database.detectionReportDao()
            for (det in stable) {
                // build() may return null when, e.g., no location is available yet;
                // in that case we simply skip persisting this detection.
                val report = reportBuilder.build(det) ?: continue
                try {
                    dao.insert(report)
                } catch (t: Throwable) {
                    // A single failed insert should not crash the pipeline.
                    Logger.e(tag, "Failed to persist report", t)
                }
            }
        }
    }

    /**
     * Subscribes to the live count of queued detection reports in the database
     * and keeps the on-screen queue counter updated. Uses [collectLatest] so a
     * rapid burst of updates only renders the most recent value.
     */
    private fun observeReportQueue() {
        val dao = (application as RadsApplication).database.detectionReportDao()
        lifecycleScope.launch {
            dao.countFlow().collectLatest { count ->
                binding.tvQueueCount.text = getString(R.string.fmt_queue, count)
            }
        }
    }

    /**
     * Releases all long-lived/hardware resources when the Activity is destroyed
     * to avoid leaking the camera, GPS listener, native model memory, or audio
     * resources held by the alert manager.
     */
    override fun onDestroy() {
        super.onDestroy()
        cameraManager?.release()
        // Guard against stopping a provider that was never initialized (e.g. if
        // onCreate failed early).
        if (::locationProvider.isInitialized) locationProvider.stop()
        detector?.close()
        alertManager?.release()
    }
}
