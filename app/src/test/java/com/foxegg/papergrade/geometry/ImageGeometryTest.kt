package com.foxegg.papergrade.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageGeometryTest {
    @Test
    fun rotatesFullFrame() {
        assertEquals(IntRect(0, 0, 2, 4), ImageGeometry.rotateRectClockwise(0, 0, 4, 2, 4, 2, 90))
        assertEquals(IntRect(0, 0, 4, 2), ImageGeometry.rotateRectClockwise(0, 0, 4, 2, 4, 2, 180))
        assertEquals(IntRect(0, 0, 2, 4), ImageGeometry.rotateRectClockwise(0, 0, 4, 2, 4, 2, 270))
    }

    @Test
    fun rotatesPartialRect() {
        val rotated = ImageGeometry.rotateRectClockwise(1, 0, 3, 1, 4, 2, 90)
        assertEquals(1, rotated.left)
        assertEquals(1, rotated.top)
        assertEquals(2, rotated.right)
        assertEquals(3, rotated.bottom)
    }

    @Test
    fun swapsSizeOnSidewaysRotation() {
        assertEquals(2 to 4, ImageGeometry.rotatedSize(4, 2, 90))
        assertEquals(4 to 2, ImageGeometry.rotatedSize(4, 2, 180))
    }
}
