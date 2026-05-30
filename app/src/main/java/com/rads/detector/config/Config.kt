/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : Config.kt
 *  Package : com.rads.detector.config
 *
 *  Central, compile-time configuration object holding every tunable knob used
 *  by the detection pipeline: model identity and input geometry, confidence /
 *  NMS thresholds, cross-frame persistence rules, severity scaling, runtime
 *  performance budgets, GPS sampling cadence, and the canonical class labels.
 *  Keeping all of these in one place means tuning the detector or swapping the
 *  model is a single-file change rather than a hunt through the codebase.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.config

/**
 * Single source of truth for all tunable detection parameters.
 *
 * Change values here, NOT scattered through the codebase. This makes model
 * swaps and threshold tuning a single-file diff.
 *
 * Implemented as a Kotlin `object` (a singleton) so the values are accessible
 * statically as `Config.X` without instantiation, and as `const val`s so they
 * are inlined at compile time for zero-overhead access on the hot path.
 */
object Config {

    // ─────────────────────────────────────────────────────────────────────
    // MODEL CONFIGURATION
    // These constants identify and describe the on-device TFLite model so the
    // interpreter and pre/post-processing code agree on shapes and assets.
    // ─────────────────────────────────────────────────────────────────────

    // Filename of the quantized/float TFLite graph bundled under assets/.
    // The interpreter loads this by name at startup.
    /** Asset filename for the TFLite model (in app/src/main/assets/) */
    const val MODEL_FILENAME = "model.tflite"

    // Plain-text label map; line N corresponds to class index N emitted by
    // the model. Must stay in sync with the CLASS_* constants below.
    /** Asset filename for class labels (one per line) */
    const val LABELS_FILENAME = "labels.txt"

    /**
     * Model input resolution. MUST match what the .tflite was exported with.
     * Default: 640x640 (recommended for mobile). If you re-export at a
     * different size, change this.
     *
     * The camera frame is letterboxed/resized to exactly these dimensions
     * before being fed to the tensor; a mismatch produces garbage outputs.
     */
    const val INPUT_WIDTH = 640   // tensor width  in pixels expected by the model
    const val INPUT_HEIGHT = 640  // tensor height in pixels expected by the model

    // Total number of distinct anomaly classes the head predicts. Used to
    // size output buffers and validate the label file length.
    /** Number of classes the model detects. */
    const val NUM_CLASSES = 3

    // ─────────────────────────────────────────────────────────────────────
    // DETECTION THRESHOLDS
    // These govern which raw model outputs survive into reported detections.
    // ─────────────────────────────────────────────────────────────────────

    // Final acceptance gate: any box whose class confidence is below this is
    // discarded. Lower = more recall but more false positives; higher = the
    // opposite. Chosen empirically in Chapter 4.2.6.1.
    /** Min confidence to keep a detection. From Chapter 4.2.6.1. */
    const val CONFIDENCE_THRESHOLD = 0.35f

    // Overlap threshold for Non-Maximum Suppression: two boxes overlapping by
    // more than this are treated as the same object and the weaker is dropped,
    // preventing duplicate detections of one anomaly.
    /** IoU threshold for Non-Maximum Suppression. */
    const val IOU_THRESHOLD = 0.45f

    // Pre-filter on raw objectness: anything below this is dropped before
    // class scoring, cheaply pruning the vast majority of empty anchors.
    /** Below this, we skip the detection entirely (low-confidence harvest). */
    const val OBJECTNESS_FLOOR = 0.15f

    // Hard upper bound on detections retained per frame after NMS. Acts as a
    // defensive cap so a pathological frame can't blow up downstream work.
    /** Max detections to keep after NMS (defensive cap). */
    const val MAX_DETECTIONS = 30

    // ─────────────────────────────────────────────────────────────────────
    // PERSISTENCE — only report detections that are stable across frames
    // A single noisy frame should never produce a report; we require the same
    // anomaly to appear consistently before trusting it.
    // ─────────────────────────────────────────────────────────────────────

    // How many consecutive frames an anomaly must be seen in before it is
    // logged. Filters out transient one-frame flickers / false positives.
    /** Min consecutive frames before logging a report (avoid spurious blips). */
    const val MIN_FRAMES_TO_REPORT = 3

    // Spatial-association threshold: a detection in frame N is considered the
    // "same" physical anomaly as one in frame N-1 if their boxes overlap by at
    // least this IoU, letting us count consecutive sightings.
    /** Spatial proximity (IoU) to treat consecutive detections as same anomaly. */
    const val PERSISTENCE_IOU = 0.30f

    // ─────────────────────────────────────────────────────────────────────
    // SEVERITY (Section 4.3 of Chapter 4)
    // Apparent on-screen size is used as a proxy for how serious / close an
    // anomaly is, nudging the reported severity level up or down.
    // ─────────────────────────────────────────────────────────────────────

    // If a box occupies more than this fraction of the frame area, the anomaly
    // is large/near, so severity is escalated by one level.
    /** Bbox area > this fraction of frame → bump severity up by 1 level. */
    const val SEVERITY_LARGE_BBOX_FRAC = 0.08f

    // If a box occupies less than this fraction of the frame area, the anomaly
    // is small/far, so severity is de-escalated by one level.
    /** Bbox area < this fraction of frame → drop severity down by 1 level. */
    const val SEVERITY_SMALL_BBOX_FRAC = 0.02f

    // ─────────────────────────────────────────────────────────────────────
    // PERFORMANCE / THERMAL
    // Budgets that keep the app responsive and the device cool by avoiding
    // unnecessary work on every camera frame.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Target inference FPS. We don't run on every camera frame — that wastes
     * power. 10 FPS is plenty for road detection (cars don't move that fast
     * relative to road features).
     *
     * The capture pipeline throttles to roughly this rate; running the model
     * faster would drain battery and overheat the SoC for no accuracy gain.
     */
    const val TARGET_INFERENCE_FPS = 10

    // Fallback CPU thread count for the interpreter when no hardware
    // accelerator (GPU / NNAPI delegate) is available on the device.
    /** Number of CPU threads if GPU/NNAPI delegate isn't available. */
    const val CPU_NUM_THREADS = 4

    // ─────────────────────────────────────────────────────────────────────
    // LOCATION
    // Controls how often and how finely GPS fixes are sampled for tagging
    // reports with a position.
    // ─────────────────────────────────────────────────────────────────────

    // Desired interval between GPS fixes. 1000 ms (1 Hz) is ample for tagging
    // road anomalies and is far gentler on the battery than high-rate sampling.
    /** GPS sampling interval in milliseconds (1 Hz is plenty). */
    const val LOCATION_INTERVAL_MS = 1000L

    // Minimum movement (in metres) required to emit a new location callback;
    // suppresses redundant updates while the vehicle is stationary.
    /** Minimum displacement before triggering a new location callback. */
    const val LOCATION_MIN_DISPLACEMENT_M = 1.0f

    // ─────────────────────────────────────────────────────────────────────
    // CLASS NAMES (must match labels.txt order)
    // Human-readable codes for each model output index; used when building
    // reports and rendering overlays. Order here must mirror labels.txt.
    // ─────────────────────────────────────────────────────────────────────

    const val CLASS_MH = "MH"      // Open Manhole — uncovered/open manhole hazard
    const val CLASS_PH = "PH"      // Pothole — dry surface pothole
    const val CLASS_WLPH = "WLPH"  // Waterlogged Pothole — pothole hidden by water
}
