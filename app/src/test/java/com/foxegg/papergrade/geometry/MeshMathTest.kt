package com.foxegg.papergrade.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshMathTest {
    @Test
    fun bilinearCenterOfRectangle() {
        val mesh = rectangle()
        val center = mesh.map(0.5f, 0.5f)
        assertEquals(50f, center.x, 0.01f)
        assertEquals(100f, center.y, 0.01f)
        assertEquals(0f, mesh.map(0f, 0f).x, 0.01f)
        assertEquals(100f, mesh.map(1f, 0f).x, 0.01f)
        assertEquals(200f, mesh.map(1f, 1f).y, 0.01f)
    }

    @Test
    fun cellAreaSign() {
        assertTrue(cellSignedArea(rectangle(), 0, 0) > 0f)
        val flipped = PaperMesh(
            1,
            1,
            listOf(
                Vec2(0f, 0f),
                Vec2(0f, 10f),
                Vec2(10f, 10f),
                Vec2(10f, 0f),
            ),
        )
        assertTrue(hasFlippedCell(flipped))
        assertFalse(hasFlippedCell(rectangle()))
    }

    @Test
    fun preservePlanarOffsetWhenCornerMoves() {
        val mesh = rectangle()
        val moved = preserveCornerOffsets(
            mesh,
            listOf(Vec2(0f, 0f), Vec2(120f, 4f), Vec2(100f, 200f), Vec2(0f, 200f)),
        )
        assertEquals(120f, moved.map(1f, 0f).x, 0.01f)
        assertEquals(4f, moved.map(1f, 0f).y, 0.01f)
        assertEquals(0f, moved.map(0f, 1f).x, 0.01f)
    }

    @Test
    fun ordersCornersFromAnyStart() {
        val ordered = orderQuadCorners(
            listOf(Vec2(100f, 200f), Vec2(0f, 0f), Vec2(0f, 200f), Vec2(100f, 0f)),
        )
        assertNotNull(ordered)
        assertEquals(0f, ordered!![0].x, 0.01f)
        assertEquals(0f, ordered[0].y, 0.01f)
        assertEquals(100f, ordered[1].x, 0.01f)
        assertEquals(0f, ordered[1].y, 0.01f)
        assertEquals(100f, ordered[2].x, 0.01f)
        assertEquals(200f, ordered[2].y, 0.01f)
        assertEquals(0f, ordered[3].x, 0.01f)
    }

    @Test
    fun pointInQuad() {
        val quad = rectangle().corners()
        assertTrue(pointInConvexQuad(Vec2(50f, 100f), quad))
        assertFalse(pointInConvexQuad(Vec2(-5f, 10f), quad))
    }

    @Test
    fun rectifiedLongEdge() {
        val quad = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 200f), Vec2(0f, 200f))
        val (width, height) = rectifiedSize(quad, longEdge = 1000)
        assertEquals(500, width)
        assertEquals(1000, height)
    }

    @Test
    fun flatnessThreshold() {
        assertFalse(flatteningNeeded(0.04f, 0.11f))
        assertTrue(flatteningNeeded(0.05f, 0.01f))
        assertTrue(flatteningNeeded(0.01f, 0.12f))
    }

    private fun rectangle(): PaperMesh {
        return PaperMesh(
            1,
            1,
            listOf(
                Vec2(0f, 0f),
                Vec2(100f, 0f),
                Vec2(0f, 200f),
                Vec2(100f, 200f),
            ),
        )
    }
}
