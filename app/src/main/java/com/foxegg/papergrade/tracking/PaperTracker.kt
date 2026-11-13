package com.foxegg.papergrade.tracking

import android.graphics.Bitmap
import android.util.Log
import com.foxegg.papergrade.PaperGradeApp
import com.foxegg.papergrade.geometry.PaperMesh
import com.foxegg.papergrade.geometry.Vec2
import com.foxegg.papergrade.geometry.blendMeshes
import com.foxegg.papergrade.geometry.flatteningNeeded
import com.foxegg.papergrade.geometry.hasFlippedCell
import com.foxegg.papergrade.geometry.orderQuadCorners
import com.foxegg.papergrade.geometry.pointInConvexQuad
import com.foxegg.papergrade.geometry.polygonArea
import com.foxegg.papergrade.geometry.preserveCornerOffsets
import com.foxegg.papergrade.geometry.rectifiedSize
import com.foxegg.papergrade.model.TrackStatus
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point as CvPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 对准链路：每帧检测或跟踪纸面，不请求模型。
 * 平移和旋转靠光流加单应性；轻微褶皱把单应性没解释掉的位移摊到网格上。
 * 手挡住一部分时，用还跟得上的特征点继续更新。严重变形时由平整度提示摊平。
 */
class PaperTracker {
    private var memory: Memory? = null

