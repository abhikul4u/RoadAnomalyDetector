/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : Detector.kt
 *  Package : com.rads.detector.detection
 *
 *  Owns the TensorFlow Lite interpreter that runs the YOLO road-anomaly model.
 *  It loads the model and label assets, picks the fastest available compute
 *  backend (GPU delegate, falling back to multi-threaded XNNPACK CPU), runs the
 *  forward pass on a preprocessed input buffer, and hands the raw output tensor
 *  to the YoloPostProcessor for decoding + NMS. This is the single hot path that
 *  turns a normalized image buffer into a list of Detection objects.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.detection

import android.content.Context
import androidx.camera.core.ImageProxy
import com.rads.detector.config.Config
import com.rads.detector.util.Logger
import com.rads.detector.util.PerfTimer
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Core TFLite inference engine. One instance per app lifetime.
 *
 * This class is deliberately heavyweight to construct (it memory-maps the model,
 * spins up a hardware delegate, and JIT-warms the graph), so it is created once
 * and reused for every camera frame rather than per-detection.
 *
 * Delegate priority chain:
 *   1. GPU delegate  — best perf on most modern phones (Mali / Adreno / Mali-G)
 *   2. XNNPACK CPU   — strong CPU fallback (always available)
 *
 * NNAPI is intentionally NOT used: Google deprecated it in Android 15, and the
 * Edge 60 series runs Android 16. GPU + XNNPACK is the recommended path.
 *
 * @param context Android context, used only at init time to read the model and
 *   label files out of the app's bundled assets.
 */
class Detector(context: Context) {

    // Logcat tag for all messages emitted by this class.
    private val tag = "Detector"

    // The TFLite interpreter that actually executes the model graph. Final once
    // built in init {} so the hot detect() path never re-checks it for null.
    private val interpreter: Interpreter
    // Decodes the raw model tensor into Detection objects and applies NMS.
    private val postProcessor: YoloPostProcessor
    // Rolling stopwatch used to report average inference latency for the HUD.
    private val perfTimer = PerfTimer()

    // Number of candidate boxes the model emits per frame (e.g. 8400 for 640x640).
    // Read from the model's output tensor shape at load time so we never hard-code it.
    private val numAnchors: Int
    // Pre-allocated [1][channels][anchors] output container handed to the interpreter.
    // Allocated once and reused every frame to avoid per-frame GC churn.
    private val outputBuffer: Array<Array<FloatArray>>

    // Human-readable name of the backend that actually got selected ("GPU" or
    // "CPU+XNNPACK"); surfaced in the UI/diagnostics so we know what's running.
    val activeDelegate: String

    init {
        // Memory-map the .tflite model and read the class labels out of assets.
        // Mapping (vs. copying) keeps the model file off the Java heap.
        val model = loadModelFile(context, Config.MODEL_FILENAME)
        val labels = loadLabels(context, Config.LABELS_FILENAME)

        Logger.i(tag, "Loaded ${labels.size} labels: $labels")
        // Post-processor needs the labels so it can attach a className to each box.
        postProcessor = YoloPostProcessor(labels)

        // Build the interpreter configuration. We try the GPU delegate first and
        // only configure the CPU/XNNPACK path if GPU initialization fails.
        val options = Interpreter.Options()
        val gpuOk = trySetupGpu(options)
        if (!gpuOk) {
            // CPU fallback: XNNPACK provides heavily-optimized float kernels, and
            // splitting across several threads recovers much of the lost throughput.
            options.numThreads = Config.CPU_NUM_THREADS
            options.useXNNPACK = true
            activeDelegate = "CPU+XNNPACK"
            Logger.i(tag, "Using CPU with XNNPACK, threads=${Config.CPU_NUM_THREADS}")
        } else {
            activeDelegate = "GPU"
            Logger.i(tag, "Using GPU delegate")
        }

        // Instantiate the interpreter with whichever backend was selected above.
        interpreter = Interpreter(model, options)

        // Verify output tensor shape and pre-allocate output buffer.
        // Reading the shape from the model (rather than assuming it) lets us fail
        // loudly if the bundled model doesn't match what the code expects.
        val outShape = interpreter.getOutputTensor(0).shape()
        Logger.i(tag, "Model output shape: ${outShape.toList()}")
        // Ultralytics export: [1, 4+nc, num_anchors]
        // dim0 = batch (always 1), dim1 = channels (4 box coords + per-class scores),
        // dim2 = anchors (candidate boxes). Anything else means a wrong/old model.
        require(outShape.size == 3 && outShape[0] == 1) {
            "Unexpected model output shape: ${outShape.toList()}"
        }
        val channels = outShape[1]
        numAnchors = outShape[2]
        // Guard that the model's class count agrees with Config: 4 box regressors
        // plus one confidence channel per class. A mismatch would silently corrupt
        // decoding, so we turn it into an immediate, descriptive crash instead.
        require(channels == 4 + Config.NUM_CLASSES) {
            "Model has $channels output channels, expected ${4 + Config.NUM_CLASSES}. " +
                    "Check NUM_CLASSES in Config or re-export the model."
        }
        // Allocate the [1][channels][anchors] result buffer once; reused every frame.
        outputBuffer = Array(1) { Array(channels) { FloatArray(numAnchors) } }

        // Warmup pass to JIT-compile the graph (prevents first-frame stutter)
        // The first real inference would otherwise pay the kernel-compilation cost
        // and drop a frame; we burn that cost here on a dummy buffer during startup.
        warmup()
    }

    // Holds the live GPU delegate so we can explicitly free its native resources
    // in close(). Nullable because we may run on the CPU path with no delegate.
    private var gpuDelegate: GpuDelegate? = null

