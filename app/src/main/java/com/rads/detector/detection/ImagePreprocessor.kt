/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : ImagePreprocessor.kt
 *  Package : com.rads.detector.detection
 *
 *  Bridges the camera and the neural network: it takes a raw CameraX frame in
 *  YUV_420_888 format and produces the exact float tensor the YOLO model wants
 *  (RGB, 640x640, letterboxed, normalized to [0,1]). It also records the
 *  letterbox transform (scale + padding) used on each frame so the post-
 *  processor can later project detection boxes back onto the original frame.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.rads.detector.config.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a CameraX [ImageProxy] (YUV_420_888) into a normalized float
 * [ByteBuffer] suitable for the TFLite model's input tensor.
 *
 * Steps:
 *   1. YUV → ARGB Bitmap (using android.graphics.YuvImage path)
 *   2. Rotate to upright orientation
 *   3. Letterbox into 640x640 (preserves aspect ratio, fills with gray)
 *   4. Normalize [0, 255] → [0, 1] floats and pack into NHWC ByteBuffer
 *
 * "Letterboxing" means we scale the image to fit inside the square input while
 * keeping its aspect ratio, then pad the leftover margins with a neutral gray.
 * This avoids the geometric distortion a naive squash-to-square would introduce
 * (which would deform potholes/cracks and hurt accuracy).
 *
 * The letterboxing parameters are remembered so post-processing can map
 * detections back to the original camera frame coordinates.
 */
class ImagePreprocessor {

    // Target tensor dimensions the model was trained/exported at (e.g. 640x640).
    private val inputW = Config.INPUT_WIDTH
    private val inputH = Config.INPUT_HEIGHT

