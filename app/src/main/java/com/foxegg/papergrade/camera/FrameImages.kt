package com.foxegg.papergrade.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.foxegg.papergrade.geometry.ImageGeometry
import com.foxegg.papergrade.geometry.IntRect
import java.nio.ByteBuffer
import kotlin.math.min

object FrameImages {
    fun toUprightCropped(image: ImageProxy): Bitmap {
        val raw = imageToBitmap(image)
        val upright = rotateBitmap(raw, image.imageInfo.rotationDegrees)
        val crop = image.cropRect
        val rotated = ImageGeometry.rotateRectClockwise(
            crop.left,
            crop.top,
            crop.right,
            crop.bottom,
            image.width,
            image.height,
            image.imageInfo.rotationDegrees,
        )
        return cropBitmap(upright, rotated)
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes.firstOrNull() ?: error("相机帧没有图像平面")
        val buffer = plane.buffer
        buffer.rewind()
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride == 4 && rowStride >= width * 4 && rowStride % 4 == 0) {
            val paddedWidth = rowStride / 4
            val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            if (paddedWidth == width) return bitmap
            val tight = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                .copy(Bitmap.Config.ARGB_8888, false)
            bitmap.recycle()
            return tight
        }
        return slowRgba(buffer, width, height, pixelStride, rowStride)
    }

    private fun slowRgba(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
    ): Bitmap {
        val pixels = IntArray(width * height)
        val row = ByteArray(rowStride.coerceAtLeast(1))
        for (y in 0 until height) {
            val start = y * rowStride
            if (start >= buffer.limit()) break
            buffer.position(start)
            val count = min(rowStride, buffer.remaining())
            buffer.get(row, 0, count)
            for (x in 0 until width) {
                val i = x * pixelStride
                if (i + 2 >= count) break
                val r = row[i].toInt() and 0xFF
                val g = row[i + 1].toInt() and 0xFF
                val b = row[i + 2].toInt() and 0xFF
                val a = if (pixelStride >= 4 && i + 3 < count) row[i + 3].toInt() and 0xFF else 255
                pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return source
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated != source) source.recycle()
        return rotated
    }

    private fun cropBitmap(source: Bitmap, rect: IntRect): Bitmap {
        if (source.width <= 0 || source.height <= 0) return source
        val left = rect.left.coerceIn(0, source.width - 1)
        val top = rect.top.coerceIn(0, source.height - 1)
        val right = rect.right.coerceIn(left + 1, source.width)
        val bottom = rect.bottom.coerceIn(top + 1, source.height)
        val width = right - left
        val height = bottom - top
        if (left == 0 && top == 0 && width == source.width && height == source.height) return source
        val subset = Bitmap.createBitmap(source, left, top, width, height)
        val copy = if (subset == source) source else subset.copy(Bitmap.Config.ARGB_8888, false)
        if (source != copy) source.recycle()
        if (subset !== source && subset !== copy) subset.recycle()
        return copy
    }
}
