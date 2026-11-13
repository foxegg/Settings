package com.foxegg.papergrade.camera

import android.graphics.Bitmap
import com.foxegg.papergrade.geometry.Vec2

/** 保存最近一帧正向画面和纸面四角，供「批改」按钮取用。跟踪线程和按钮线程共用这把锁。 */
class FrameStore {
    private val lock = Any()
    private var bitmap: Bitmap? = null
    private var quad: List<Vec2>? = null

    fun set(frame: Bitmap, corners: List<Vec2>?) {
        synchronized(lock) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            bitmap = frame
            quad = corners
        }
    }

    fun copy(): Pair<Bitmap, List<Vec2>>? = synchronized(lock) {
        val frame = bitmap ?: return null
        val corners = quad ?: return null
        if (frame.isRecycled || corners.size != 4) return null
        frame.copy(Bitmap.Config.ARGB_8888, false) to corners
    }

    fun clear() {
        synchronized(lock) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            bitmap = null
            quad = null
        }
    }
}
