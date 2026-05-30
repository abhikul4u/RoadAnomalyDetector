/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : DetectionOverlayView.kt
 *  Package : com.rads.detector.overlay
 *
 *  A transparent custom View layered directly on top of the CameraX preview.
 *  It is the final, user-facing stage of the RADS pipeline: it receives the
 *  detector's bounding boxes (in camera-frame coordinates), scales them into
 *  on-screen View coordinates so they align with the live preview, and paints
 *  each box plus a colour-coded class/severity/confidence label. All drawing is
 *  lightweight Canvas work performed on the UI thread.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.rads.detector.detection.Detection
import com.rads.detector.severity.SeverityLevel

/**
 * Custom View overlaid on top of the camera preview. Draws bounding boxes,
 * class+severity labels, and confidence. Designed to be drawn cheaply on
 * the UI thread at preview frame rate.
 *
 * Coordinate system: detections come in source (camera frame) coordinates.
 * We map them into View coordinates using the [setSourceSize] hint.
 *
 * The three constructor parameters are the standard Android View constructor
 * signature; [JvmOverloads] generates the overloads so this view can be both
 * inflated from XML (attrs/defStyleAttr supplied by the framework) and created
 * programmatically.
 *
 * @param context      The hosting Context.
 * @param attrs        Inflation attributes when created from XML, else null.
 * @param defStyleAttr Default style attribute resource, 0 for none.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // Paint for the bounding-box outline. STROKE so only the rectangle border
    // is drawn (the interior stays transparent, leaving the preview visible).
    // Anti-aliasing smooths the edges; colour is set per-detection at draw time.
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    // Paint for the solid label background pill. FILL so it forms an opaque
    // backdrop that makes the white label text legible over any scene.
    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Paint for the label text itself: white, bold, anti-aliased for clarity
    // against the coloured background drawn behind it.
    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // The most recent detections to render. Replaced wholesale on each update.
    private var detections: List<Detection> = emptyList()

    // Dimensions of the camera frame the detections were computed against.
    // Initialised to 1 (not 0) to avoid divide-by-zero before the real source
    // size is supplied via setSourceSize.
    private var sourceWidth: Int = 1
    private var sourceHeight: Int = 1

    /**
     * Records the camera-frame dimensions that incoming detection coordinates
     * are expressed in. Required so [onDraw] can scale boxes from source space
     * into this View's pixel space.
     *
     * @param width  Source frame width in pixels.
     * @param height Source frame height in pixels.
     */
    fun setSourceSize(width: Int, height: Int) {
        // Ignore non-positive sizes; they would corrupt the scale factor.
        if (width <= 0 || height <= 0) return
        sourceWidth = width
        sourceHeight = height
    }

    /**
     * Supplies a fresh set of detections to display and requests a redraw.
     * Safe to call from any thread: [postInvalidateOnAnimation] schedules the
     * invalidate on the UI thread, synced to the display's vsync so we don't
     * draw more often than the screen refreshes.
     *
     * @param newDetections The latest detections, in source coordinates.
     */
    fun submitDetections(newDetections: List<Detection>) {
        detections = newDetections
        // Coalesce redraws to the next animation frame rather than forcing an
        // immediate (and possibly cross-thread) invalidate.
        postInvalidateOnAnimation()
    }

    /**
     * Renders every current detection. Called by the framework whenever the
     * view is invalidated. All geometry is recomputed here because the View's
     * size or the source size may have changed since the last frame.
     *
     * @param canvas The Canvas to draw onto, sized to this View's bounds.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Nothing to draw; skip all the scaling math.
        if (detections.isEmpty()) return

        // PreviewView uses center-crop FILL by default; we mirror that mapping
        // so bounding boxes line up with what the user sees.
        //
        // Center-crop means the camera frame is scaled up uniformly until it
        // fully covers the view (the larger of the two axis scales wins), then
        // centered — so the overflow on the longer axis is cropped off both
        // edges. We must reproduce exactly this transform or boxes would drift.
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val srcW = sourceWidth.toFloat()
        val srcH = sourceHeight.toFloat()
        // maxOf picks the FILL (cover) scale: both dimensions scaled by the
        // same factor, the larger ratio guaranteeing full coverage.
        val scale = maxOf(viewW / srcW, viewH / srcH)
        // Size of the scaled frame; one dimension equals the view, the other
        // overflows and is cropped.
        val displayW = srcW * scale
        val displayH = srcH * scale
        // Negative offsets shift the oversized frame so it is centered, pushing
        // the cropped overflow equally off both edges.
        val offsetX = (viewW - displayW) / 2f
        val offsetY = (viewH - displayH) / 2f

        for (det in detections) {
            // Apply the same scale-then-offset transform to each box corner to
            // move it from source pixels into on-screen view pixels.
            val left = det.bbox.left * scale + offsetX
            val top = det.bbox.top * scale + offsetY
            val right = det.bbox.right * scale + offsetX
            val bottom = det.bbox.bottom * scale + offsetY

            // Colour encodes severity so users can triage at a glance.
            val color = colorForSeverity(det.severity)
            boxPaint.color = color
            // Slightly rounded corners (8px radius) for a softer look.
            canvas.drawRoundRect(RectF(left, top, right, bottom), 8f, 8f, boxPaint)

            // Label
            // Compose "class · severity · confidence%" as the human-readable tag.
            val confPct = (det.confidence * 100).toInt()
            val labelText = "${det.className} · ${det.severity.displayName} · $confPct%"
            // Measure the text so the background pill is sized to fit it.
            val textWidth = labelTextPaint.measureText(labelText)
            val padding = 12f
            // Position the label just above the box; coerceAtLeast(0f) clamps it
            // on-screen so labels for boxes near the top edge aren't clipped off.
            val labelBgTop = (top - 48f).coerceAtLeast(0f)
            val labelBgLeft = left.coerceAtLeast(0f)
            // Background rectangle: text width plus padding on both sides, 48px tall.
            val labelBg = RectF(
                labelBgLeft,
                labelBgTop,
                labelBgLeft + textWidth + padding * 2,
                labelBgTop + 48f
            )
            // Fill the pill with the same severity colour as the box.
            labelBgPaint.color = color
            canvas.drawRoundRect(labelBg, 6f, 6f, labelBgPaint)
            // Draw the text inset by padding from the left and lifted 12px from
            // the pill's bottom (drawText's y is the text baseline, not the top).
            canvas.drawText(
                labelText,
                labelBg.left + padding,
                labelBg.bottom - 12f,
                labelTextPaint
            )
        }
    }

    /**
     * Maps a [SeverityLevel] to the colour used for its box and label, using a
     * traffic-light gradient (red = worst, green = least severe) so severity is
     * instantly recognisable.
     *
     * @param severity The detection's classified severity.
     * @return An ARGB colour int for the given severity.
     */
    private fun colorForSeverity(severity: SeverityLevel): Int = when (severity) {
        SeverityLevel.CRITICAL -> Color.rgb(220, 38, 38)   // red
        SeverityLevel.HIGH -> Color.rgb(234, 88, 12)       // orange
        SeverityLevel.MEDIUM -> Color.rgb(202, 138, 4)     // yellow
        SeverityLevel.LOW -> Color.rgb(22, 163, 74)        // green
    }
}
