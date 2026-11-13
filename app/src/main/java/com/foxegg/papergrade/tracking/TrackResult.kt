package com.foxegg.papergrade.tracking

import com.foxegg.papergrade.geometry.PaperMesh
import com.foxegg.papergrade.geometry.Vec2
import com.foxegg.papergrade.model.TrackStatus

data class TrackResult(
    val status: TrackStatus,
    val mesh: PaperMesh?,
    val quad: List<Vec2>?,
    val needsFlattening: Boolean,
    val stable: Boolean,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    companion object {
        fun searching(width: Int, height: Int) = TrackResult(
            status = TrackStatus.SEARCHING,
            mesh = null,
            quad = null,
            needsFlattening = false,
            stable = false,
            imageWidth = width,
            imageHeight = height,
        )
    }
}
