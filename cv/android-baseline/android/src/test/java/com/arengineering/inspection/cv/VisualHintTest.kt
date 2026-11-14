package com.arengineering.inspection.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualHintTest {
    @Test fun groundWireCueRequiresBothColorsInsideTheValidImage() {
        val size = 160
        val stamp = FrameStamp("test", 0, 0, 1, 1, size, size)
        val geometry = GeometrySnapshot(stamp, CameraIntrinsics(160.0, 160.0, 80.0, 80.0),
            CameraPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0), Affine2x3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0))
        val rgb = ByteArray(size * size * 3) { 90 }
        for (y in 62..66) for (x in 44..86) {
            val offset = (y * size + x) * 3
            rgb[offset] = (if (y <= 64) 40 else 210).toByte()
            rgb[offset + 1] = (if (y <= 64) 164 else 180).toByte()
            rgb[offset + 2] = 35
        }
        val result = checkNotNull(locateGroundWireCue(CvFrame(geometry, rgb)))
        assertTrue(result.bbox.contains(Point2(65.0 / 160, 64.0 / 160)))
        assertNull(locateGroundWireCue(CvFrame(geometry, rgb, RawCrop(0, 0, 40, size))))
        assertNull(locateGroundWireCue(CvFrame(geometry, ByteArray(rgb.size) { 90 })))
    }

    @Test fun pilotUsesOnlyTheCurrentInspectionClassAndProjectsItsBox() {
        val detections = FloatArray(300 * 6)
        floatArrayOf(20f, 21f, 220f, 221f, 0.9f, 4f).copyInto(detections)
        floatArrayOf(40f, 50f, 240f, 250f, 0.6f, 0f).copyInto(detections, 6)
        floatArrayOf(1f, 1f, 210f, 250f, 0.2f, 0f).copyInto(detections, 12)
        val transform = LetterboxTransform.create(416, 416, 90)
        val result = checkNotNull(decodePilotHint(detections, transform, VisualHint.LEAK))
        assertEquals(VisualHint.LEAK, result.target)
        assertEquals(50.0 / 416.0, result.bbox.left, 0.0)
        assertEquals(1.0 - 240.0 / 416.0, result.bbox.top, 0.0)
        assertNull(result.mask)
        assertNull(decodePilotHint(detections, transform, VisualHint.FINS))
    }

    @Test fun onlyRequestedClassWithUsableMaskBecomesHint() {
        val detections = FloatArray(300 * 38)
        floatArrayOf(40f, 50f, 360f, 370f, 0.9f, 5f, 1f).copyInto(detections)
        floatArrayOf(96f, 104f, 320f, 312f, 0.7f, 2f, 1f).copyInto(detections, 38)
        val prototypes = FloatArray(32 * 104 * 104)
        prototypes.fill(1f, toIndex = 104 * 104)
        val transform = LetterboxTransform.create(416, 416, 0)
        val decoder = YoloDecoder()
        val result = checkNotNull(decoder.decodeVisualHint(detections, prototypes, transform, VisualHint.GUARD))
        assertEquals(VisualHint.GUARD, result.target)
        assertEquals(96.0 / 416.0, result.bbox.left, 0.0)
        assertTrue(checkNotNull(result.mask).contours().isNotEmpty())
        assertNull(decoder.decodeVisualHint(detections, prototypes, transform, VisualHint.GROUND))
        assertNull(decoder.decodeVisualHint(detections, FloatArray(prototypes.size), transform, VisualHint.GUARD))
    }
}
