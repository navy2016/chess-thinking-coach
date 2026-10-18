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
import kotlin.math.min
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
    fun pieceSide(idx: Int): Int?
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
    var variationMoves: List<Move> = emptyList()
    var variationStartSide: Int = 0
    var numberVariation: Boolean = false
    private var pressedX: Float = 0f
    private var pressedY: Float = 0f
    private var tapCancelled: Boolean = false
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val scaleDetector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 3f)
            panX = panX.coerceIn(-width * (zoom - 1f) / 2f, width * (zoom - 1f) / 2f)
            panY = panY.coerceIn(-height * (zoom - 1f) / 2f, height * (zoom - 1f) / 2f)
            tapCancelled = true
            invalidate()
            return true
        }
    })

    fun resetViewport() {
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

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
        val a = adapter
        val desired = (w * ((a?.rows() ?: 8) + 1.6f) / ((a?.columns() ?: 8) + 1.6f)).toInt()
        setMeasuredDimension(w, if (hMode == MeasureSpec.UNSPECIFIED || h == 0) desired else h)
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
        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(zoom, zoom, width / 2f, height / 2f)
        val cols = a.columns()
        val rows = a.rows()
        val padding = if (showCoords) 1.6f else 0.7f
        cell = min(width / (cols + padding), height / (rows + padding))
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
        canvas.restore()
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
            ring(canvas, m.to, cell * 0.50f, "#803FE2", cell * 0.075f)
            if (m.from >= 0 && m.from != m.to) arrow(canvas, m.from, m.to, 230)
        }
        for ((index, move) in variationMoves.withIndex()) {
            if (move.to < 0) continue
            if (numberVariation) {
                val side = (variationStartSide + index) % 2
                if (a.pieceSide(move.to) != side || variationMoves.drop(index + 1).any { it.to == move.to }) continue
                val c = centerOf(move.to)
                val black = side == 0
                dotPaint.color = if (black) Color.parseColor("#211D24") else Color.parseColor("#FFFCF5")
                canvas.drawCircle(c[0], c[1], cell * 0.25f, dotPaint)
                ring(canvas, move.to, cell * 0.25f, "#803FE2", cell * 0.045f)
                drawCharCentered(canvas, labelPaint, (index + 1).toString(), c[0], c[1], cell * 0.30f,
                    if (black) Color.WHITE else Color.parseColor("#302936"))
                if (index > 0 && variationMoves[index - 1].to >= 0) arrow(canvas, variationMoves[index - 1].to, move.to, 110)
            } else if (move.from >= 0 && move.from != move.to) {
                arrow(canvas, move.from, move.to, if (index == variationMoves.lastIndex) 220 else 100)
            }
        }
        dotPaint.color = Color.parseColor("#1976D2")
        labelPaint.color = Color.parseColor("#6D5A3F")
        if (hintTo >= 0) ring(canvas, hintTo, cell * 0.5f, "#FFD700", cell * 0.1f)
        for (d in deadMarks) {
            val c = centerOf(d)
            val s = cell * 0.3f
            crossPaint.strokeWidth = cell * 0.07f
            canvas.drawLine(c[0] - s, c[1] - s, c[0] + s, c[1] + s, crossPaint)
            canvas.drawLine(c[0] - s, c[1] + s, c[0] + s, c[1] - s, crossPaint)
        }
    }

    private fun arrow(canvas: Canvas, from: Int, to: Int, alpha: Int) {
        if (from == to) return
        val a = centerOf(from)
        val b = centerOf(to)
        val angle = atan2(b[1] - a[1], b[0] - a[0])
        val endX = b[0] - cos(angle) * cell * 0.23f
        val endY = b[1] - sin(angle) * cell * 0.23f
        markPaint.color = Color.argb(alpha, 128, 63, 226)
        markPaint.strokeWidth = cell * 0.065f
        canvas.drawLine(a[0], a[1], endX, endY, markPaint)
        for (offset in floatArrayOf(-0.55f, 0.55f)) {
            canvas.drawLine(endX, endY, endX - cos(angle + offset) * cell * 0.22f,
                endY - sin(angle + offset) * cell * 0.22f, markPaint)
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

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (zoom > 1f || event.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        val pointX = (event.x - panX - width / 2f) / zoom + width / 2f
        val pointY = (event.y - panY - height / 2f) / zoom + height / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedX = event.x
                pressedY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                tapCancelled = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                if (kotlin.math.abs(event.x - pressedX) > slop || kotlin.math.abs(event.y - pressedY) > slop) tapCancelled = true
                if (zoom > 1f && !scaleDetector.isInProgress && event.pointerCount == 1 && tapCancelled) {
                    panX = (panX + event.x - lastTouchX).coerceIn(-width * (zoom - 1f) / 2f, width * (zoom - 1f) / 2f)
                    panY = (panY + event.y - lastTouchY).coerceIn(-height * (zoom - 1f) / 2f, height * (zoom - 1f) / 2f)
                    invalidate()
                }
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> { tapCancelled = true; return true }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) {
                    lastTouchX = event.getX(remaining)
                    lastTouchY = event.getY(remaining)
                }
                tapCancelled = true
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tapCancelled = true
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (tapCancelled) return true
            }
            else -> return true
        }
        val a = adapter ?: return false
        if (cell <= 0f) return true
        val cols = a.columns()
        val rows = a.rows()
        val gx: Int
        val gy: Int
        if (a.intersections()) {
            val fx = (pointX - originX) / cell
            val fy = (pointY - originY) / cell
            if (fx < -0.45f || fx > cols - 0.55f || fy < -0.45f || fy > rows - 0.55f) return true
            gx = Math.round(fx)
            gy = Math.round(fy)
        } else {
            if (!rect.contains(pointX, pointY)) return true
            val fx = ((pointX - rect.left) / cell).toInt()
            val fy = ((pointY - rect.top) / cell).toInt()
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
