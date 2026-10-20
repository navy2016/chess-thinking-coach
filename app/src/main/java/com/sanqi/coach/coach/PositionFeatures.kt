package com.sanqi.coach.coach

import com.sanqi.coach.core.chess.ChessEngine
import com.sanqi.coach.core.chess.ChessPosition
import com.sanqi.coach.core.go.GoEngine
import com.sanqi.coach.core.go.GoPosition
import com.sanqi.coach.core.xiangqi.XiangqiEngine
import com.sanqi.coach.core.xiangqi.XiangqiPosition
import kotlin.math.abs

enum class TeachingFeature {
    PIECES, KING_ATTACKED, KING_ZONE_ATTACKS, CENTER_OCCUPANCY, CENTER_CONTROL, MINORS_OFF_HOME,
    ROOK_CANNON_MOVES, BLOCKED_HORSE_LEGS, GO_GROUPS, GO_ATARI_GROUPS, GO_FOCUS_LIBERTIES, GO_CAPTURABLE_STONES
}

data class FeatureSnapshot(val kind: String, val side: Int, val values: Map<TeachingFeature, Int>)

object PositionFeatures {
    fun read(position: Any, side: Int, focus: Int): FeatureSnapshot = when (position) {
        is ChessPosition -> chess(position, side)
        is XiangqiPosition -> xiangqi(position, side)
        is GoPosition -> go(position, side, focus)
        else -> FeatureSnapshot("", side, emptyMap())
    }

    private fun chess(pos: ChessPosition, side: Int): FeatureSnapshot {
        val engine = ChessEngine()
        val sign = if (side == 0) 1 else -1
        val king = pos.board.indexOf(sign)
        val centers = listOf(27, 28, 35, 36)
        val home = if (side == 0) listOf(1, 2, 5, 6) else listOf(57, 58, 61, 62)
        val zone = if (king < 0) emptyList() else pos.board.indices.filter {
            abs(it % 8 - king % 8) <= 1 && abs(it / 8 - king / 8) <= 1
        }
        return FeatureSnapshot("chess", side, mapOf(
            TeachingFeature.PIECES to pos.board.count { it * sign > 0 },
            TeachingFeature.KING_ATTACKED to if (king >= 0 && engine.isSquareAttacked(pos, king, 1 - side)) 1 else 0,
            TeachingFeature.KING_ZONE_ATTACKS to zone.count { engine.isSquareAttacked(pos, it, 1 - side) },
            TeachingFeature.CENTER_OCCUPANCY to centers.count { pos.board[it] * sign > 0 },
            TeachingFeature.CENTER_CONTROL to centers.count { engine.isSquareAttacked(pos, it, side) },
            TeachingFeature.MINORS_OFF_HOME to pos.board.indices.count {
                pos.board[it] * sign > 0 && abs(pos.board[it]) in 4..5 && it !in home
            }
        ))
    }

    private fun xiangqi(pos: XiangqiPosition, side: Int): FeatureSnapshot {
        val engine = XiangqiEngine()
        val sign = if (side == 0) 1 else -1
        val mine = pos.copy(toMove = side)
        val mobile = engine.legalMoves(mine).count { abs(pos.board[it.from]) in 5..6 }
        var legs = 0
        for (square in pos.board.indices.filter { pos.board[it] == 4 * sign }) {
            val x = square % 9
            val y = square / 9
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0..8 && ny in 0..9 && pos.board[ny * 9 + nx] != 0) legs++
            }
        }
        return FeatureSnapshot("xiangqi", side, mapOf(
            TeachingFeature.PIECES to pos.board.count { it * sign > 0 },
            TeachingFeature.KING_ATTACKED to if (engine.inCheck(mine)) 1 else 0,
            TeachingFeature.ROOK_CANNON_MOVES to mobile,
            TeachingFeature.BLOCKED_HORSE_LEGS to legs
        ))
    }

    private fun neighbors(index: Int, size: Int): List<Int> = buildList {
        if (index % size > 0) add(index - 1)
        if (index % size < size - 1) add(index + 1)
        if (index >= size) add(index - size)
        if (index < size * (size - 1)) add(index + size)
    }

    private fun go(pos: GoPosition, side: Int, focus: Int): FeatureSnapshot {
        val seen = mutableSetOf<Int>()
        val groups = mutableListOf<Pair<Set<Int>, Set<Int>>>()
        for (start in pos.board.indices) {
            if (pos.board[start] != side + 1 || !seen.add(start)) continue
            val stones = mutableSetOf(start)
            val liberties = mutableSetOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            while (queue.isNotEmpty()) {
                for (next in neighbors(queue.removeFirst(), pos.size)) {
                    when (pos.board[next]) {
                        0 -> liberties.add(next)
                        side + 1 -> if (seen.add(next)) { stones.add(next); queue.add(next) }
                    }
                }
            }
            groups.add(stones to liberties)
        }
        val legal = if (pos.toMove != side && groups.any { it.second.size == 1 })
            GoEngine(pos.size).legalMoves(pos).map { it.to }.toSet() else emptySet()
        val values = mutableMapOf(
            TeachingFeature.PIECES to pos.board.count { it == side + 1 },
            TeachingFeature.GO_GROUPS to groups.size,
            TeachingFeature.GO_ATARI_GROUPS to groups.count { it.second.size == 1 }
        )
        groups.firstOrNull { focus in it.first }?.let { values[TeachingFeature.GO_FOCUS_LIBERTIES] = it.second.size }
        if (pos.toMove != side) values[TeachingFeature.GO_CAPTURABLE_STONES] =
            groups.filter { it.second.size == 1 && it.second.first() in legal }.sumOf { it.first.size }
        return FeatureSnapshot("go", side, values)
    }
}
