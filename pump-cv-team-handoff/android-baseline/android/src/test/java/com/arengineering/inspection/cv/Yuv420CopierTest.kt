package com.arengineering.inspection.cv

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Yuv420CopierTest {
    @Test fun paddedRowsPreserveTheOriginalBufferPosition() {
        val source = ByteBuffer.wrap(byteArrayOf(99, 99, 10, 11, 12, 88, 20, 21, 22))
        source.position(2)
        assertArrayEquals(byteArrayOf(11, 12, 21, 22), Yuv420Copier.copyPlane(source, 4, 1, 1, 0, 2, 2))
        assertEquals(2, source.position())
    }

    @Test fun interleavedChromaUsesPixelStrideAndAcceptsMissingFinalPadding() {
        val source = ByteBuffer.wrap(byteArrayOf(90, 1, 91, 2, 92, 3, 0, 0, 93, 4, 94, 5, 95))
        assertArrayEquals(byteArrayOf(91, 92, 94, 95), Yuv420Copier.copyPlane(source, 8, 2, 1, 0, 2, 2))
        assertTrue(runCatching { Yuv420Copier.copyPlane(source, 8, 2, 0, 0, 4, 2) }.isFailure)
        assertTrue(runCatching { Yuv420Copier.copyPlane(source, 0, 2, 0, 0, 1, 1) }.isFailure)
    }

    @Test fun oddCropUsesFullImageChromaCellsAndNeutralOutsidePixels() {
        val copied = OwnedYuv420(
            5, 5, RawCrop(1, 1, 4, 4), 0, 0, 2,
            ByteArray(9) { 128.toByte() },
            byteArrayOf(0, 64, 128.toByte(), 255.toByte()),
            ByteArray(4) { 128.toByte() },
        )
        val rgb = copied.toRgb()
        fun sample(x: Int, y: Int, c: Int) = rgb[(y * 5 + x) * 3 + c].toInt() and 255
        assertEquals(75, rgb.size)
        assertEquals(114, sample(0, 0, 0))
        assertEquals(114, sample(4, 4, 2))
        assertEquals(0, sample(1, 1, 2))
        assertEquals(1, sample(2, 1, 2))
        assertEquals(130, sample(1, 2, 2))
        assertEquals(255, sample(2, 2, 2))
    }

    @Test fun limitedRangeBlackAndWhiteProduceRgbExtremes() {
        val rgb = OwnedYuv420(
            2, 2, RawCrop(0, 0, 2, 2), 0, 0, 1,
            byteArrayOf(16, 235.toByte(), 16, 235.toByte()),
            byteArrayOf(128.toByte()), byteArrayOf(128.toByte()),
        ).toRgb()
        assertTrue(rgb.sliceArray(0..2).all { it.toInt() == 0 })
        assertTrue(rgb.sliceArray(3..5).all { (it.toInt() and 255) == 255 })
    }
}
