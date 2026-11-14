package com.arengineering.inspection.cv

import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

/**
 * Copies valid YUV samples while the Image is open. Conversion runs on the CV worker.
 * No Image, Plane or camera-owned ByteBuffer survives [copy].
 */
internal object Yuv420Copier {
    fun copy(image: Image): OwnedYuv420 {
        require(image.format == ImageFormat.YUV_420_888) { "Expected YUV_420_888" }
        require(image.width > 0 && image.height > 0)
        val rect = image.cropRect
        require(rect.left >= 0 && rect.top >= 0 && rect.right <= image.width && rect.bottom <= image.height)
        require(rect.width() > 0 && rect.height() > 0)
        val crop = RawCrop(rect.left, rect.top, rect.right, rect.bottom)
        val planes = image.planes
        require(planes.size == 3) { "YUV_420_888 must have Y, U and V planes" }
        val chromaLeft = crop.left / 2
        val chromaTop = crop.top / 2
        // Odd crop origins may touch one more chroma sample than ceil(crop.width / 2).
        val chromaWidth = (crop.right + 1) / 2 - chromaLeft
        val chromaHeight = (crop.bottom + 1) / 2 - chromaTop
        fun plane(index: Int, left: Int, top: Int, width: Int, height: Int): ByteArray {
            val p = planes[index]
            return copyPlane(p.buffer, p.rowStride, p.pixelStride, left, top, width, height)
        }
        return OwnedYuv420(
            image.width, image.height, crop, chromaLeft, chromaTop, chromaWidth,
            plane(0, crop.left, crop.top, rect.width(), rect.height()),
            plane(1, chromaLeft, chromaTop, chromaWidth, chromaHeight),
            plane(2, chromaLeft, chromaTop, chromaWidth, chromaHeight),
        )
    }

    /** Absolute reads preserve the producer's buffer position, including sliced/interleaved planes. */
    internal fun copyPlane(
        source: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        require(rowStride > 0 && pixelStride > 0 && left >= 0 && top >= 0 && width > 0 && height > 0)
        val bytes = source.duplicate()
        val base = bytes.position()
        val last = base.toLong() + (top + height - 1L) * rowStride + (left + width - 1L) * pixelStride
        require(last < bytes.limit()) { "Image plane ends before the last valid sample" }
        val out = ByteArray(Math.multiplyExact(width, height))
        for (row in 0 until height) {
            val start = base + (top + row) * rowStride + left * pixelStride
            if (pixelStride == 1) {
                bytes.position(start)
                bytes.get(out, row * width, width)
            } else {
                for (column in 0 until width) {
                    out[row * width + column] = bytes.get(start + column * pixelStride)
                }
            }
        }
        return out
    }
}

/**
 * Owned compact planes plus the original raw image dimensions. Outside validCrop the output
 * is neutral 114, so all model coordinates continue to use the full CPU image/intrinsics.
 */
internal class OwnedYuv420(
    val width: Int,
    val height: Int,
    val validCrop: RawCrop,
    private val chromaLeft: Int,
    private val chromaTop: Int,
    private val chromaWidth: Int,
    private val y: ByteArray,
    private val u: ByteArray,
    private val v: ByteArray,
) {
    /** BT.601 limited-range conversion. Validate camera colour/range on the target device. */
    fun toRgb(): ByteArray {
        val rgb = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), 3)) { 114 }
        val cropWidth = validCrop.right - validCrop.left
        for (row in validCrop.top until validCrop.bottom) {
            for (column in validCrop.left until validCrop.right) {
                val yi = (row - validCrop.top) * cropWidth + column - validCrop.left
                val ci = (row / 2 - chromaTop) * chromaWidth + column / 2 - chromaLeft
                val luminance = ((y[yi].toInt() and 255) - 16).coerceAtLeast(0)
                val blueDifference = (u[ci].toInt() and 255) - 128
                val redDifference = (v[ci].toInt() and 255) - 128
                val index = (row * width + column) * 3
                rgb[index] = ((298 * luminance + 409 * redDifference + 128) shr 8).coerceIn(0, 255).toByte()
                rgb[index + 1] = ((298 * luminance - 100 * blueDifference - 208 * redDifference + 128) shr 8).coerceIn(0, 255).toByte()
                rgb[index + 2] = ((298 * luminance + 516 * blueDifference + 128) shr 8).coerceIn(0, 255).toByte()
            }
        }
        return rgb
    }
}
