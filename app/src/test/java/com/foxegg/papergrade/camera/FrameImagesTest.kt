package com.foxegg.papergrade.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameImagesTest {
    @Test
    fun packsPaddedRowsUsingTheShortCameraBuffer() {
        val width = 2
        val height = 2
        val pixelStride = 4
        val rowStride = 12
        val raw = ByteArray(rowStride * (height - 1) + pixelStride * width)
        for (i in raw.indices) raw[i] = (i + 1).toByte()

        val packed = FrameImages.packPlane(ByteBuffer.wrap(raw), width, height, pixelStride, rowStride)
        val out = ByteArray(packed.remaining())
        packed.get(out)

        assertEquals(width * height * pixelStride, out.size)
        assertEquals(1, out[0].toInt())
        assertEquals(8, out[7].toInt())
        assertEquals(13, out[8].toInt())
        assertEquals(20, out[15].toInt() and 0xFF)
    }

    @Test
    fun packsTightRowsUnchanged() {
        val width = 2
        val height = 2
        val pixelStride = 4
        val rowStride = width * pixelStride
        val raw = ByteArray(rowStride * height) { index -> (index + 1).toByte() }

        val packed = FrameImages.packPlane(ByteBuffer.wrap(raw), width, height, pixelStride, rowStride)
        val out = ByteArray(packed.remaining())
        packed.get(out)

        assertEquals(raw.toList(), out.toList())
    }
}
