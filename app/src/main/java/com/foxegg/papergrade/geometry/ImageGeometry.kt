package com.foxegg.papergrade.geometry

import kotlin.math.floor

data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 把相机原始缓冲里的点旋转到和预览一致的正向坐标。
 * 角度按顺时针，和 CameraX ImageInfo.rotationDegrees 一致。
 */
object ImageGeometry {
    fun rotateClockwise(x: Float, y: Float, width: Int, height: Int, degrees: Int): Vec2 {
        val normalized = ((degrees % 360) + 360) % 360
        return when (normalized) {
            0 -> Vec2(x, y)
            90 -> Vec2(height - 1f - y, x)
            180 -> Vec2(width - 1f - x, height - 1f - y)
            270 -> Vec2(y, width - 1f - x)
            else -> Vec2(x, y)
        }
    }

    fun rotatedSize(width: Int, height: Int, degrees: Int): Pair<Int, Int> {
        val normalized = ((degrees % 360) + 360) % 360
        return if (normalized == 90 || normalized == 270) height to width else width to height
    }

    /** Android Rect 是左上闭、右下开。旋转时按包含的像素角点算新的开区间。 */
    fun rotateRectClockwise(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        width: Int,
        height: Int,
        degrees: Int,
    ): IntRect {
        if (right <= left || bottom <= top || width <= 0 || height <= 0) {
            return IntRect(0, 0, 0, 0)
        }
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        val xs = floatArrayOf(left.toFloat(), (right - 1).toFloat())
        val ys = floatArrayOf(top.toFloat(), (bottom - 1).toFloat())
        for (x in xs) {
            for (y in ys) {
                val point = rotateClockwise(x, y, width, height, degrees)
                minX = minOf(minX, point.x)
                minY = minOf(minY, point.y)
                maxX = maxOf(maxX, point.x)
                maxY = maxOf(maxY, point.y)
            }
        }
        return IntRect(
            floor(minX).toInt(),
            floor(minY).toInt(),
            floor(maxX).toInt() + 1,
            floor(maxY).toInt() + 1,
        )
    }
}
