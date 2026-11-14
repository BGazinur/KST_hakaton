package com.arengineering.inspection.cv

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Normalized scan window in the UPRIGHT CPU image, not display/view coordinates. */
data class CodeScanRegion(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init { require(left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right > left && bottom > top) }
}

/**
 * Construct/scan/close on one background worker. Uses the existing CvFrame RAM copy.
 * No second camera, file writes, networking, photo capture or retained source frames.
 * Keep calling only while identifying; the host owns backpressure and DB lookup.
 */
class EquipmentCodeScanner(rules: CodeRules = CodeRules()) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val parser = EquipmentCodeParser(rules)
    private val qr = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    private val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var closed = false

    fun scan(frame: CvFrame, region: CodeScanRegion): CodeObservation {
        val stamp = frame.geometry.stamp
        val id = CodeFrame(stamp.sessionId, stamp.inspectionEpoch, stamp.frameTimestampNs)
        return scanRgb(frame.rgb, stamp.imageWidth, stamp.imageHeight, frame.geometry.rotationCw, frame.validCrop, id, region)
    }
    /** Identification does not require a tracked AR pose; the AR adapter uses this overload. */
    fun scanRgb(rgb: ByteArray, width: Int, height: Int, rotation: Int, validCrop: RawCrop, id: CodeFrame, region: CodeScanRegion): CodeObservation {
        check(Thread.currentThread() === owner && !closed)
        require(width > 0 && height > 0 && rgb.size.toLong() == width.toLong()*height*3)
        require(rotation in listOf(0,90,180,270))
        require(validCrop.right <= width && validCrop.bottom <= height)
        val pixels = IntArray(width * height) { i ->
            val o = i * 3
            (0xff shl 24) or ((rgb[o].toInt() and 255) shl 16) or
                ((rgb[o + 1].toInt() and 255) shl 8) or (rgb[o + 2].toInt() and 255)
        }
        val raw = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val upright = if (rotation == 0) raw else Bitmap.createBitmap(raw, 0, 0, width, height, Matrix().apply { postRotate(rotation.toFloat()) }, false)
        val c = validCrop
        val valid = when (rotation) {
            0 -> intArrayOf(c.left, c.top, c.right, c.bottom)
            90 -> intArrayOf(height-c.bottom, c.left, height-c.top, c.right)
            180 -> intArrayOf(width-c.right, height-c.bottom, width-c.left, height-c.top)
            270 -> intArrayOf(c.top, width-c.right, c.bottom, width-c.left)
            else -> error("Unsupported captured rotation")
        }
        val l = maxOf((region.left * upright.width).toInt(), valid[0])
        val t = maxOf((region.top * upright.height).toInt(), valid[1])
        val r = minOf((region.right * upright.width).toInt(), valid[2])
        val b = minOf((region.bottom * upright.height).toInt(), valid[3])
        if (r <= l || b <= t) return CodeObservation(id, CodeState.SEARCHING)
        val roi = Bitmap.createBitmap(upright, l, t, r - l, b - t)
        val input = InputImage.fromBitmap(roi, 0)
        // Do not recycle bitmaps early: an interrupted await can leave an ML Kit task running.
        // Objects are scoped to this call/task and become GC-eligible after processing.
        return try {
            val codes = Tasks.await(qr.process(input))
            if (codes.isNotEmpty()) parser.parse(id, codes.mapNotNull { it.rawValue }, true, emptyList())
            else {
                val recognized = Tasks.await(text.process(input))
                parser.parse(id, emptyList(), false, recognized.textBlocks.flatMap { it.lines }.map { it.text })
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CodeObservation(id, CodeState.FAILED)
        } catch (e: java.util.concurrent.ExecutionException) {
            CodeObservation(id, CodeState.FAILED)
        } catch (e: java.util.concurrent.CancellationException) {
            CodeObservation(id, CodeState.FAILED)
        }
    }
    override fun close() {
        check(Thread.currentThread() === owner)
        if (!closed) { closed = true; qr.close(); text.close() }
    }
}
