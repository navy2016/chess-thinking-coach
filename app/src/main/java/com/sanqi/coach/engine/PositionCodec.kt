package com.sanqi.coach.engine

import com.sanqi.coach.core.Move
import com.sanqi.coach.core.chess.ChessPosition
import com.sanqi.coach.core.xiangqi.XiangqiPosition

object PositionCodec {
    fun uci(move: Move, kind: String, oneBasedXiangqi: Boolean = false): String {
        val width = if (kind == "xiangqi") 9 else 8
        val base = if (kind == "xiangqi" && !oneBasedXiangqi) 0 else 1
        fun square(index: Int): String = "${'a' + index % width}${index / width + base}"
        val promotion = if (kind == "chess") when (move.promo) { 2 -> "q"; 3 -> "r"; 4 -> "b"; 5 -> "n"; else -> "" } else ""
        return square(move.from) + square(move.to) + promotion
    }

    fun parseUci(value: String, kind: String, oneBasedXiangqi: Boolean = false): Move {
        if (kind == "xiangqi" && oneBasedXiangqi) {
            val match = Regex("([a-i])(10|[1-9])([a-i])(10|[1-9])").matchEntire(value)
                ?: throw EngineException(EngineProblem.PROTOCOL)
            fun square(file: Int, rank: Int) = (match.groupValues[rank].toInt() - 1) * 9 + (match.groupValues[file][0] - 'a')
            return Move(square(1, 2), square(3, 4))
        }
        val width = if (kind == "xiangqi") 9 else 8
        val base = if (kind == "xiangqi" && !oneBasedXiangqi) 0 else 1
        if (value.length !in 4..5) throw EngineException(EngineProblem.PROTOCOL)
        fun square(offset: Int): Int {
            val x = value[offset] - 'a'
            val y = value[offset + 1] - '0' - base
            if (x !in 0 until width || y !in 0 until (if (width == 9) 10 else 8)) throw EngineException(EngineProblem.PROTOCOL)
            return y * width + x
        }
        val promotion = if (value.length == 5) when (value[4]) { 'q' -> 2; 'r' -> 3; 'b' -> 4; 'n' -> 5; else -> throw EngineException(EngineProblem.PROTOCOL) } else 0
        return Move(square(0), square(2), promotion)
    }

    fun fen(position: Any): String {
        val chess = position as? ChessPosition
        val xiangqi = position as? XiangqiPosition
        val board = chess?.board ?: xiangqi?.board ?: throw EngineException(EngineProblem.PROTOCOL)
        val width = if (chess != null) 8 else 9
        val height = if (chess != null) 8 else 10
        val letters = if (chess != null) "?kqrbnp" else "?kabnrcp"
        val ranks = (height - 1 downTo 0).map { y ->
            buildString {
                var empty = 0
                for (x in 0 until width) {
                    val code = board[y * width + x]
                    if (code == 0) empty++ else {
                        if (empty > 0) { append(empty); empty = 0 }
                        val letter = letters[kotlin.math.abs(code)]
                        append(if (code > 0) letter.uppercaseChar() else letter)
                    }
                }
                if (empty > 0) append(empty)
            }
        }.joinToString("/")
        val side = if (chess?.whiteToMove ?: (xiangqi!!.toMove == 0)) "w" else "b"
        val castles = if (chess != null) buildString {
            for ((bit, char) in listOf(1 to 'K', 2 to 'Q', 4 to 'k', 8 to 'q')) if (chess.castling and bit != 0) append(char)
        }.ifEmpty { "-" } else "-"
        val ep = if (chess != null && chess.ep >= 0) "${'a' + chess.ep % 8}${chess.ep / 8 + 1}" else "-"
        return "$ranks $side $castles $ep ${chess?.halfmove ?: 0} 1"
    }

    fun go(move: Move, size: Int): String {
        if (move == Move(-1, -1)) return "pass"
        val x = move.to % size
        return "${'A' + x + if (x >= 8) 1 else 0}${size - move.to / size}"
    }

    fun parseGo(value: String, size: Int): Move {
        if (value.equals("pass", true)) return Move(-1, -1)
        val column = value.firstOrNull()?.uppercaseChar() ?: throw EngineException(EngineProblem.PROTOCOL)
        val x = column - 'A' - if (column > 'I') 1 else 0
        val row = value.drop(1).toIntOrNull() ?: throw EngineException(EngineProblem.PROTOCOL)
        if (column == 'I' || x !in 0 until size || row !in 1..size) throw EngineException(EngineProblem.PROTOCOL)
        val index = (size - row) * size + x
        return Move(index, index)
    }
}
