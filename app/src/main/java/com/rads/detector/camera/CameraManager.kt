/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : CameraManager.kt
 *  Package : com.rads.detector.camera
 *
 *  Encapsulates all CameraX boilerplate for the RADS pipeline: acquiring the
 *  camera provider, configuring the live Preview use case (what the user sees)
 *  and the ImageAnalysis use case (the frame stream fed to the detector), and
 *  binding both to the host Activity's lifecycle. This is the entry point of
 *  the capture stage — frames flow from here into FrameAnalyzer and onward to
 *  the on-device anomaly detector.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.rads.detector.util.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Wraps CameraX setup. One instance per Activity. Call [start] from
 * onCreate and [release] from onDestroy.
 *
 * The class owns two CameraX "use cases" that share the same physical camera
 * stream:
 *  - [Preview]: renders the live camera feed into the supplied [PreviewView].
 *  - [ImageAnalysis]: delivers a stream of frames to [analyzer] for on-device
 *    anomaly detection.
 * Both are bound to the [LifecycleOwner], so CameraX automatically starts the
 * camera when the owner reaches STARTED and stops it when it is paused/stopped.
 * This lifecycle binding is what frees us from manually opening/closing the
 * camera and handling configuration changes.
 *
 * @param context        Android [Context], used to obtain the camera provider
 *                        and the main-thread executor.
 * @param lifecycleOwner The Activity/Fragment whose lifecycle gates the camera;
 *                        CameraX ties camera open/close to its state.
 * @param previewView    The on-screen surface the live preview is rendered into.
 * @param analyzer       The frame consumer that runs detection on each frame.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val analyzer: FrameAnalyzer,
) {
    // Logcat tag for all messages emitted from this class.
    private val tag = "CameraManager"

    // Dedicated single-thread executor for analysis callbacks. Using a single
    // background thread (rather than the main thread) keeps the UI responsive
    // while inference runs, and serializing on one thread means our analyzer
    // never needs to be re-entrant — frames are processed one at a time.
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Kicks off camera initialization. [ProcessCameraProvider.getInstance]
     * returns a future because acquiring the provider is asynchronous (CameraX
     * must initialize its background process). We attach a listener that runs
     * once the provider is ready and then binds our use cases.
     *
     * The listener is dispatched on the main executor because CameraX use-case
     * binding must happen on the main thread.
     */
    fun start() {
        // Asynchronously request the singleton camera provider for this process.
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                // get() is safe here: the listener only fires once the future
                // is complete, so this does not block.
                val provider = providerFuture.get()
                bindUseCases(provider)
            } catch (t: Throwable) {
                // Provider acquisition can fail on misconfigured/headless
                // devices; log rather than crash so the app degrades gracefully.
                Logger.e(tag, "Camera provider failed", t)
            }
        }, ContextCompat.getMainExecutor(context)) // Run callback on the main thread.
    }

    /**
     * Configures and binds the Preview and ImageAnalysis use cases to the
     * lifecycle. Must be invoked on the main thread (guaranteed by [start]).
     *
     * @param provider The resolved CameraX provider used to bind use cases.
     */
    private fun bindUseCases(provider: ProcessCameraProvider) {
        // 1280x720 input is plenty — preprocessor downsamples to 640 anyway.
        // We share one ResolutionSelector across both use cases so the preview
        // and the analyzed frame have the same aspect ratio, which keeps the
        // overlay coordinate mapping (in DetectionOverlayView) consistent.
        // FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER tells CameraX: if the exact
        // 720p size is unavailable, prefer the nearest higher resolution, then
        // fall back to the nearest lower one.
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()

        // Preview use case: streams the camera feed straight to the on-screen
        // surface. setSurfaceProvider wires this preview to the PreviewView so
        // the user sees the live road view.
        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // ImageAnalysis use case: the analysis pipeline that feeds the detector.
        //  - STRATEGY_KEEP_ONLY_LATEST: if the analyzer is still busy, CameraX
        //    discards intermediate frames and only keeps the newest one. This
        //    prevents a backlog of stale frames and keeps detections "live".
        //  - OUTPUT_IMAGE_FORMAT_YUV_420_888: requests frames in the standard
        //    YUV 4:2:0 planar format that the preprocessor expects (luma plane
        //    plus subsampled chroma planes); CameraX handles any conversion.
        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analyzerExecutor, analyzer) } // Run on bg thread.

        // RADS analyzes the road ahead, so we always use the rear camera.
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            // Unbind any previous bindings first; rebinding without this can
            // throw if the use cases are already attached (e.g. after a restart).
            provider.unbindAll()
            // Bind both use cases to the lifecycle. From here CameraX opens the
            // camera and routes frames to the preview and the analyzer.
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            Logger.i(tag, "Camera bound successfully")
        } catch (t: Throwable) {
            // Binding can fail if too many use cases are requested for the
            // device or the camera is unavailable; log and continue.
            Logger.e(tag, "Camera binding failed", t)
        }
    }

    /**
     * Releases resources owned by this manager. Call from the host's
     * onDestroy. CameraX itself unbinds automatically via the lifecycle, but
     * the analyzer executor is ours to shut down so its worker thread can exit.
     */
    fun release() {
        // Stop accepting new analysis tasks and allow the worker thread to die.
        analyzerExecutor.shutdown()
    }
}
