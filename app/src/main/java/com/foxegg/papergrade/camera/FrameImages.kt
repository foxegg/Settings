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
        if (pixelStride == 4 && rowStride >= width * 4) {
            val packed = packPlane(buffer, width, height, pixelStride, rowStride)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(packed)
            return bitmap
        }
        return slowRgba(buffer, width, height, pixelStride, rowStride)
    }

    /**
     * CameraX 的平面缓冲长度是 `rowStride * (height - 1) + pixelStride * width`，
     * 最后一行没有行尾填充。按整幅 `rowStride * height` 去拷贝会在有填充的机型上抛异常。
     */
    internal fun packPlane(
        source: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
    ): ByteBuffer {
        val rowBytes = width * pixelStride
        val packed = ByteBuffer.allocate(rowBytes * height)
        val row = ByteArray(rowBytes)
        val limit = source.limit()
        for (y in 0 until height) {
            val start = y * rowStride
            val available = limit - start
            if (available <= 0) {
                row.fill(0)
            } else if (available < rowBytes) {
                row.fill(0)
                source.position(start)
                source.get(row, 0, available)
            } else {
                source.position(start)
                source.get(row, 0, rowBytes)
            }
            packed.put(row)
        }
        packed.rewind()
        return packed
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
