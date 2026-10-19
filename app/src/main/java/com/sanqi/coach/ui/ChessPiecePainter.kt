package com.sanqi.coach.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

object ChessPiecePainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pawn = Path().apply {
        moveTo(38f, 38f); cubicTo(43f, 49f, 39f, 58f, 30f, 73f)
        lineTo(70f, 73f); cubicTo(61f, 58f, 57f, 49f, 62f, 38f); close()
        addCircle(50f, 27f, 14f, Path.Direction.CW)
    }
    private val rook = Path().apply {
        moveTo(23f, 14f); lineTo(35f, 14f); lineTo(35f, 25f); lineTo(44f, 25f)
        lineTo(44f, 14f); lineTo(56f, 14f); lineTo(56f, 25f); lineTo(65f, 25f)
        lineTo(65f, 14f); lineTo(77f, 14f); lineTo(77f, 35f); lineTo(65f, 41f)
        lineTo(64f, 65f); lineTo(72f, 76f); lineTo(28f, 76f); lineTo(36f, 65f)
        lineTo(35f, 41f); lineTo(23f, 35f); close()
    }
    private val bishop = Path().apply {
        moveTo(50f, 10f); cubicTo(26f, 28f, 25f, 42f, 41f, 51f)
        cubicTo(43f, 59f, 35f, 69f, 28f, 75f); lineTo(72f, 75f)
        cubicTo(65f, 69f, 57f, 59f, 59f, 51f); cubicTo(75f, 42f, 74f, 28f, 50f, 10f); close()
    }
    private val knight = Path().apply {
        moveTo(30f, 77f); cubicTo(33f, 62f, 55f, 51f, 48f, 39f)
        lineTo(33f, 49f); lineTo(18f, 44f); lineTo(19f, 35f); lineTo(40f, 17f)
        lineTo(39f, 7f); lineTo(50f, 13f); lineTo(58f, 8f); lineTo(61f, 21f)
        cubicTo(82f, 36f, 77f, 57f, 76f, 77f); close()
    }
    private val queen = Path().apply {
        moveTo(28f, 59f); lineTo(18f, 25f); lineTo(35f, 43f); lineTo(34f, 17f)
        lineTo(45f, 39f); lineTo(50f, 11f); lineTo(55f, 39f); lineTo(66f, 17f)
        lineTo(65f, 43f); lineTo(82f, 25f); lineTo(72f, 59f); lineTo(67f, 67f)
        lineTo(74f, 77f); lineTo(26f, 77f); lineTo(33f, 67f); close()
    }
    private val king = Path().apply {
        moveTo(45f, 7f); lineTo(55f, 7f); lineTo(55f, 17f); lineTo(65f, 17f)
        lineTo(65f, 25f); lineTo(55f, 25f); lineTo(55f, 34f)
        cubicTo(80f, 19f, 83f, 47f, 66f, 57f); lineTo(64f, 67f); lineTo(73f, 77f)
        lineTo(27f, 77f); lineTo(36f, 67f); lineTo(34f, 57f)
        cubicTo(17f, 47f, 20f, 19f, 45f, 34f); lineTo(45f, 25f)
        lineTo(35f, 25f); lineTo(35f, 17f); lineTo(45f, 17f); close()
    }
    private val base = Path().apply {
        moveTo(26f, 77f); lineTo(74f, 77f); lineTo(80f, 87f); lineTo(20f, 87f); close()
    }

    fun draw(canvas: Canvas, cell: Float, cx: Float, cy: Float, piece: Int, white: Boolean) {
        val shape = when (piece) { 1 -> king; 2 -> queen; 3 -> rook; 4 -> bishop; 5 -> knight; else -> pawn }
        val fill = if (white) Color.parseColor("#FFFDF7") else Color.parseColor("#211C18")
        val edge = if (white) Color.parseColor("#352C24") else Color.parseColor("#0E0C0A")
        canvas.save()
        canvas.translate(cx - cell * 0.46f, cy - cell * 0.46f)
        canvas.scale(cell * 0.0092f, cell * 0.0092f)
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawPath(shape, paint)
        canvas.drawPath(base, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.6f
        paint.color = edge
        canvas.drawPath(shape, paint)
        canvas.drawPath(base, paint)
        paint.color = if (white) edge else Color.parseColor("#C5AF92")
        paint.strokeWidth = 2.2f
        canvas.drawLine(31f, 82f, 69f, 82f, paint)
        if (piece == 1 || piece == 2) canvas.drawLine(36f, 65f, 64f, 65f, paint)
        if (piece == 3) canvas.drawLine(36f, 42f, 64f, 42f, paint)
        if (piece == 4) canvas.drawLine(56f, 24f, 45f, 40f, paint)
        if (piece == 5) {
            canvas.drawLine(60f, 30f, 65f, 52f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(42f, 28f, 2.3f, paint)
        }
        canvas.restore()
    }
}
