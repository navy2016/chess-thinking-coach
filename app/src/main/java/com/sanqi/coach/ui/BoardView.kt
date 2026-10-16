package com.sanqi.coach.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.sanqi.coach.core.Move
import kotlin.math.max
import kotlin.math.min

interface BoardAdapter {
    fun columns(): Int
    fun rows(): Int

    /** true：棋子落在交叉点（围棋、象棋）；false：落在格子里（国际象棋） */
    fun intersections(): Boolean

    /** true：棋盘索引 y=0 画在屏幕底部（象棋红方在下、国象白方在下） */
    fun flipped(): Boolean

    fun colLabel(i: Int): String?
    fun rowLabel(i: Int): String?

    fun drawBoard(canvas: Canvas, rect: RectF, cell: Float, originX: Float, originY: Float, showCoords: Boolean)

    fun drawPiece(canvas: Canvas, cell: Float, idx: Int, cx: Float, cy: Float)
}

fun drawCharCentered(canvas: Canvas, textPaint: Paint, ch: String, cx: Float, cy: Float, size: Float, color: Int) {
    textPaint.textSize = size
    textPaint.color = color
    val fm = textPaint.fontMetrics
    val baseline = cy - (fm.ascent + fm.descent) / 2f
    canvas.drawText(ch, cx, baseline, textPaint)
}

class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var adapter: BoardAdapter? = null
    var onTap: ((Int) -> Unit)? = null

    var selectFrom: Int = -1
    var targetHighlights: Set<Int> = emptySet()
    var pendingMove: Move? = null
    var lastMove: Move? = null
    var hintTo: Int = -1
    var deadMarks: Set<Int> = emptySet()
    var showCoords: Boolean = true

    private val rect = RectF()
    private var cell = 0f
    private var originX = 0f
    private var originY = 0f

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#D32F2F")
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1976D2")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#6D5A3F")
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#C62828")
    }

    fun refresh() {
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = if (hMode == MeasureSpec.UNSPECIFIED || h == 0) w else min(w, h)
        setMeasuredDimension(side, side)
    }

    private fun centerOf(idx: Int): FloatArray {
        val a = adapter!!
        val cols = a.columns()
        val x = idx % cols
        val y = idx / cols
        return if (a.intersections()) {
            val sy = if (a.flipped()) a.rows() - 1 - y else y
            floatArrayOf(originX + x * cell, originY + sy * cell)
        } else {
            val sy = if (a.flipped()) a.rows() - 1 - y else y
            floatArrayOf(rect.left + x * cell + cell / 2f, rect.top + sy * cell + cell / 2f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val a = adapter ?: return
        val cols = a.columns()
        val rows = a.rows()
        cell = min(width, height) / (max(cols, rows) + if (showCoords) 1.6f else 0.7f)
        val bw = cols * cell
        val bh = rows * cell
        rect.set((width - bw) / 2f, (height - bh) / 2f, (width + bw) / 2f, (height + bh) / 2f)
        originX = rect.left + cell / 2f
        originY = rect.top + cell / 2f
        a.drawBoard(canvas, rect, cell, originX, originY, showCoords)
        for (i in 0 until cols * rows) {
            val c = centerOf(i)
            a.drawPiece(canvas, cell, i, c[0], c[1])
        }
        drawOverlays(canvas)
        if (showCoords) drawLabels(canvas, a)
    }

    private fun drawOverlays(canvas: Canvas) {
        val a = adapter ?: return
        val r = if (a.intersections()) cell * 0.46f else cell * 0.42f
        lastMove?.let { m ->
            if (m.from >= 0) ring(canvas, m.from, r, "#C62828", cell * 0.06f)
            ring(canvas, m.to, r, "#C62828", cell * 0.06f)
        }
        if (selectFrom >= 0) ring(canvas, selectFrom, r, "#1976D2", cell * 0.08f)
        for (t in targetHighlights) {
            val c = centerOf(t)
            canvas.drawCircle(c[0], c[1], cell * 0.13f, dotPaint)
        }
        pendingMove?.let { m ->
            ring(canvas, m.to, cell * 0.52f, "#F9A825", cell * 0.09f)
            if (m.from >= 0) {
                val c = centerOf(m.from)
                canvas.drawCircle(c[0], c[1], cell * 0.1f, dotPaint)
            }
        }
        if (hintTo >= 0) ring(canvas, hintTo, cell * 0.5f, "#FFD700", cell * 0.1f)
        for (d in deadMarks) {
            val c = centerOf(d)
            val s = cell * 0.3f
            crossPaint.strokeWidth = cell * 0.07f
            canvas.drawLine(c[0] - s, c[1] - s, c[0] + s, c[1] + s, crossPaint)
            canvas.drawLine(c[0] - s, c[1] + s, c[0] + s, c[1] - s, crossPaint)
        }
    }

    private fun ring(canvas: Canvas, idx: Int, radius: Float, colorHex: String, width: Float) {
        if (idx < 0) return
        val c = centerOf(idx)
        markPaint.color = Color.parseColor(colorHex)
        markPaint.strokeWidth = width
        canvas.drawCircle(c[0], c[1], radius, markPaint)
    }

    private fun drawLabels(canvas: Canvas, a: BoardAdapter) {
        labelPaint.textSize = cell * 0.34f
        for (i in 0 until a.columns()) {
            val l = a.colLabel(i) ?: continue
            val cx = if (a.intersections()) originX + i * cell else rect.left + i * cell + cell / 2f
            canvas.drawText(l, cx, rect.bottom + cell * 0.42f, labelPaint)
        }
        for (r in 0 until a.rows()) {
            val l = a.rowLabel(r) ?: continue
            val cy = if (a.intersections()) originY + r * cell else rect.top + r * cell + cell / 2f
            canvas.drawText(l, rect.left - cell * 0.42f, cy + cell * 0.12f, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        val a = adapter ?: return false
        val cols = a.columns()
        val rows = a.rows()
        val gx: Int
        val gy: Int
        if (a.intersections()) {
            val fx = (event.x - originX) / cell
            val fy = (event.y - originY) / cell
            if (fx < -0.45f || fx > cols - 0.55f || fy < -0.45f || fy > rows - 0.55f) return true
            gx = Math.round(fx)
            gy = Math.round(fy)
        } else {
            val fx = ((event.x - rect.left) / cell).toInt()
            val fy = ((event.y - rect.top) / cell).toInt()
            if (fx < 0 || fx >= cols || fy < 0 || fy >= rows) return true
            gx = fx
            gy = fy
        }
        val by = if (a.flipped()) rows - 1 - gy else gy
        val idx = by * cols + gx
        if (idx < 0 || idx >= cols * rows) return true
        performClick()
        onTap?.invoke(idx)
        return true
    }
}
