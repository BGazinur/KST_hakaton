package com.arengineering.inspection.cv

import kotlin.math.floor
import kotlin.math.min

/** Pure RGB preprocessing. No Bitmap, camera object, native image, or Android dependency. */
object ImagePreprocessor {
    const val INPUT_SIZE = 416
    const val PADDING = 114

    data class PreparedImage(val chw: FloatArray, val transform: LetterboxTransform)

    /**
     * Rotate clockwise to upright, then center-letterbox and return float32 RGB CHW / 255.
     * Input is borrowed only for this synchronous call and never retained or modified.
     * Resizing uses half-pixel bilinear sampling, edge replication, and nearest uint8 rounding.
     * The returned transform records the exact rounded resize so that the decoder can
     * map points and each binary mask back to the original CPU image without a second remap.
     */
    fun prepare(rgb: ByteArray, width: Int, height: Int, rotationCw: Int): PreparedImage {
        require(width > 0 && height > 0 && width.toLong() * height * 3 == rgb.size.toLong()) {
            "Expected tightly packed RGB8: width * height * 3 bytes"
        }
        require(rotationCw in setOf(0, 90, 180, 270)) { "rotationCw must be 0, 90, 180, or 270" }
        val transform = LetterboxTransform.create(width, height, rotationCw, INPUT_SIZE)
        val uprightWidth = transform.uprightWidth
        val uprightHeight = transform.uprightHeight
        val resizedWidth = transform.resizedWidth
        val resizedHeight = transform.resizedHeight
        val padLeft = transform.padLeft
        val padTop = transform.padTop
        val plane = INPUT_SIZE * INPUT_SIZE
        val chw = FloatArray(3 * plane) { PADDING / 255f }
        for (dy in 0 until resizedHeight) {
            val sy = ((dy + 0.5) * uprightHeight / resizedHeight - 0.5).coerceIn(0.0, uprightHeight - 1.0)
            val y0 = floor(sy).toInt()
            val y1 = min(y0 + 1, uprightHeight - 1)
            val fy = sy - y0
            for (dx in 0 until resizedWidth) {
                val sx = ((dx + 0.5) * uprightWidth / resizedWidth - 0.5).coerceIn(0.0, uprightWidth - 1.0)
                val x0 = floor(sx).toInt()
                val x1 = min(x0 + 1, uprightWidth - 1)
                val fx = sx - x0
                val i00 = rawIndex(x0, y0, width, height, rotationCw)
                val i10 = rawIndex(x1, y0, width, height, rotationCw)
                val i01 = rawIndex(x0, y1, width, height, rotationCw)
                val i11 = rawIndex(x1, y1, width, height, rotationCw)
                val dst = (dy + padTop) * INPUT_SIZE + dx + padLeft
                for (channel in 0..2) {
                    val top = (rgb[i00 + channel].toInt() and 255) * (1.0 - fx) +
                        (rgb[i10 + channel].toInt() and 255) * fx
                    val bottom = (rgb[i01 + channel].toInt() and 255) * (1.0 - fx) +
                        (rgb[i11 + channel].toInt() and 255) * fx
                    val value = floor(top * (1.0 - fy) + bottom * fy + 0.5).toInt().coerceIn(0, 255)
                    chw[channel * plane + dst] = value / 255f
                }
            }
        }
        return PreparedImage(chw, transform)
    }

    private fun rawIndex(x: Int, y: Int, width: Int, height: Int, rotation: Int): Int {
        val rawX: Int
        val rawY: Int
        when (rotation) {
            0 -> { rawX = x; rawY = y }
            90 -> { rawX = y; rawY = height - 1 - x }
            180 -> { rawX = width - 1 - x; rawY = height - 1 - y }
            270 -> { rawX = width - 1 - y; rawY = x }
            else -> error("Invalid rotation")
        }
        return 3 * (rawY * width + rawX)
    }
}
