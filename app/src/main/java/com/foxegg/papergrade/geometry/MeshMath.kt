package com.foxegg.papergrade.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt

const val MEAN_FLATNESS_RATIO = 0.05f
const val MAX_FLATNESS_RATIO = 0.12f

fun bilinearQuad(tl: Vec2, tr: Vec2, br: Vec2, bl: Vec2, u: Float, v: Float): Vec2 {
    val top = tl * (1f - u) + tr * u
    val bottom = bl * (1f - u) + br * u
    return top * (1f - v) + bottom * v
}

fun signedArea(points: List<Vec2>): Float {
    if (points.size < 3) return 0f
    var sum = 0f
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        sum += a.x * b.y - b.x * a.y
    }
    return sum * 0.5f
}

fun polygonArea(points: List<Vec2>): Float = abs(signedArea(points))

fun cellSignedArea(mesh: PaperMesh, col: Int, row: Int): Float {
    return signedArea(
        listOf(
            mesh[col, row],
            mesh[col + 1, row],
            mesh[col + 1, row + 1],
            mesh[col, row + 1],
        ),
    )
}

fun hasFlippedCell(mesh: PaperMesh): Boolean {
    for (row in 0 until mesh.rows) {
        for (col in 0 until mesh.cols) {
            if (cellSignedArea(mesh, col, row) <= 1f) return true
        }
    }
    return false
}

fun flatteningNeeded(meanRatio: Float, maxRatio: Float): Boolean {
    return meanRatio >= MEAN_FLATNESS_RATIO || maxRatio >= MAX_FLATNESS_RATIO
}

fun blendMeshes(from: PaperMesh, to: PaperMesh, towardTo: Float): PaperMesh {
    require(from.cols == to.cols && from.rows == to.rows)
    val weight = towardTo.coerceIn(0f, 1f)
    return from.transform { c, r, point -> point * (1f - weight) + to[c, r] * weight }
}

/** 四角挪动后，格子内部相对双线性平面的偏移保持不变。 */
fun preserveCornerOffsets(mesh: PaperMesh, newCorners: List<Vec2>): PaperMesh {
    require(newCorners.size == 4)
    val old = mesh.corners()
    return mesh.transform { c, r, point ->
        val u = c.toFloat() / mesh.cols
        val v = r.toFloat() / mesh.rows
        val oldPlanar = bilinearQuad(old[0], old[1], old[2], old[3], u, v)
        val newPlanar = bilinearQuad(newCorners[0], newCorners[1], newCorners[2], newCorners[3], u, v)
        newPlanar + (point - oldPlanar)
    }
}

fun pointInConvexQuad(point: Vec2, quad: List<Vec2>): Boolean {
    if (quad.size != 4) return false
    var sign = 0
    for (i in 0..3) {
        val a = quad[i]
        val b = quad[(i + 1) % 4]
        val cross = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
        val current = when {
            cross > 0f -> 1
            cross < 0f -> -1
            else -> continue
        }
        if (sign == 0) {
            sign = current
        } else if (sign != current) {
            return false
        }
    }
    return true
}

/** 把任意顺序的四个角排成左上、右上、右下、左下。图像 y 向下。 */
fun orderQuadCorners(points: List<Vec2>): List<Vec2>? {
    if (points.size != 4) return null
    if (points.distinctBy { it.x to it.y }.size != 4) return null
    val cx = points.sumOf { it.x.toDouble() }.toFloat() / 4f
    val cy = points.sumOf { it.y.toDouble() }.toFloat() / 4f
    val sorted = points.sortedBy { atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
    val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
    val ordered = List(4) { sorted[(start + it) % 4] }
    if (polygonArea(ordered) < 1f) return null
    return ordered
}

fun rectifiedSize(quad: List<Vec2>, longEdge: Int = 1600): Pair<Int, Int> {
    if (quad.size != 4) return 320 to 320
    val top = quad[0].dist(quad[1])
    val bottom = quad[3].dist(quad[2])
    val left = quad[0].dist(quad[3])
    val right = quad[1].dist(quad[2])
    val width = (top + bottom) / 2f
    val height = (left + right) / 2f
    if (width < 1f || height < 1f) return 320 to 320
    val scale = longEdge.toFloat() / max(width, height)
    val w = (width * scale).roundToInt().coerceIn(64, 2200)
    val h = (height * scale).roundToInt().coerceIn(64, 2200)
    return w to h
}