    /** Reusable buffer to avoid GC pressure. */
    // Sized for W*H pixels * 3 channels * 4 bytes/float. Direct + native byte order
    // so TFLite can read it without an extra copy. Reused every frame instead of
    // allocating ~4.9 MB per call, which would thrash the GC at video frame rates.
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * inputW * inputH * 3)
        .order(ByteOrder.nativeOrder())

    /** Cached letterbox metadata from the most recent call. */
    // These five fields capture the forward transform applied to the latest frame.
    // mapBboxToSource() inverts them to convert model-space boxes back to frame space.
    // All use `private set` so only this class can update them, keeping them in sync
    // with the buffer that was actually produced.
    // Uniform scale factor applied to the source bitmap to fit it into the input square.
    var lastScale: Float = 1f
        private set
    // Horizontal padding (px) added on the left so the scaled image is centered.
    var lastPadLeft: Float = 0f
        private set
    // Vertical padding (px) added on the top so the scaled image is centered.
    var lastPadTop: Float = 0f
        private set
    // Width of the (rotated) source frame, kept for reference/diagnostics.
    var lastSourceWidth: Int = 0
        private set
    // Height of the (rotated) source frame, kept for reference/diagnostics.
    var lastSourceHeight: Int = 0
        private set

    // Solid fill used for the letterbox margins. 114 gray is the Ultralytics/YOLO
    // convention, matching the padding the model saw during training.
    private val letterboxPaint = Paint().apply { color = Color.rgb(114, 114, 114) }
    // FILTER_BITMAP_FLAG enables bilinear filtering when the source is scaled down,
    // giving smoother (less aliased) pixels than nearest-neighbour sampling.
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Process a frame from CameraX and return a ready-to-use input buffer.
     * The buffer is owned by this preprocessor and reused on every call —
     * do NOT hold a reference after the next call.
     *
     * @param image the live camera frame in YUV_420_888.
     * @return the shared, rewound input [ByteBuffer] ready to feed to the model.
     */
    fun process(image: ImageProxy): ByteBuffer {
        // CameraX reports how many degrees the sensor image must be rotated to be
        // upright; we must apply it so the model sees the road right-way-up.
        val rotation = image.imageInfo.rotationDegrees
        val sourceBitmap = imageProxyToBitmap(image)
        val rotated = rotateBitmap(sourceBitmap, rotation)
        lastSourceWidth = rotated.width
        lastSourceHeight = rotated.height

        // Compute letterbox parameters
        // Use the SAME scale for both axes (the min of the two fit ratios) so aspect
        // ratio is preserved; whichever axis doesn't fill the square gets padded.
        val scale = minOf(inputW.toFloat() / rotated.width, inputH.toFloat() / rotated.height)
        val newW = (rotated.width * scale).toInt()
        val newH = (rotated.height * scale).toInt()
        // Split the leftover space evenly so the scaled image sits centered.
        val padLeft = (inputW - newW) / 2f
        val padTop = (inputH - newH) / 2f
        // Remember the transform for the inverse mapping in mapBboxToSource().
        lastScale = scale
        lastPadLeft = padLeft
        lastPadTop = padTop

        // Build the destination square, prefill it with the neutral gray, then blit
        // the scaled source into the centered rectangle.
        val letterboxed = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawRect(0f, 0f, inputW.toFloat(), inputH.toFloat(), letterboxPaint)
        // dst rectangle = where the scaled image lands inside the 640x640 canvas.
        val dst = Rect(
            padLeft.toInt(),
            padTop.toInt(),
            padLeft.toInt() + newW,
            padTop.toInt() + newH
        )
        // src = null means draw the whole bitmap; Android scales it into dst for us.
        canvas.drawBitmap(rotated, null, dst, bmpPaint)

        // Pack into float buffer normalized to [0, 1]
        return packToBuffer(letterboxed)
    }

    /**
     * Flatten an ARGB bitmap into the model's float input buffer.
     *
     * Produces NHWC layout (the channel triplet for each pixel is written
     * contiguously, pixels in row-major order) with channels in RGB order and
     * values normalized to [0, 1] to match how the model was trained.
     *
     * @param bitmap the finished 640x640 letterboxed image.
     * @return the reused [inputBuffer], rewound to position 0 for reading.
     */
    private fun packToBuffer(bitmap: Bitmap): ByteBuffer {
        // Rewind so we overwrite from the start of the reused buffer.
        inputBuffer.rewind()
        // Pull all pixels out in one JNI call (cheaper than per-pixel getPixel()).
        val pixels = IntArray(inputW * inputH)
        bitmap.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)

        // NHWC, float32, channel order RGB, normalized [0, 1]
        for (i in pixels.indices) {
            // Each pixel is packed ARGB in a single int; shift+mask to extract bytes.
            val p = pixels[i]
            inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f)  // R
            inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)   // G
            inputBuffer.putFloat((p and 0xFF) / 255f)           // B
            // Note: the alpha byte is intentionally dropped — the model is RGB-only.
        }
        // Rewind again so the consumer (TFLite) reads from the beginning.
        inputBuffer.rewind()
        return inputBuffer
    }

    /**
     * Convert YUV_420_888 ImageProxy to ARGB Bitmap.
     * Reference implementation using android.graphics.YuvImage.
     *
     * The camera delivers luma (Y) and chroma (U/V) in separate planes. We repack
     * them into NV21 (the one interleaved format YuvImage accepts) and let the
     * platform JPEG codec do the YUV→RGB conversion. This trades a little CPU for
     * a simple, device-agnostic conversion that handles every plane layout.
     *
     * @param image the camera frame to convert.
     * @return a decoded ARGB_8888 bitmap of the same dimensions.
     */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        // Plane 0 = Y (luma), plane 1 = U (Cb), plane 2 = V (Cr).
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // NV21 = full-res Y plane followed by half-res interleaved V,U chroma.
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        // NV21 layout: Y plane, then interleaved VU
        // ImageProxy gives separate U and V planes; we need to interleave.
        // pixelStride tells us whether the chroma is already interleaved in memory.
        val pixelStride = image.planes[2].pixelStride
        if (pixelStride == 2) {
            // Already interleaved (semi-planar)
            // The V plane's bytes already alternate V,U so we can bulk-copy them.
            vBuffer.get(nv21, ySize, vSize)
        } else {
            // Planar — interleave manually
            // Truly separate planes: copy each out, then weave them as V,U,V,U...
            val uBytes = ByteArray(uSize)
            val vBytes = ByteArray(vSize)
            uBuffer.get(uBytes)
            vBuffer.get(vBytes)
            var pos = ySize
            for (i in 0 until minOf(uSize, vSize)) {
                nv21[pos++] = vBytes[i]
                // Guard the trailing write in case the buffer ends on a V byte.
                if (pos < nv21.size) nv21[pos++] = uBytes[i]
            }
        }

        // Wrap the NV21 bytes and compress to JPEG, which performs the colour-space
        // conversion, then decode that JPEG straight back into an ARGB bitmap.
        val yuv = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            image.width,
            image.height,
            null
        )
        val out = java.io.ByteArrayOutputStream()
        // Quality 90: near-lossless enough for detection while keeping the buffer small.
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val bytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /**
     * Rotate a bitmap clockwise by [degrees] using a transform matrix.
     *
     * @param bitmap the source image.
     * @param degrees rotation in degrees (0 returns the input unchanged).
     * @return an upright bitmap (or the original if no rotation is needed).
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        // Fast path: nothing to do, avoid allocating a second bitmap.
        if (degrees == 0) return bitmap
        // postRotate builds the rotation transform; createBitmap applies it.
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Map a bbox in INPUT (640x640 letterboxed) space back to source (camera
     * frame) coordinates. Used by overlay rendering and persistence tracking.
     *
     * This is the exact inverse of the forward letterbox transform: undo the
     * centering pad first, then undo the uniform scale. (left - pad) / scale.
     *
     * @param left,top,right,bottom box edges in model input pixels.
     * @return [left, top, right, bottom] in source-frame pixels.
     */
    fun mapBboxToSource(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
        // Subtract the padding that was added during letterboxing, then divide by
        // the scale to return to the original (rotated) source resolution.
        val srcLeft = (left - lastPadLeft) / lastScale
        val srcTop = (top - lastPadTop) / lastScale
        val srcRight = (right - lastPadLeft) / lastScale
        val srcBottom = (bottom - lastPadTop) / lastScale
        return floatArrayOf(srcLeft, srcTop, srcRight, srcBottom)
    }
}
