package com.foxegg.papergrade.geometry

import kotlin.math.floor

/**
 * 纸面网格。points 按行优先，(cols+1) * (rows+1) 个点，坐标在调用方自己的像素空间里。
 * 归一化纸面坐标 (u, v) 用所在格子的四个点做双线性插值，所以轻微褶皱可以跟着走。
 */
data class PaperMesh(
    val cols: Int,
    val rows: Int,
    val points: List<Vec2>,
) {
    init {
        require(cols >= 1 && rows >= 1)
        require(points.size == (cols + 1) * (rows + 1))
    }

    operator fun get(c: Int, r: Int): Vec2 = points[r * (cols + 1) + c]

    fun corners(): List<Vec2> = listOf(get(0, 0), get(cols, 0), get(cols, rows), get(0, rows))

    fun center(): Vec2 = map(0.5f, 0.5f)

    fun map(u: Float, v: Float): Vec2 {
        val uu = u.coerceIn(0f, 1f)
        val vv = v.coerceIn(0f, 1f)
        val x = uu * cols
        val y = vv * rows
        var col = floor(x).toInt()
        var row = floor(y).toInt()
        var tx = x - col
        var ty = y - row
        if (col >= cols) {
            col = cols - 1
            tx = 1f
        }
        if (row >= rows) {
            row = rows - 1
            ty = 1f
        }
        val top = get(col, row) * (1f - tx) + get(col + 1, row) * tx
        val bottom = get(col, row + 1) * (1f - tx) + get(col + 1, row + 1) * tx
        return top * (1f - ty) + bottom * ty
    }

    fun scaled(sx: Float, sy: Float): PaperMesh {
        return PaperMesh(cols, rows, points.map { Vec2(it.x * sx, it.y * sy) })
    }

    fun transform(block: (c: Int, r: Int, point: Vec2) -> Vec2): PaperMesh {
        val mapped = ArrayList<Vec2>(points.size)
        for (r in 0..rows) {
            for (c in 0..cols) {
                mapped += block(c, r, get(c, r))
            }
        }
        return PaperMesh(cols, rows, mapped)
    }
}
