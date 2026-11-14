package com.arengineering.inspection.cv

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceMotionTest {
    @Test fun identityAndQuaternionSignGiveZeroRotation() {
        val positive = ReferenceMotion.fromDelta(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
        val negative = ReferenceMotion.fromDelta(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, -1f))
        assertEquals(positive, negative)
        assertEquals(0.0, positive.rotationDegrees, 1e-10)
        assertTrue(positive.withinApproximateLimit)
    }

    @Test fun translationBeyondOneCentimeterIsRejected() {
        val motion = ReferenceMotion.fromDelta(floatArrayOf(0.006f, 0.008f, 0.003f), floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(0.0104403, motion.translationMeters, 1e-6)
        assertFalse(motion.withinApproximateLimit)
    }

    @Test fun rotationBeyondOneDegreeIsRejected() {
        val half = Math.toRadians(1.2) / 2
        val motion = ReferenceMotion.fromDelta(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, sin(half).toFloat(), 0f, cos(half).toFloat()))
        assertEquals(1.2, motion.rotationDegrees, 1e-4)
        assertFalse(motion.withinApproximateLimit)
    }

    @Test fun invalidRotationCannotAuthorizeAnOverlay() {
        assertTrue(runCatching {
            ReferenceMotion.fromDelta(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 0f))
        }.isFailure)
        assertTrue(runCatching {
            ReferenceMotion.fromDelta(floatArrayOf(Float.NaN, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))
        }.isFailure)
    }
}
