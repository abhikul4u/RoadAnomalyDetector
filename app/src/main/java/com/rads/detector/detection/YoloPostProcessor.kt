/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : YoloPostProcessor.kt
 *  Package : com.rads.detector.detection
 *
 *  Turns the YOLO model's raw output tensor into a clean list of Detection
 *  objects. It decodes each anchor's box geometry and class scores, discards
 *  low-confidence candidates, converts center/size boxes into corner boxes,
 *  and then runs per-class Non-Maximum Suppression (NMS) to remove duplicate
 *  overlapping boxes — yielding the final detections drawn on screen.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.detection

import android.graphics.RectF
import com.rads.detector.config.Config
import com.rads.detector.severity.SeverityCalculator

/**
 * Decodes the raw YOLOv8 detection tensor and runs Non-Maximum Suppression.
 *
 * Expected tensor shape: [1, 4 + num_classes, num_anchors]
 *   where each anchor produces:
 *     [0..3]  → cx, cy, w, h (normalized [0, 1] relative to input image)
 *     [4..6]  → class confidences for 3 classes (MH, PH, WLPH)
 *
 * For 640x640 input, num_anchors = 8400 (the sum of detection heads at
 * strides 8/16/32: 80x80 + 40x40 + 20x20 = 8400).
 *
 * NOTE on layout: the tensor is channel-major (channels before anchors), so a
 * single anchor's 7 values are NOT contiguous — they are strided numAnchors
 * apart. The indexing helper raw[c*numAnchors + a] accounts for this.
 *
 * @param labels class names indexed by class id; used to label each detection.
 * @param numClasses number of object classes the model predicts.
 * @param confThreshold minimum class score to keep a candidate (filters noise).
 * @param iouThreshold overlap ratio above which NMS treats two boxes as duplicates.
 * @param maxDetections hard cap on detections returned per frame.
 */
