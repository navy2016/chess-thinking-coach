package com.sanqi.coach.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.sanqi.coach.core.chess.ChessEngine
import com.sanqi.coach.core.chess.ChessPosition
import com.sanqi.coach.core.go.GoEngine
import com.sanqi.coach.core.go.GoPosition
import com.sanqi.coach.core.xiangqi.XiangqiEngine
import com.sanqi.coach.core.xiangqi.XiangqiPosition
import kotlin.math.max

private const val WOOD_GO = "#E3BE83"
private const val WOOD_XQ = "#E3BE83"

class GoBoard(private val engine: GoEngine) : BoardAdapter {

    var pos: GoPosition? = null

    private val fill = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#4A3A28")
    }
    private val stone = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#888888")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun pieceSide(idx: Int): Int? = pos?.board?.get(idx)?.takeIf { it != 0 }?.minus(1)

    override fun columns(): Int = pos?.size ?: 19
    override fun rows(): Int = pos?.size ?: 19
    override fun intersections(): Boolean = true
    override fun flipped(): Boolean = false

    override fun colLabel(i: Int): String {
        var n = i + 1
        if (n > 8) n++
        return ('A' + n - 1).toString()
    }

    override fun rowLabel(r: Int): String? = ((columns() - r).toString())

    override fun drawBoard(canvas: Canvas, rect: RectF, cell: Float, originX: Float, originY: Float, showCoords: Boolean) {
        val size = columns()
        fill.color = Color.parseColor(WOOD_GO)
        canvas.drawRect(rect, fill)
        line.strokeWidth = max(1f, cell * 0.02f)
        val end = (size - 1) * cell
        for (i in 0 until size) {
            canvas.drawLine(originX + i * cell, originY, originX + i * cell, originY + end, line)
            canvas.drawLine(originX, originY + i * cell, originX + end, originY + i * cell, line)
        }
        val stars = when (size) {
            19 -> listOf(3, 9, 15)
            13 -> listOf(3, 6, 9)
            9 -> listOf(2, 4, 6)
            else -> emptyList()
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4A3A28") }
        for (sy in stars) for (sx in stars) {
            canvas.drawCircle(originX + sx * cell, originY + sy * cell, cell * 0.09f, dot)
        }
    }

    override fun drawPiece(canvas: Canvas, cell: Float, idx: Int, cx: Float, cy: Float) {
        val p = pos ?: return
        val s = engine.stoneAt(p, idx)
        if (s == 0) return
        stone.color = Color.parseColor("#553A291A")
        canvas.drawCircle(cx, cy + cell * 0.035f, cell * 0.45f, stone)
        stone.color = if (s == 1) Color.parseColor("#211F1D") else Color.parseColor("#FFFDF7")
        canvas.drawCircle(cx, cy, cell * 0.44f, stone)
        ring.color = if (s == 1) Color.parseColor("#11100F") else Color.parseColor("#A99B85")
        ring.strokeWidth = cell * 0.025f
        canvas.drawCircle(cx, cy, cell * 0.44f, ring)
        stone.color = if (s == 1) Color.parseColor("#35FFFFFF") else Color.parseColor("#AFFFFFFF")
        canvas.drawOval(cx - cell * 0.26f, cy - cell * 0.30f, cx + cell * 0.12f, cy - cell * 0.17f, stone)
    }
}

class XiangqiBoard(private val engine: XiangqiEngine) : BoardAdapter {

    var pos: XiangqiPosition? = null

    private val fill = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#5A4020")
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun pieceSide(idx: Int): Int? = pos?.board?.get(idx)?.takeIf { it != 0 }?.let { if (it > 0) 0 else 1 }

    override fun columns(): Int = 9
    override fun rows(): Int = 10
    override fun intersections(): Boolean = true
    override fun flipped(): Boolean = true
    override fun colLabel(i: Int): String? = null
    override fun rowLabel(i: Int): String? = null

