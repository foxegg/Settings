package com.foxegg.papergrade.geometry

import kotlin.math.hypot

data class Vec2(val x: Float, val y: Float) {
    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)

    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)

    operator fun times(scale: Float) = Vec2(x * scale, y * scale)

    fun length(): Float = hypot(x.toDouble(), y.toDouble()).toFloat()

    fun dist(other: Vec2): Float = (this - other).length()
}