class YoloPostProcessor(
    private val labels: List<String>,
    private val numClasses: Int = Config.NUM_CLASSES,
    private val confThreshold: Float = Config.CONFIDENCE_THRESHOLD,
    private val iouThreshold: Float = Config.IOU_THRESHOLD,
    private val maxDetections: Int = Config.MAX_DETECTIONS,
) {

    /**
     * Decode the raw tensor and return the final, de-duplicated detections.
     *
     * Two-stage pipeline: (1) scan every anchor, keep only those whose best
     * class score clears [confThreshold], decoding their boxes into pixel-space
     * corners; (2) run per-class NMS to collapse overlapping boxes.
     *
     * @param raw Raw output tensor flattened. Layout: [1, 4+nc, 8400].
     *   We index it as raw[c * numAnchors + a] for the value of channel c
     *   at anchor a (because Ultralytics exports in (batch, channels, anchors)
     *   order).
     * @param numAnchors total number of anchors (e.g. 8400 for 640 input)
     * @param inputWidth model input width in pixels
     * @param inputHeight model input height in pixels
     * @return surviving detections in input-image (640x640) pixel coordinates.
     */
    fun process(
        raw: FloatArray,
        numAnchors: Int,
        inputWidth: Int = Config.INPUT_WIDTH,
        inputHeight: Int = Config.INPUT_HEIGHT,
    ): List<Detection> {

        // Collects every box that survives the confidence filter, pre-NMS.
        val candidates = mutableListOf<Detection>()
        // Total channels per anchor: 4 box regressors + one score per class.
        val totalChannels = 4 + numClasses

        // First pass: filter by confidence threshold
        // Visit each of the (e.g.) 8400 candidate anchors exactly once.
        for (a in 0 until numAnchors) {
            // Find best class
            // YOLOv8 has no separate "objectness" channel — the class score itself
            // is the confidence, so we take the highest-scoring class for this anchor.
            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                // Class scores live in channels [4 .. 4+nc); stride by numAnchors.
                val channelIdx = 4 + c
                val score = raw[channelIdx * numAnchors + a]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }
            // Drop anchors below threshold (and the degenerate no-class case) early
            // so we never allocate a Detection for the overwhelming majority of anchors.
            if (bestScore < confThreshold || bestClass < 0) continue

            // Box coords (model outputs in input-pixel space for the recent
            // Ultralytics exports; for older exports they may be normalized.
            // We handle both by checking magnitude.)
            // Channels 0..3 are the box center (cx, cy) and size (w, h).
            var cx = raw[0 * numAnchors + a]
            var cy = raw[1 * numAnchors + a]
            var w = raw[2 * numAnchors + a]
            var h = raw[3 * numAnchors + a]

            // If values look normalized [0, 1], scale to input pixels.
            // Heuristic: real pixel coords on a 640px input are >> 1.5, so if all
            // four are tiny the export must be normalized and we scale up to pixels.
            if (cx <= 1.5f && cy <= 1.5f && w <= 1.5f && h <= 1.5f) {
                cx *= inputWidth
                cy *= inputHeight
                w *= inputWidth
                h *= inputHeight
            }

            // Convert center+size (cx,cy,w,h) into corner form (left,top,right,bottom),
            // which is what RectF, IoU, and the renderer all expect.
            val left = cx - w / 2f
            val top = cy - h / 2f
            val right = cx + w / 2f
            val bottom = cy + h / 2f

            // Clamp to the image bounds: a box predicted partly off-frame would
            // otherwise produce negative coords or inflated areas downstream.
            val clampedLeft = left.coerceIn(0f, inputWidth.toFloat())
            val clampedTop = top.coerceIn(0f, inputHeight.toFloat())
            val clampedRight = right.coerceIn(0f, inputWidth.toFloat())
            val clampedBottom = bottom.coerceIn(0f, inputHeight.toFloat())

            // Scale-invariant size used by the severity heuristic: box area divided
            // by total image area, giving a fraction in [0, 1].
            val area = (clampedRight - clampedLeft) * (clampedBottom - clampedTop)
            val normArea = area / (inputWidth.toFloat() * inputHeight.toFloat())

            // Defensive: if the labels list is shorter than the class id, skip rather
            // than crash (keeps a mismatched labels file from taking down the frame).
            val className = labels.getOrNull(bestClass) ?: continue

            val det = Detection(
                classId = bestClass,
                className = className,
                confidence = bestScore,
                bbox = RectF(clampedLeft, clampedTop, clampedRight, clampedBottom),
                normalizedBboxArea = normArea,
            )
            // Score severity now while we have the class + size handy.
            det.severity = SeverityCalculator.compute(className, normArea)
            candidates.add(det)
        }

        // Nothing cleared the threshold — return early to skip NMS entirely.
        if (candidates.isEmpty()) return emptyList()

        return nonMaxSuppression(candidates)
    }

    /**
     * Standard greedy Non-Maximum Suppression, performed independently per class.
     *
     * For each class we repeatedly take the highest-confidence box, keep it, and
     * discard any remaining box that overlaps it more than [iouThreshold]. This
     * collapses the many near-duplicate boxes the model emits for one object down
     * to a single best box. Doing it per class lets two different anomaly types
     * legitimately overlap without suppressing each other.
     *
     * @param detections all confidence-filtered candidates.
     * @return kept detections, capped at [maxDetections], sorted by confidence.
     */
    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        // Partition candidates by class so NMS only compares like-with-like.
        val byClass = detections.groupBy { it.classId }
        val kept = mutableListOf<Detection>()

        for ((_, group) in byClass) {
            // Process highest-confidence boxes first — the greedy NMS invariant.
            val sorted = group.sortedByDescending { it.confidence }.toMutableList()
            while (sorted.isNotEmpty()) {
                // The current most-confident box is always a keeper.
                val best = sorted.removeAt(0)
                kept.add(best)
                // Respect the global cap; bail out as soon as we hit it.
                if (kept.size >= maxDetections) return kept

                // Remove every remaining box that overlaps `best` too much — these
                // are considered duplicate detections of the same object.
                val iter = sorted.iterator()
                while (iter.hasNext()) {
                    val other = iter.next()
                    if (iou(best.bbox, other.bbox) > iouThreshold) {
                        iter.remove()
                    }
                }
            }
        }
        // Boxes were kept class-by-class; re-sort globally by confidence and cap so
        // the most confident detections survive regardless of class order.
        return kept.sortedByDescending { it.confidence }.take(maxDetections)
    }

    /**
     * Compute Intersection-over-Union (IoU) of two boxes.
     *
     * IoU = area(overlap) / area(union), a value in [0, 1] where 1 means the
     * boxes are identical and 0 means they don't touch. It is the standard
     * overlap metric NMS uses to decide whether two boxes describe one object.
     *
     * @param a,b boxes in corner form.
     * @return their IoU, or 0 if the union is degenerate.
     */
    private fun iou(a: RectF, b: RectF): Float {
        // Intersection rectangle = the overlapping region's corners.
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        // Clamp at 0: if the boxes don't overlap on an axis the gap is negative,
        // which must count as zero intersection rather than a spurious area.
        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH
        // Union = sum of both areas minus the double-counted intersection.
        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)
        val union = aArea + bArea - interArea
        // Guard against divide-by-zero for empty/degenerate boxes.
        return if (union <= 0f) 0f else interArea / union
    }
}
