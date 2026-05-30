/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : FrameAnalyzer.kt
 *  Package : com.rads.detector.camera
 *
 *  The bridge between the camera frame stream and the neural-network detector.
 *  CameraX hands each captured frame to this analyzer; it throttles the rate to
 *  a target inference FPS, drops frames while the detector is busy, preprocesses
 *  the chosen frame, runs detection, and maps the resulting bounding boxes from
 *  the model's input space back into original camera-frame coordinates before
 *  handing them to the UI via a callback. It is the heart of the capture-to-
 *  detection stage of the RADS pipeline.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.rads.detector.config.Config
import com.rads.detector.detection.Detection
import com.rads.detector.detection.Detector
import com.rads.detector.detection.ImagePreprocessor
import com.rads.detector.util.FpsMeter
import com.rads.detector.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX [ImageAnalysis.Analyzer] that runs detection on a throttled subset
 * of camera frames.
 *
 * Backpressure: drops frames if the detector is still busy on a previous one,
 * so we never queue stale frames. The latest frame always wins.
 *
 * Throttling: only runs inference at TARGET_INFERENCE_FPS, regardless of
 * incoming camera FPS. Saves power and heat without sacrificing usefulness.
 */
/**
 * CameraX [ImageAnalysis.Analyzer] that runs detection on a throttled subset
 * of camera frames.
 *
 * Backpressure: drops frames if the detector is still busy on a previous one,
 * so we never queue stale frames. The latest frame always wins.
 *
 * Throttling: only runs inference at TARGET_INFERENCE_FPS, regardless of
 * incoming camera FPS. Saves power and heat without sacrificing usefulness.
 *
 * @param detector     The on-device model wrapper that produces raw detections
 *                     from a preprocessed input buffer.
 * @param preprocessor Converts a camera [ImageProxy] into the fixed-size tensor
 *                     the model expects, and remembers the scaling used so we
 *                     can map results back to the original frame.
 * @param onResult     Callback invoked with the mapped detections plus the
 *                     source frame's width/height (so the overlay knows the
 *                     coordinate space the boxes live in). Runs on the analyzer
 *                     executor thread, not the main thread.
 */
class FrameAnalyzer(
    private val detector: Detector,
    private val preprocessor: ImagePreprocessor,
    private val onResult: (List<Detection>, sourceWidth: Int, sourceHeight: Int) -> Unit,
) : ImageAnalysis.Analyzer {

    // Logcat tag for messages emitted from this analyzer.
    private val tag = "FrameAnalyzer"

    // Guards against overlapping inference. compareAndSet gives us a lock-free
    // "is the detector currently working?" flag; if true, new frames are
    // dropped rather than queued.
    private val busy = AtomicBoolean(false)

    // Minimum gap between inferences in milliseconds, derived from the target
    // FPS. e.g. a target of 5 FPS yields a 200 ms interval.
    private val targetFrameIntervalMs = 1000L / Config.TARGET_INFERENCE_FPS

    // Timestamp (ms) of the last frame we actually ran inference on; used to
    // enforce the throttle interval above.
    private var lastInferenceTime = 0L

    // FPS meters for diagnostics/HUD: how fast the camera delivers frames vs.
    // how fast we actually run inference (the latter is throttled and lower).
    val cameraFps = FpsMeter()
    val inferenceFps = FpsMeter()

    /**
     * Called by CameraX on the analyzer executor for each delivered frame.
     *
     * Responsibilities, in order: count the frame, apply FPS throttling, claim
     * the busy lock (dropping the frame if inference is already in flight),
     * preprocess + run inference, map boxes back to source coordinates, emit
     * the result, and — critically — always close the [ImageProxy] so CameraX
     * can recycle the underlying buffer and deliver the next frame.
     *
     * @param image The camera frame. Must be closed before returning, or the
     *              pipeline will stall once the image buffer pool is exhausted.
     */
    override fun analyze(image: ImageProxy) {
        // Record every incoming frame so cameraFps reflects true camera rate.
        cameraFps.tick()

        try {
            val now = System.currentTimeMillis()
            // Throttle: if not enough time has elapsed since the last inference,
            // skip this frame entirely (but it is still closed in the finally).
            if (now - lastInferenceTime < targetFrameIntervalMs) {
                return  // Throttle — skip this frame
            }
            // Try to claim the busy flag. If it was already true, the detector
            // is mid-inference on an earlier frame, so we drop this one to avoid
            // building up latency (the "latest frame wins" strategy).
            if (!busy.compareAndSet(false, true)) {
                return  // Detector still working — drop frame
            }

            try {
                // We committed to processing this frame; reset the throttle clock.
                lastInferenceTime = now
                // Preprocess: resize/normalize the YUV frame into the model's
                // fixed input tensor (typically 640x640). The preprocessor also
                // records the original frame dimensions and the scaling applied.
                val inputBuffer = preprocessor.process(image)
                // Run the neural network; detections are in the model's input
                // coordinate space, not the camera frame's.
                val rawDetections = detector.detect(inputBuffer)

                // Map detections from 640x640 input space → original frame space
                // so the overlay can position boxes over the live preview. The
                // source dimensions describe the coordinate system of the mapped
                // boxes and are forwarded to the UI.
                val sourceW = preprocessor.lastSourceWidth
                val sourceH = preprocessor.lastSourceHeight
                val mapped = rawDetections.map { det ->
                    // Convert each box's corners back into source pixel coords,
                    // undoing the resize/letterbox the preprocessor applied.
                    val coords = preprocessor.mapBboxToSource(
                        det.bbox.left, det.bbox.top, det.bbox.right, det.bbox.bottom
                    )
                    // Copy the detection with a remapped bounding box. severity
                    // is reassigned explicitly because it is a mutable property
                    // not carried by data-class copy().
                    det.copy(bbox = android.graphics.RectF(coords[0], coords[1], coords[2], coords[3]))
                        .also { it.severity = det.severity }
                }

                // Count a successful inference and publish results to the caller.
                inferenceFps.tick()
                onResult(mapped, sourceW, sourceH)
            } finally {
                // Release the busy lock so the next eligible frame can proceed,
                // whether inference succeeded or threw.
                busy.set(false)
            }
        } catch (t: Throwable) {
            // Never let an analysis error propagate and kill the camera thread.
            Logger.e(tag, "Frame analysis failed", t)
            // Defensive: ensure the lock is cleared even on the outer failure
            // path (e.g. if preprocessing threw before the inner finally ran).
            busy.set(false)
        } finally {
            image.close()  // CRITICAL: must close every ImageProxy
        }
    }
}
