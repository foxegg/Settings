package com.foxegg.papergrade.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.foxegg.papergrade.geometry.PaperMesh
import com.foxegg.papergrade.geometry.Vec2
import com.foxegg.papergrade.model.Mark
import com.foxegg.papergrade.model.TrackStatus
import kotlin.math.atan2
import kotlin.math.max

/**
 * 只画透明批改层：网格、对钩、叉、圈、下划线和短批注。
 * 不绘制试卷照片，也不绘制模型生成的整页图片。
 */
class AnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private var mesh: PaperMesh? = null
    private var status = TrackStatus.SEARCHING
    private var needsFlattening = false
    private var marks: List<Mark> = emptyList()

    private val meshPaint = strokePaint(Color.parseColor("#802F6F4E"), 1.2f)
    private val borderPaint = strokePaint(Color.parseColor("#0F6E4C"), 2.6f)
    private val checkPaint = strokePaint(Color.parseColor("#14804A"), 2.4f)
    private val crossPaint = strokePaint(Color.parseColor("#C5362B"), 2.4f)
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C2430")
        textAlign = Paint.Align.LEFT
    }
    private val textHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F6F1E7")
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.LEFT
    }

    init {
        setWillNotDraw(false)
    }

    fun setPose(mesh: PaperMesh?, status: TrackStatus, needsFlattening: Boolean) {
        this.mesh = mesh
        this.status = status
        this.needsFlattening = needsFlattening
        invalidate()
    }

    fun setMarks(marks: List<Mark>) {
        this.marks = marks
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val paper = mesh ?: return
        borderPaint.color = when {
            needsFlattening -> Color.parseColor("#C5362B")
            status == TrackStatus.OCCLUDED -> Color.parseColor("#B86E00")
            else -> Color.parseColor("#0F6E4C")
        }
        drawMesh(canvas, paper)
        val paperWidth = paper.map(0f, 0f).dist(paper.map(1f, 0f)).coerceAtLeast(48f * density)
        for (mark in marks) {
            drawMark(canvas, paper, mark, paperWidth)
        }
    }

    private fun drawMesh(canvas: Canvas, paper: PaperMesh) {
        for (row in 0..paper.rows) {
            for (col in 0 until paper.cols) {
                val paint = if (row == 0 || row == paper.rows) borderPaint else meshPaint
                line(canvas, paper[col, row], paper[col + 1, row], paint)
            }
        }
        for (col in 0..paper.cols) {
            for (row in 0 until paper.rows) {
                val paint = if (col == 0 || col == paper.cols) borderPaint else meshPaint
                line(canvas, paper[col, row], paper[col, row + 1], paint)
            }
        }
    }

    private fun drawMark(canvas: Canvas, paper: PaperMesh, mark: Mark, paperWidth: Float) {
        when (mark.type) {
            "underline" -> drawUnderline(canvas, paper, mark, paperWidth)
            "circle" -> drawCircle(canvas, paper, mark)
            "text" -> drawNote(canvas, paper, mark, paperWidth)
            "cross" -> drawGlyph(canvas, paper, mark, paperWidth, cross = true)
            else -> drawGlyph(canvas, paper, mark, paperWidth, cross = false)
        }
    }

    private fun drawGlyph(
        canvas: Canvas,
        paper: PaperMesh,
        mark: Mark,
        paperWidth: Float,
        cross: Boolean,
    ) {
        val origin = paper.map(mark.x, mark.y)
        val size = paperWidth * 0.045f
        canvas.save()
        canvas.translate(origin.x, origin.y)
        canvas.rotate(paperAngle(paper, mark.x, mark.y))
        val paint = if (cross) crossPaint else checkPaint
        paint.strokeWidth = max(size * 0.16f, 2f * density)
        if (cross) {
            canvas.drawLine(-size * 0.38f, -size * 0.38f, size * 0.38f, size * 0.38f, paint)
            canvas.drawLine(-size * 0.38f, size * 0.38f, size * 0.38f, -size * 0.38f, paint)
        } else {
            canvas.drawLine(-size * 0.42f, 0f, -size * 0.08f, size * 0.36f, paint)
            canvas.drawLine(-size * 0.08f, size * 0.36f, size * 0.5f, -size * 0.4f, paint)
        }
        canvas.restore()
    }

    private fun drawUnderline(canvas: Canvas, paper: PaperMesh, mark: Mark, paperWidth: Float) {
        val start = paper.map(mark.x, mark.y)
        val end = if (mark.x2 != null && mark.y2 != null) {
            paper.map(mark.x2, mark.y2)
        } else {
            paper.map((mark.x + 0.12f).coerceAtMost(1f), mark.y)
        }
        crossPaint.strokeWidth = max(paperWidth * 0.004f, 2f * density)
        line(canvas, start, end, crossPaint)
    }

    private fun drawCircle(canvas: Canvas, paper: PaperMesh, mark: Mark) {
        val radius = (mark.radius ?: 0.03f).coerceIn(0.012f, 0.2f)
        val center = paper.map(mark.x, mark.y)
        val right = paper.map((mark.x + radius).coerceAtMost(1f), mark.y)
        val down = paper.map(mark.x, (mark.y + radius).coerceAtMost(1f))
        val rx = center.dist(right).coerceAtLeast(8f * density)
        val ry = center.dist(down).coerceAtLeast(8f * density)
        canvas.save()
        canvas.translate(center.x, center.y)
        canvas.rotate(paperAngle(paper, mark.x, mark.y))
        crossPaint.strokeWidth = max(2f * density, rx * 0.08f)
        canvas.drawOval(-rx, -ry, rx, ry, crossPaint)
        canvas.restore()
    }

    private fun drawNote(canvas: Canvas, paper: PaperMesh, mark: Mark, paperWidth: Float) {
        val note = mark.text?.trim().orEmpty()
        if (note.isEmpty()) return
        val shown = if (note.length <= 24) note else note.take(23) + "…"
        val origin = paper.map(mark.x, mark.y)
        val textSize = (paperWidth * 0.032f).coerceIn(12f * density, 28f * density)
        textFill.textSize = textSize
        textHalo.textSize = textSize
        textHalo.strokeWidth = textSize * 0.18f
        canvas.save()
        canvas.translate(origin.x, origin.y)
        canvas.rotate(paperAngle(paper, mark.x, mark.y))
        canvas.drawText(shown, 0f, 0f, textHalo)
        canvas.drawText(shown, 0f, 0f, textFill)
        canvas.restore()
    }

    private fun paperAngle(paper: PaperMesh, u: Float, v: Float): Float {
        val endU = (u + 0.08f).coerceAtMost(1f)
        val startU = (endU - 0.08f).coerceAtLeast(0f)
        val start = paper.map(startU, v)
        val end = paper.map(endU, v)
        return Math.toDegrees(atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())).toFloat()
    }

    private fun line(canvas: Canvas, start: Vec2, end: Vec2, paint: Paint) {
        canvas.drawLine(start.x, start.y, end.x, end.y, paint)
    }

    private fun strokePaint(color: Int, widthDp: Float): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
            strokeWidth = widthDp * resources.displayMetrics.density
        }
    }

    private val density: Float
        get() = resources.displayMetrics.density
}