    /**
     * Attempt to attach a GPU delegate to the given interpreter [options].
     *
     * GPU execution is the fastest path on most phones but is not universally
     * supported, so this is best-effort: any failure is caught, logged, and the
     * caller transparently falls back to the CPU path.
     *
     * @param options interpreter options to mutate by adding the GPU delegate.
     * @return true if a GPU delegate was successfully added, false otherwise.
     */
    private fun trySetupGpu(options: Interpreter.Options): Boolean {
        // Try GPU delegate with FP16 precision loss allowed (faster, slight accuracy trade-off)
        try {
            // Ask TFLite whether this device has a supported GPU configuration.
            val compat = CompatibilityList()
            val supported = compat.isDelegateSupportedOnThisDevice
            Logger.i(tag, "GPU delegate supported: $supported")

            val delegateOpts = if (supported) {
                // Use the tuned options TFLite recommends for this exact device.
                compat.bestOptionsForThisDevice
            } else {
                // Force-try with relaxed options for FP16 models
                // Even when the compat check says "no", many GPUs still run an FP16
                // model fine. We allow precision loss (FP16 math) and quantized ops,
                // and bias the scheduler toward sustained throughput over latency.
                GpuDelegate.Options().apply {
                    setPrecisionLossAllowed(true)
                    setQuantizedModelsAllowed(true)
                    setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                }
            }

            // Create the delegate, remember it for later cleanup, and attach it.
            val delegate = GpuDelegate(delegateOpts)
            gpuDelegate = delegate
            options.addDelegate(delegate)
            Logger.i(tag, "GPU delegate added successfully")
            return true
        } catch (t: Throwable) {
            // Any GPU failure (driver bug, unsupported op, OOM) is non-fatal: log it,
            // release any half-constructed native delegate, and signal CPU fallback.
            Logger.w(tag, "GPU delegate init failed: ${t.message}", t)
            gpuDelegate?.close()
            gpuDelegate = null
        }

        return false
    }

    /**
     * Run a couple of throwaway inferences on a zero-filled buffer.
     *
     * TFLite lazily compiles/optimizes the graph on first use; doing it here at
     * construction time moves that one-time cost off the first camera frame, so
     * the live preview doesn't visibly stutter when detection begins.
     */
    private fun warmup() {
        Logger.d(tag, "Running warmup inference...")
        // A correctly-sized but uninitialized direct buffer is enough to trigger
        // graph compilation; the garbage output is simply discarded.
        val dummy = ByteBuffer.allocateDirect(4 * Config.INPUT_WIDTH * Config.INPUT_HEIGHT * 3)
            .order(java.nio.ByteOrder.nativeOrder())
        repeat(2) {
            interpreter.run(dummy, outputBuffer)
        }
    }

    /**
     * Run a single inference pass.
     *
     * Times the forward pass, then flattens the interpreter's 3-D output into the
     * 1-D layout the post-processor expects before decoding it into detections.
     *
     * @param inputBuffer Normalized float32 buffer from [ImagePreprocessor].
     * @return Post-processed detections in INPUT-image coordinates (640x640).
     */
    fun detect(inputBuffer: ByteBuffer): List<Detection> {
        // Bracket only the model execution with the timer so the reported latency
        // reflects pure inference, not the flattening/post-processing that follows.
        perfTimer.start()
        interpreter.run(inputBuffer, outputBuffer)
        perfTimer.stop()

        // Flatten [1][channels][anchors] -> FloatArray for post-processor
        // The post-processor indexes a single contiguous array as raw[c*anchors + a];
        // we lay the per-channel rows out back-to-back to match that scheme exactly.
        val channels = 4 + Config.NUM_CLASSES
        val flat = FloatArray(channels * numAnchors)
        for (c in 0 until channels) {
            // arraycopy is a native bulk memcpy — far cheaper than an element loop.
            System.arraycopy(outputBuffer[0][c], 0, flat, c * numAnchors, numAnchors)
        }
        return postProcessor.process(flat, numAnchors)
    }

    /** Average inference time in milliseconds over recent frames. */
    fun avgInferenceMs(): Float = perfTimer.avgMs()

    /**
     * Release native resources. Safe to call multiple times.
     *
     * Both the interpreter and the GPU delegate hold off-heap/native memory that
     * the GC won't reclaim, so they must be closed explicitly. Each close is wrapped
     * in its own try/catch so a failure freeing one never leaks the other.
     */
    fun close() {
        try {
            interpreter.close()
        } catch (_: Throwable) { /* ignore */ }
        try {
            gpuDelegate?.close()
        } catch (_: Throwable) { /* ignore */ }
    }

    /**
     * Memory-map a model file from assets without copying it onto the heap.
     *
     * @param context provides access to the asset manager.
     * @param filename asset path of the .tflite model.
     * @return a read-only [MappedByteBuffer] view of the model bytes.
     */
    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        // openFd gives the offset/length of this asset inside the (possibly packed) APK.
        val fd = context.assets.openFd(filename)
        val input = FileInputStream(fd.fileDescriptor)
        val channel = input.channel
        // Map exactly the model's slice of the file as read-only shared memory.
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    /**
     * Read the class-label file, one label per line.
     *
     * @param context provides access to the asset manager.
     * @param filename asset path of the labels text file.
     * @return labels in order, trimmed, with blank lines dropped — index aligns
     *   with the model's class ids.
     */
    private fun loadLabels(context: Context, filename: String): List<String> {
        // useLines streams the file and auto-closes it; we trim whitespace and skip
        // empties so a trailing newline or stray blank line can't shift class indices.
        return context.assets.open(filename).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
    }
}
