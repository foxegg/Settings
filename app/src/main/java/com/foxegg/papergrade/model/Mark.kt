package com.foxegg.papergrade.model

/** 纸面归一化坐标：原点在左上，宽和高都是 1。 */
data class Mark(
    val type: String,
    val x: Float,
    val y: Float,
    val x2: Float? = null,
    val y2: Float? = null,
    val text: String? = null,
    val radius: Float? = null,
)
