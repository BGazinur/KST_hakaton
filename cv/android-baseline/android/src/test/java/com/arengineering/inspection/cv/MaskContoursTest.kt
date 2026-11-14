package com.arengineering.inspection.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskContoursTest {
    @Test fun preservesConcavityAndHoles() {
        val rows = listOf("11110", "10110", "11110", "11000")
        val mask = BinaryMaskRle.encode(5, 4, rows.joinToString("").map { it == '1' }.toBooleanArray())
        val contours = mask.contours()
        assertEquals(2, contours.size)
        assertEquals(listOf(4, 6), contours.map { it.size }.sorted())
        // Filled area subtracts the hole and leaves the concave corner empty.
        val area = contours.sumOf { ring ->
            ring.indices.sumOf { i ->
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                (a.x * b.y - b.x * a.y) / 2
            }
        }
        assertEquals(13.0 / 20, area, 1e-9)
    }

    @Test fun separatesDiagonalIslandsAndHandlesEmptyMasks() {
        val mask = BinaryMaskRle.encode(2, 2, booleanArrayOf(true, false, false, true))
        assertEquals(listOf(4, 4), mask.contours().map { it.size })
        assertTrue(BinaryMaskRle.encode(3, 2, BooleanArray(6)).contours().isEmpty())
    }
}