    fun process(bitmap: Bitmap): TrackResult = synchronized(this) {
        if (bitmap.width < 16 || bitmap.height < 16 || bitmap.isRecycled) {
            return TrackResult.searching(bitmap.width.coerceAtLeast(1), bitmap.height.coerceAtLeast(1))
        }
        if (!ensureOpenCv()) return TrackResult.searching(bitmap.width, bitmap.height)
        val smallWidth = min(TRACK_WIDTH, bitmap.width)
        val smallHeight = max(1, (bitmap.height.toFloat() / bitmap.width * smallWidth).roundToInt())
        val small = if (smallWidth == bitmap.width && smallHeight == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, true)
        }
        val gray = try {
            toGray(small)
        } catch (error: Exception) {
            Log.e(TAG, "toGray", error)
            return TrackResult.searching(bitmap.width, bitmap.height)
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "toGray", error)
            return TrackResult.searching(bitmap.width, bitmap.height)
        } finally {
            if (small !== bitmap && !small.isRecycled) small.recycle()
        }
        val scaleX = bitmap.width.toFloat() / smallWidth
        val scaleY = bitmap.height.toFloat() / smallHeight
        try {
            track(gray, bitmap.width, bitmap.height, scaleX, scaleY)
        } catch (error: Exception) {
            Log.e(TAG, "track", error)
            if (memory?.gray !== gray) gray.release()
            TrackResult.searching(bitmap.width, bitmap.height)
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "track", error)
            if (memory?.gray !== gray) gray.release()
            TrackResult.searching(bitmap.width, bitmap.height)
        }
    }

    fun rectify(bitmap: Bitmap, quad: List<Vec2>): Bitmap? = synchronized(this) {
        if (quad.size != 4 || bitmap.isRecycled) return null
        if (!ensureOpenCv()) return null
        val rgba = Mat()
        val warped = Mat()
        val source = MatOfPoint2f()
        val dest = MatOfPoint2f()
        var transform: Mat? = null
        try {
            Utils.bitmapToMat(bitmap, rgba)
            val (width, height) = rectifiedSize(quad)
            source.fromArray(*quad.map { CvPoint(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
            dest.fromArray(
                CvPoint(0.0, 0.0),
                CvPoint((width - 1).toDouble(), 0.0),
                CvPoint((width - 1).toDouble(), (height - 1).toDouble()),
                CvPoint(0.0, (height - 1).toDouble()),
            )
            transform = Imgproc.getPerspectiveTransform(source, dest)
            Imgproc.warpPerspective(rgba, warped, transform, Size(width.toDouble(), height.toDouble()))
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, output)
            output
        } catch (error: Exception) {
            Log.e(TAG, "rectify", error)
            null
        } finally {
            rgba.release()
            warped.release()
            source.release()
            dest.release()
            transform?.release()
        }
    }

    fun release() {
        synchronized(this) { releaseMemory() }
    }

    private fun track(
        gray: Mat,
        fullWidth: Int,
        fullHeight: Int,
        scaleX: Float,
        scaleY: Float,
    ): TrackResult {
        val existing = memory
        if (existing == null) {
            val quad = detectQuad(gray)
            val mesh = quad?.let { corners ->
                try {
                    planarMesh(corners)
                } catch (error: Exception) {
                    Log.w(TAG, "planar", error)
                    null
                }
            }
            if (quad == null || mesh == null || hasFlippedCell(mesh)) {
                gray.release()
                return TrackResult.searching(fullWidth, fullHeight)
            }
            memory = Memory(
                gray = gray,
                points = seed(gray, mesh, emptyList()),
                mesh = mesh,
                miss = 0,
                frame = 0,
                stableCount = 0,
                lastCenter = mesh.center(),
            )
            return publish(mesh, TrackStatus.TRACKING, false, false, fullWidth, fullHeight, scaleX, scaleY)
        }

        val update = tryUpdate(existing, gray)
        if (update == null) {
            gray.release()
            existing.miss += 1
            existing.stableCount = 0
            if (existing.miss > MAX_MISS) {
                releaseMemory()
                return TrackResult(
                    status = TrackStatus.LOST,
                    mesh = null,
                    quad = null,
                    needsFlattening = false,
                    stable = false,
                    imageWidth = fullWidth,
                    imageHeight = fullHeight,
                )
            }
            val ratios = flatness(existing.mesh)
            return publish(
                existing.mesh,
                TrackStatus.OCCLUDED,
                flatteningNeeded(ratios.first, ratios.second),
                false,
                fullWidth,
                fullHeight,
                scaleX,
                scaleY,
            )
        }

        existing.gray.release()
        existing.gray = gray
        existing.mesh = update.mesh
        existing.points = update.points
        existing.miss = 0
        existing.frame += 1
        val ratios = flatness(existing.mesh)
        val flatten = existing.frame >= 5 && flatteningNeeded(ratios.first, ratios.second)
        val center = existing.mesh.center()
        val shift = existing.lastCenter?.dist(center) ?: Float.MAX_VALUE
        existing.lastCenter = center
        val smallWidth = fullWidth / scaleX
        if (!update.occluded && !flatten && shift < smallWidth * 0.008f) {
            existing.stableCount += 1
        } else {
            existing.stableCount = 0
        }
        val status = if (update.occluded) TrackStatus.OCCLUDED else TrackStatus.TRACKING
        val stable = status == TrackStatus.TRACKING && existing.stableCount >= 24
        return publish(existing.mesh, status, flatten, stable, fullWidth, fullHeight, scaleX, scaleY)
    }

    private fun tryUpdate(mem: Memory, gray: Mat): Update? {
        val matches = flow(mem.gray, gray, mem.points)
        if (matches.size < 8) return null
        val source = matches.map { it.prev }.toPointMat()
        val dest = matches.map { it.next }.toPointMat()
        val mask = Mat()
        var homography: Mat? = null
        try {
            homography = Calib3d.findHomography(source, dest, Calib3d.RANSAC, 3.0, mask)
            val matrix = homography ?: return null
            if (matrix.empty()) return null
            val warpedPoints = transformPoints(mem.mesh.points, matrix) ?: return null
            if (warpedPoints.size != mem.mesh.points.size) return null
            var warped = PaperMesh(mem.mesh.cols, mem.mesh.rows, warpedPoints)
            if (rejectedMotion(mem.mesh, warped)) return null
            val inliers = ArrayList<FlowMatch>()
            val wrinkles = ArrayList<Pair<Vec2, Vec2>>()
            val predicted = transformPoints(matches.map { it.prev }, matrix)
            for (i in matches.indices) {
                if (maskAt(mask, i)) {
                    inliers += matches[i]
                } else if (predicted != null && i < predicted.size) {
                    wrinkles += matches[i].next to (matches[i].next - predicted[i])
                }
            }
            if (inliers.size < 8) return null
            val wrinkled = applyResiduals(warped, wrinkles)
            if (!hasFlippedCell(wrinkled)) warped = wrinkled
            if (hasFlippedCell(warped)) return null

            var largeJump = false
            if (mem.frame % 8 == 0 || inliers.size < 15) {
                val detected = detectQuad(gray)
                if (detected != null) {
                    val reconciled = reconcile(warped, detected)
                    if (reconciled != null) {
                        warped = reconciled.first
                        largeJump = reconciled.second
                    }
                }
            }
            if (!largeJump) {
                val smoothed = blendMeshes(mem.mesh, warped, 0.82f)
                if (!hasFlippedCell(smoothed) && !rejectedMotion(mem.mesh, smoothed)) {
                    warped = smoothed
                }
            }
            val tracked = inliers.map { it.next }
            return Update(
                mesh = warped,
                points = seed(gray, warped, tracked),
                occluded = !spreadOk(tracked, warped),
            )
        } catch (error: Exception) {
            Log.w(TAG, "update", error)
            return null
        } finally {
            source.release()
            dest.release()
            mask.release()
            homography?.release()
        }
    }

    private fun reconcile(mesh: PaperMesh, detected: List<Vec2>): Pair<PaperMesh, Boolean>? {
        val oldArea = polygonArea(mesh.corners())
        val newArea = polygonArea(detected)
        if (newArea > oldArea * 1.35f && pointInConvexQuad(mesh.center(), detected)) {
            val rebuilt = planarMesh(detected)
            if (hasFlippedCell(rebuilt)) return null
            return rebuilt to true
        }
        val corners = mesh.corners()
        val diagonal = corners[0].dist(corners[2]).coerceAtLeast(1f)
        val close = corners.indices.all { corners[it].dist(detected[it]) < diagonal * 0.10f }
        if (!close) return null
        val blended = corners.indices.map { corners[it] * 0.75f + detected[it] * 0.25f }
        val nudged = preserveCornerOffsets(mesh, blended)
        if (hasFlippedCell(nudged)) return null
        return nudged to false
    }

    private fun flow(previous: Mat, next: Mat, points: List<Vec2>): List<FlowMatch> {
        if (points.size < 4) return emptyList()
        val previousMat = points.toPointMat()
        val nextMat = MatOfPoint2f()
        val backMat = MatOfPoint2f()
        val status = MatOfByte()
        val error = MatOfFloat()
        val backStatus = MatOfByte()
        val backError = MatOfFloat()
        try {
            Video.calcOpticalFlowPyrLK(previous, next, previousMat, nextMat, status, error)
            Video.calcOpticalFlowPyrLK(next, previous, nextMat, backMat, backStatus, backError)
            val nextPoints = nextMat.toArray()
            val backPoints = backMat.toArray()
            val forwardStatus = status.toArray()
            val backwardStatus = backStatus.toArray()
            val forwardError = error.toArray()
            val matches = ArrayList<FlowMatch>()
            val count = minOf(points.size, nextPoints.size, backPoints.size, forwardStatus.size, backwardStatus.size)
            for (i in 0 until count) {
                if (forwardStatus[i].toInt() != 1 || backwardStatus[i].toInt() != 1) continue
                if (i < forwardError.size && forwardError[i] > 30f) continue
                val back = Vec2(backPoints[i].x.toFloat(), backPoints[i].y.toFloat())
                if (back.dist(points[i]) > 1.6f) continue
                val landed = Vec2(nextPoints[i].x.toFloat(), nextPoints[i].y.toFloat())
                if (landed.x < 0f || landed.y < 0f || landed.x >= next.cols() || landed.y >= next.rows()) continue
                matches += FlowMatch(points[i], landed)
            }
            return matches
        } catch (error: Exception) {
            Log.w(TAG, "flow", error)
            return emptyList()
        } finally {
            previousMat.release()
            nextMat.release()
            backMat.release()
            status.release()
            error.release()
            backStatus.release()
            backError.release()
        }
    }

    private fun applyResiduals(mesh: PaperMesh, residuals: List<Pair<Vec2, Vec2>>): PaperMesh {
        if (residuals.isEmpty()) return mesh
        val diagonal = mesh.corners()[0].dist(mesh.corners()[2]).coerceAtLeast(1f)
        val sigma = 0.09f * diagonal
        val radius = 0.22f * diagonal
        val denom = 2f * sigma * sigma
        val accumulated = Array(mesh.points.size) { Vec2(0f, 0f) }
        val weights = FloatArray(mesh.points.size)
        for ((position, delta) in residuals) {
            val length = delta.length()
            if (length < 2f || length > 22f) continue
            val clamped = if (length > 18f) delta * (18f / length) else delta
            for (i in mesh.points.indices) {
                val distance = mesh.points[i].dist(position)
                if (distance > radius) continue
                val weight = exp(-(distance * distance) / denom)
                accumulated[i] = accumulated[i] + clamped * weight
                weights[i] += weight
            }
        }
        return mesh.transform { col, row, point ->
            val index = row * (mesh.cols + 1) + col
            if (weights[index] < 1e-3f) point else point + accumulated[index] * (0.65f / weights[index])
        }
    }

    private fun rejectedMotion(before: PaperMesh, after: PaperMesh): Boolean {
        if (hasFlippedCell(after)) return true
        val diagonal = before.corners()[0].dist(before.corners()[2]).coerceAtLeast(1f)
        val shift = (0..3).maxOf { before.corners()[it].dist(after.corners()[it]) }
        if (shift > diagonal * 0.30f) return true
        val oldArea = polygonArea(before.corners())
        val newArea = polygonArea(after.corners())
        return oldArea > 1f && (newArea > oldArea * 1.85f || newArea < oldArea * 0.5f)
    }

    private fun spreadOk(points: List<Vec2>, mesh: PaperMesh): Boolean {
        if (points.isEmpty()) return false
        val corners = mesh.corners()
        val paperWidth = (corners.maxOf { it.x } - corners.minOf { it.x }).coerceAtLeast(1f)
        val paperHeight = (corners.maxOf { it.y } - corners.minOf { it.y }).coerceAtLeast(1f)
        val spanX = points.maxOf { it.x } - points.minOf { it.x }
        val spanY = points.maxOf { it.y } - points.minOf { it.y }
        return spanX > 0.35f * paperWidth && spanY > 0.35f * paperHeight
    }

    private fun flatness(mesh: PaperMesh): Pair<Float, Float> {
        val source = MatOfPoint2f()
        val dest = MatOfPoint2f()
        val projected = MatOfPoint2f()
        var homography: Mat? = null
        try {
            val grid = ArrayList<Vec2>(mesh.points.size)
            for (row in 0..mesh.rows) {
                for (col in 0..mesh.cols) {
                    grid += Vec2(col.toFloat(), row.toFloat())
                }
            }
            source.fromArray(*grid.map { CvPoint(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
            dest.fromArray(*mesh.points.map { CvPoint(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
            homography = Calib3d.findHomography(source, dest, 0)
            if (homography.empty()) return 1f to 1f
            Core.perspectiveTransform(source, projected, homography)
            val predicted = projected.toArray()
            if (predicted.size != mesh.points.size) return 1f to 1f
            var sum = 0.0
            var peak = 0.0
            for (i in mesh.points.indices) {
                val dx = mesh.points[i].x - predicted[i].x
                val dy = mesh.points[i].y - predicted[i].y
                val distance = hypot(dx.toDouble(), dy.toDouble())
                sum += distance
                if (distance > peak) peak = distance
            }
            val diagonal = mesh.corners()[0].dist(mesh.corners()[2]).toDouble().coerceAtLeast(1.0)
            val count = mesh.points.size.coerceAtLeast(1)
            return (sum / count / diagonal).toFloat() to (peak / diagonal).toFloat()
        } catch (error: Exception) {
            Log.w(TAG, "flatness", error)
            return 0f to 0f
        } finally {
            source.release()
            dest.release()
            projected.release()
            homography?.release()
        }
    }

    private fun detectQuad(gray: Mat): List<Vec2>? {
        val blurred = Mat()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            val guesses = quadsFromEdges(blurred) + quadsFromBrightRegion(blurred)
            return guesses.maxByOrNull { it.score }?.corners
        } catch (error: Exception) {
            Log.w(TAG, "detect", error)
            return null
        } finally {
            blurred.release()
        }
    }

    private fun quadsFromEdges(blurred: Mat): List<QuadGuess> {
        val edges = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            Imgproc.Canny(blurred, edges, 40.0, 120.0)
            Imgproc.dilate(edges, edges, kernel)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            return scoreContours(contours, blurred.rows() * blurred.cols())
        } finally {
            edges.release()
            kernel.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun quadsFromBrightRegion(blurred: Mat): List<QuadGuess> {
        val binary = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            return scoreContours(contours, blurred.rows() * blurred.cols())
        } finally {
            binary.release()
            kernel.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun scoreContours(contours: List<MatOfPoint>, imageArea: Int): List<QuadGuess> {
        val minArea = imageArea * 0.12
        val largest = contours.map { it to Imgproc.contourArea(it) }
            .filter { it.second >= minArea }
            .sortedByDescending { it.second }
            .take(8)
        val guesses = ArrayList<QuadGuess>()
        for ((contour, area) in largest) {
            val curve = MatOfPoint2f(*contour.toArray())
            val approx = MatOfPoint2f()
            try {
                val perimeter = Imgproc.arcLength(curve, true)
                Imgproc.approxPolyDP(curve, approx, 0.02 * perimeter, true)
                val approxPoints = if (approx.rows() <= 0) emptyArray() else approx.toArray()
                val rect = Imgproc.minAreaRect(curve)
                val rectArea = rect.size.width * rect.size.height
                if (rectArea < 1.0) continue
                val rectangularity = (area / rectArea).coerceAtMost(1.0)
                if (rectangularity < 0.55) continue
                val shortSide = min(rect.size.width, rect.size.height).coerceAtLeast(1.0)
                val aspect = max(rect.size.width, rect.size.height) / shortSide
                if (aspect > 2.8 || aspect < 0.35) continue
                val corners = if (approxPoints.size == 4 && isConvex(approxPoints)) {
                    orderQuadCorners(approxPoints.map { Vec2(it.x.toFloat(), it.y.toFloat()) })
                } else if (rectangularity >= 0.82) {
                    val box = Array(4) { CvPoint() }
                    rect.points(box)
                    orderQuadCorners(box.map { Vec2(it.x.toFloat(), it.y.toFloat()) })
                } else {
                    null
                }
                if (corners != null) {
                    val score = area * rectangularity * if (approxPoints.size == 4) 1.0 else 0.9
                    guesses += QuadGuess(corners, score)
                }
            } catch (error: Exception) {
                Log.w(TAG, "contour", error)
            } finally {
                curve.release()
                approx.release()
            }
        }
        return guesses
    }

    private fun seed(gray: Mat, mesh: PaperMesh, keep: List<Vec2>): List<Vec2> {
        val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val corners = MatOfPoint()
        val found = MatOfPoint()
        var kernel: Mat? = null
        try {
            corners.fromArray(*mesh.corners().map { CvPoint(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
            Imgproc.fillPoly(mask, listOf(corners), Scalar(255.0))
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
            Imgproc.erode(mask, mask, kernel)
            Imgproc.goodFeaturesToTrack(gray, found, 100, 0.01, 12.0, mask, 7, false, 0.04)
            val fresh = if (found.empty()) {
                emptyList()
            } else {
                found.toArray().map { Vec2(it.x.toFloat(), it.y.toFloat()) }
            }
            val merged = ArrayList<Vec2>(keep.size + fresh.size)
            merged += keep
            for (point in fresh) {
                if (merged.size >= 120) break
                if (merged.none { it.dist(point) < 12f }) merged += point
            }
            return merged
        } catch (error: Exception) {
            Log.w(TAG, "seed", error)
            return keep
        } finally {
            mask.release()
            corners.release()
            found.release()
            kernel?.release()
        }
    }

    private fun planarMesh(corners: List<Vec2>): PaperMesh {
        val source = MatOfPoint2f()
        val dest = MatOfPoint2f()
        val grid = MatOfPoint2f()
        val output = MatOfPoint2f()
        var transform: Mat? = null
        try {
            source.fromArray(
                CvPoint(0.0, 0.0),
                CvPoint(CELLS.toDouble(), 0.0),
                CvPoint(CELLS.toDouble(), CELLS.toDouble()),
                CvPoint(0.0, CELLS.toDouble()),
            )
            dest.fromArray(*corners.map { CvPoint(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
            transform = Imgproc.getPerspectiveTransform(source, dest)
            val samples = ArrayList<Vec2>((CELLS + 1) * (CELLS + 1))
            for (row in 0..CELLS) {
                for (col in 0..CELLS) {
                    samples += Vec2(col.toFloat(), row.toFloat())
                }
            }
            grid.fromArray(*samples.map { CvPoint(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
            Core.perspectiveTransform(grid, output, transform)
            val mapped = output.toArray().map { Vec2(it.x.toFloat(), it.y.toFloat()) }
            return PaperMesh(CELLS, CELLS, mapped)
        } finally {
            source.release()
            dest.release()
            grid.release()
            output.release()
            transform?.release()
        }
    }

    private fun transformPoints(points: List<Vec2>, homography: Mat): List<Vec2>? {
        if (points.isEmpty()) return emptyList()
        val source = points.toPointMat()
        val dest = MatOfPoint2f()
        try {
            Core.perspectiveTransform(source, dest, homography)
            return dest.toArray().map { Vec2(it.x.toFloat(), it.y.toFloat()) }
        } catch (error: Exception) {
            Log.w(TAG, "transform", error)
            return null
        } finally {
            source.release()
            dest.release()
        }
    }

    private fun publish(
        mesh: PaperMesh,
        status: TrackStatus,
        needsFlattening: Boolean,
        stable: Boolean,
        fullWidth: Int,
        fullHeight: Int,
        scaleX: Float,
        scaleY: Float,
    ): TrackResult {
        val full = mesh.scaled(scaleX, scaleY)
        return TrackResult(
            status = status,
            mesh = full,
            quad = full.corners(),
            needsFlattening = needsFlattening,
            stable = stable,
            imageWidth = fullWidth,
            imageHeight = fullHeight,
        )
    }

    private fun toGray(bitmap: Bitmap): Mat {
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return gray
        } catch (error: Exception) {
            gray.release()
            throw error
        } finally {
            rgba.release()
        }
    }

    private fun ensureOpenCv(): Boolean {
        if (PaperGradeApp.openCvReady) return true
        return try {
            val ready = OpenCVLoader.initLocal()
            if (ready) PaperGradeApp.openCvReady = true
            ready
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "OpenCV", error)
            false
        }
    }

    private fun releaseMemory() {
        memory?.gray?.release()
        memory = null
    }

    private fun isConvex(points: Array<CvPoint>): Boolean {
        val mat = MatOfPoint(*points)
        return try {
            Imgproc.isContourConvex(mat)
        } finally {
            mat.release()
        }
    }

    private fun maskAt(mask: Mat, index: Int): Boolean {
        if (mask.empty()) return false
        val row: Int
        val col: Int
        if (mask.rows() == 1) {
            row = 0
            col = index
        } else {
            row = index
            col = 0
        }
        if (row < 0 || col < 0 || row >= mask.rows() || col >= mask.cols()) return false
        val value = mask.get(row, col) ?: return false
        return value.isNotEmpty() && value[0] > 0.0
    }

    private fun List<Vec2>.toPointMat(): MatOfPoint2f {
        val mat = MatOfPoint2f()
        if (isNotEmpty()) {
            mat.fromArray(*map { CvPoint(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        }
        return mat
    }

    private class Memory(
        var gray: Mat,
        var points: List<Vec2>,
        var mesh: PaperMesh,
        var miss: Int,
        var frame: Int,
        var stableCount: Int,
        var lastCenter: Vec2?,
    )

    private class Update(
        val mesh: PaperMesh,
        val points: List<Vec2>,
        val occluded: Boolean,
    )

    private class FlowMatch(val prev: Vec2, val next: Vec2)

    private class QuadGuess(val corners: List<Vec2>, val score: Double)

    private companion object {
        const val TAG = "PaperTracker"
        const val CELLS = 4
        const val TRACK_WIDTH = 640
        const val MAX_MISS = 12
    }
}
