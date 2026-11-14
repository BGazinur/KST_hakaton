package com.arengineering.inspection

import android.graphics.Bitmap
import android.graphics.Matrix
import com.arengineering.inspection.cv.CvFrame
import com.arengineering.inspection.cv.CvResult
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class ReviewPhoto(val jpeg: ByteArray, val candidate: Boolean)

/** Encode only the requested ARCore frame on the CV worker. */
internal fun encodeReviewPhoto(frame: CvFrame, result: CvResult): ReviewPhoto {
    val crop = frame.validCrop
    val candidate = result.defects.maxByOrNull { it.confidence }?.bbox ?: result.visualHints.firstOrNull()?.bbox
    val w = frame.geometry.stamp.imageWidth
    val h = frame.geometry.stamp.imageHeight
    val left = candidate?.let { floor((it.left - maxOf(it.width * .6, .13)) * w).toInt() }?.coerceAtLeast(crop.left) ?: crop.left
    val top = candidate?.let { floor((it.top - maxOf(it.height * .6, .13)) * h).toInt() }?.coerceAtLeast(crop.top) ?: crop.top
    val right = candidate?.let { ceil((it.right + maxOf(it.width * .6, .13)) * w).toInt() }?.coerceAtMost(crop.right) ?: crop.right
    val bottom = candidate?.let { ceil((it.bottom + maxOf(it.height * .6, .13)) * h).toInt() }?.coerceAtMost(crop.bottom) ?: crop.bottom
    val width = right - left
    val height = bottom - top
    val scale = minOf(1.0, 768.0 / maxOf(width, height))
    val outWidth = maxOf(1, (width * scale).roundToInt())
    val outHeight = maxOf(1, (height * scale).roundToInt())
    val rgb = IntArray(outWidth * outHeight) { index ->
        val x = left + index % outWidth * width / outWidth
        val y = top + index / outWidth * height / outHeight
        val offset = (y * w + x) * 3
        android.graphics.Color.rgb(frame.rgb[offset].toInt() and 255, frame.rgb[offset + 1].toInt() and 255,
            frame.rgb[offset + 2].toInt() and 255)
    }
    val bitmap = Bitmap.createBitmap(rgb, outWidth, outHeight, Bitmap.Config.ARGB_8888)
    try {
        val upright = Bitmap.createBitmap(bitmap, 0, 0, outWidth, outHeight,
            Matrix().apply { postRotate(frame.geometry.rotationCw.toFloat()) }, false)
        try {
            val bytes = ByteArrayOutputStream()
            check(upright.compress(Bitmap.CompressFormat.JPEG, 82, bytes))
            return ReviewPhoto(bytes.toByteArray(), candidate != null)
        } finally {
            if (upright !== bitmap) upright.recycle()
        }
    } finally {
        bitmap.recycle()
    }
}
