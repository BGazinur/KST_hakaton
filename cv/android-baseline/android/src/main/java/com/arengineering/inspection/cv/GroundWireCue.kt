package com.arengineering.inspection.cv

import kotlin.math.abs
import kotlin.math.roundToInt

/** Localizes adjacent green and yellow markings. It says nothing about circuit continuity. */
internal fun locateGroundWireCue(frame: CvFrame): VisualHintRegion? {
    val crop = frame.validCrop
    val rawWidth = frame.geometry.stamp.imageWidth
    val rawHeight = frame.geometry.stamp.imageHeight
    val cropWidth = crop.right - crop.left
    val cropHeight = crop.bottom - crop.top
    val scale = 160.0 / maxOf(cropWidth, cropHeight)
    val width = maxOf(1, (cropWidth * scale).roundToInt())
    val height = maxOf(1, (cropHeight * scale).roundToInt())
    val colors = ByteArray(width * height)
    for (y in 0 until height) for (x in 0 until width) {
        val rawX = crop.left + ((x + 0.5) * cropWidth / width).toInt()
        val rawY = crop.top + ((y + 0.5) * cropHeight / height).toInt()
        val offset = (rawY * rawWidth + rawX) * 3
        val red = frame.rgb[offset].toInt() and 255
        val green = frame.rgb[offset + 1].toInt() and 255
        val blue = frame.rgb[offset + 2].toInt() and 255
        val max = maxOf(red, green, blue)
        val chroma = max - minOf(red, green, blue)
        if (max < 50 || chroma == 0) continue
        val hue = when (max) {
            red -> 60.0 * (green - blue) / chroma
            green -> 120.0 + 60.0 * (blue - red) / chroma
            else -> 240.0 + 60.0 * (red - green) / chroma
        }
        colors[y * width + x] = when {
            hue in 34.0..74.0 && max >= 75 && chroma * 255 >= max * 100 -> 1
            hue in 74.0..164.0 && chroma * 255 >= max * 85 -> 2
            else -> 0
        }
    }
    val green = BooleanArray(colors.size)
    val yellow = BooleanArray(colors.size)
    for (y in 0 until height) for (x in 0 until width) {
        val color = colors[y * width + x]
        if (color == 0.toByte()) continue
        val region = if (color == 1.toByte()) yellow else green
        for (dy in -5..5) for (dx in -5..5) {
            if (dx * dx + dy * dy > 25) continue
            val xx = x + dx
            val yy = y + dy
            if (xx in 0 until width && yy in 0 until height) region[yy * width + xx] = true
        }
    }
    val visited = BooleanArray(colors.size)
    val queue = IntArray(colors.size)
    var bestScore = 0
    var bestBox: Box2? = null
    for (seed in colors.indices) {
        if (!green[seed] || !yellow[seed] || visited[seed]) continue
        visited[seed] = true
        queue[0] = seed
        var count = 1
        var next = 0
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        var yellowPixels = 0
        var greenPixels = 0
        while (next < count) {
            val index = queue[next++]
            val x = index % width
            val y = index / width
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
            if (colors[index] == 1.toByte()) yellowPixels++
            if (colors[index] == 2.toByte()) greenPixels++
            for (yy in maxOf(0, y - 1)..minOf(y + 1, height - 1)) {
                for (xx in maxOf(0, x - 1)..minOf(x + 1, width - 1)) {
                    val neighbor = yy * width + xx
                    if (!visited[neighbor] && green[neighbor] && yellow[neighbor]) {
                        visited[neighbor] = true
                        queue[count++] = neighbor
                    }
                }
            }
        }
        val score = minOf(yellowPixels, greenPixels)
        if (score < 18 || score <= bestScore || abs(yellowPixels - greenPixels) > score * 2 ||
            left < 2 || top < 2 || right > width - 2 || bottom > height - 2
        ) continue
        bestScore = score
        bestBox = Box2(
            (crop.left + left.toDouble() * cropWidth / width) / rawWidth,
            (crop.top + top.toDouble() * cropHeight / height) / rawHeight,
            (crop.left + right.toDouble() * cropWidth / width) / rawWidth,
            (crop.top + bottom.toDouble() * cropHeight / height) / rawHeight,
        )
    }
    return bestBox?.let { VisualHintRegion(VisualHint.GROUND, it) }
}