    override fun drawBoard(canvas: Canvas, rect: RectF, cell: Float, originX: Float, originY: Float, showCoords: Boolean) {
        fill.color = Color.parseColor(WOOD_XQ)
        canvas.drawRect(rect, fill)
        line.strokeWidth = max(1f, cell * 0.025f)
        val w = 8 * cell
        val h = 9 * cell
        for (y in 0 until 10) {
            canvas.drawLine(originX, originY + y * cell, originX + w, originY + y * cell, line)
        }
        for (x in 0 until 9) {
            if (x == 0 || x == 8) {
                canvas.drawLine(originX + x * cell, originY, originX + x * cell, originY + h, line)
            } else {
                canvas.drawLine(originX + x * cell, originY, originX + x * cell, originY + 4 * cell, line)
                canvas.drawLine(originX + x * cell, originY + 5 * cell, originX + x * cell, originY + h, line)
            }
        }
        for (dy in intArrayOf(0, 7)) {
            canvas.drawLine(originX + 3 * cell, originY + dy * cell, originX + 5 * cell, originY + (dy + 2) * cell, line)
            canvas.drawLine(originX + 5 * cell, originY + dy * cell, originX + 3 * cell, originY + (dy + 2) * cell, line)
        }
        val riverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = Color.parseColor("#5A4020")
            textSize = cell * 0.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val midY = originY + 4.5f * cell
        canvas.drawText("楚　河", originX + 2 * cell, midY + cell * 0.18f, riverPaint)
        canvas.drawText("汉　界", originX + 6 * cell, midY + cell * 0.18f, riverPaint)
        line.strokeWidth = max(2f, cell * 0.05f)
        canvas.drawRect(
            originX - cell * 0.08f, originY - cell * 0.08f,
            originX + w + cell * 0.08f, originY + h + cell * 0.08f, line
        )
    }

    override fun drawPiece(canvas: Canvas, cell: Float, idx: Int, cx: Float, cy: Float) {
        val p = pos ?: return
        val code = engine.pieceAt(p, idx)
        if (code == 0) return
        val red = engine.isRed(code)
        val main = if (red) Color.parseColor("#A33B32") else Color.parseColor("#29231D")
        disc.color = Color.parseColor("#947045")
        canvas.drawCircle(cx, cy + cell * 0.035f, cell * 0.455f, disc)
        disc.color = Color.parseColor("#FFF0D1")
        canvas.drawCircle(cx, cy, cell * 0.44f, disc)
        ring.color = Color.parseColor("#BB9262")
        ring.strokeWidth = cell * 0.025f
        canvas.drawCircle(cx, cy, cell * 0.43f, ring)
        ring.color = main
        ring.strokeWidth = cell * 0.02f
        canvas.drawCircle(cx, cy, cell * 0.365f, ring)
        drawCharCentered(canvas, textPaint, engine.pieceLabel(code), cx, cy, cell * 0.50f, main)
    }
}

class ChessBoard(private val engine: ChessEngine) : BoardAdapter {

    var pos: ChessPosition? = null

    private val light = Paint()
    private val dark = Paint()

    override fun pieceSide(idx: Int): Int? = pos?.board?.get(idx)?.takeIf { it != 0 }?.let { if (it > 0) 0 else 1 }

    override fun columns(): Int = 8
    override fun rows(): Int = 8
    override fun intersections(): Boolean = false
    override fun flipped(): Boolean = true

    override fun colLabel(i: Int): String? = ('a' + i).toString()
    override fun rowLabel(r: Int): String? = (8 - r).toString()

    override fun drawBoard(canvas: Canvas, rect: RectF, cell: Float, originX: Float, originY: Float, showCoords: Boolean) {
        light.color = Color.parseColor("#F0D9B5")
        dark.color = Color.parseColor("#B58863")
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val p = if ((x + y) % 2 == 0) dark else light
                val sy = 7 - y
                canvas.drawRect(
                    rect.left + x * cell, rect.top + sy * cell,
                    rect.left + (x + 1) * cell, rect.top + (sy + 1) * cell, p
                )
            }
        }
    }

    override fun drawPiece(canvas: Canvas, cell: Float, idx: Int, cx: Float, cy: Float) {
        val p = pos ?: return
        val code = engine.pieceAt(p, idx)
        if (code == 0) return
        ChessPiecePainter.draw(canvas, cell, cx, cy, kotlin.math.abs(code), engine.isWhitePiece(code))
    }
}
