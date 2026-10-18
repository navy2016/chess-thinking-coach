package com.sanqi.coach.core.go

import com.sanqi.coach.core.AppliedMove
import com.sanqi.coach.core.SearchSupport
import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move
import com.sanqi.coach.core.SearchResult
import java.util.Locale
import kotlin.math.abs

data class GoPosition(
    val size: Int,
    val board: IntArray,
    val toMove: Int,
    val captures: IntArray,
    val koPoint: Int,
    val consecutivePasses: Int
)

data class GoScore(val black: Double, val white: Double, val winnerText: String)

/**
 * 围棋引擎：中国规则（数子法），支持提子、自杀禁着、简单劫、PASS 与终局计分。
 * evaluate/searchBest 为启发式，用于教练流程的粗评与应手。
 */
class GoEngine(private val size: Int = 19, val komi: Double = 7.5) : SearchSupport<GoPosition> {

    override val heuristic: Boolean = true

    override fun initialPosition(): GoPosition =
        GoPosition(size, IntArray(size * size), 0, IntArray(2), -1, 0)

    override fun sideToMove(pos: GoPosition): Int = pos.toMove

    override fun sideName(side: Int): String = if (side == 0) "黑棋" else "白棋"

    override fun copyPosition(pos: GoPosition): GoPosition =
        GoPosition(pos.size, pos.board.copyOf(), pos.toMove, pos.captures.copyOf(), pos.koPoint, pos.consecutivePasses)

    fun stoneAt(pos: GoPosition, idx: Int): Int = pos.board[idx]

    private fun neighbors(boardSize: Int, idx: Int): List<Int> {
        val x = idx % boardSize
        val y = idx / boardSize
        val out = ArrayList<Int>(4)
        if (x > 0) out.add(idx - 1)
        if (x < boardSize - 1) out.add(idx + 1)
        if (y > 0) out.add(idx - boardSize)
        if (y < boardSize - 1) out.add(idx + boardSize)
        return out
    }

    private fun groupOf(board: IntArray, boardSize: Int, idx: Int): Pair<MutableSet<Int>, MutableSet<Int>> {
        val color = board[idx]
        val group = HashSet<Int>()
        val libs = HashSet<Int>()
        val stack = ArrayDeque<Int>()
        stack.addLast(idx)
        group.add(idx)
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            for (nb in neighbors(boardSize, c)) {
                val v = board[nb]
                if (v == 0) libs.add(nb)
                else if (v == color && group.add(nb)) stack.addLast(nb)
            }
        }
        return Pair(group, libs)
    }

    /** 试放一子，返回（新盘面, 被提子集合）；非法（占用/自杀）返回 null。不检查劫。 */
    private fun tryPlace(pos: GoPosition, idx: Int, color: Int): Pair<IntArray, Set<Int>>? {
        if (pos.board[idx] != 0) return null
        val board = pos.board.copyOf()
        board[idx] = color
        val enemy = if (color == 1) 2 else 1
        val captured = HashSet<Int>()
        for (nb in neighbors(pos.size, idx)) {
            if (board[nb] == enemy && !captured.contains(nb)) {
                val (group, libs) = groupOf(board, pos.size, nb)
                if (libs.isEmpty()) {
                    for (s in group) {
                        board[s] = 0
                        captured.add(s)
                    }
                }
            }
        }
        val (own, ownLibs) = groupOf(board, pos.size, idx)
        if (ownLibs.isEmpty()) return null
        return Pair(board, captured)
    }

    override fun legalMoves(pos: GoPosition): List<Move> {
        if (pos.consecutivePasses >= 2) return emptyList()
        val out = ArrayList<Move>()
        val color = pos.toMove + 1
        for (idx in 0 until pos.size * pos.size) {
            if (idx == pos.koPoint) continue
            if (tryPlace(pos, idx, color) != null) out.add(Move(idx, idx, 0))
        }
        out.add(Move(-1, -1))
        return out
    }

    fun isMoveLegal(pos: GoPosition, move: Move): Boolean {
        if (pos.consecutivePasses >= 2) return false
        if (move == Move(-1, -1)) return true
        if (move.from != move.to || move.to !in pos.board.indices || move.promo != 0) return false
        if (move.to == pos.koPoint) return false
        return tryPlace(pos, move.to, pos.toMove + 1) != null
    }

    override fun searchPosition(pos: GoPosition, move: Move): GoPosition = applyMove(pos, move).position
    override fun inCheck(pos: GoPosition): Boolean = false
    override fun isTactical(pos: GoPosition, move: Move): Boolean = false
    override fun terminalScore(pos: GoPosition, moves: List<Move>, ply: Int): Int? =
        if (pos.consecutivePasses >= 2) evaluate(pos) else null

    override fun applyMove(pos: GoPosition, move: Move): AppliedMove<GoPosition> {
        if (move.from < 0 || move.to < 0) {
            val np = GoPosition(
                pos.size, pos.board.copyOf(), pos.toMove xor 1, pos.captures.copyOf(), -1,
                pos.consecutivePasses + 1
            )
            return AppliedMove(
                np, (if (pos.toMove == 0) "黑" else "白") + "停一手",
                0, false, GameStatus.ONGOING
            )
        }
        val placed = tryPlace(pos, move.to, pos.toMove + 1)
            ?: throw IllegalArgumentException("非法落子: $move")
        val board = placed.first
        val captured = placed.second
        val captures = pos.captures.copyOf()
        captures[pos.toMove] += captured.size
        var ko = -1
        if (captured.size == 1) {
            val (group, libs) = groupOf(board, pos.size, move.to)
            if (group.size == 1 && libs.size == 1) ko = captured.first()
        }
        val np = GoPosition(pos.size, board, pos.toMove xor 1, captures, ko, 0)
        return AppliedMove(
            np,
            (if (pos.toMove == 0) "黑 " else "白 ") + coord(pos.size, move.to),
            captured.size, false, GameStatus.ONGOING
        )
    }

    private fun coord(boardSize: Int, idx: Int): String {
        val x = idx % boardSize
        val y = idx / boardSize
        val col = if (x < 8) ('A' + x) else ('A' + x + 1)
        return col.toString() + (boardSize - y)
    }

    override fun squareDesc(pos: GoPosition, square: Int): String = coord(pos.size, square)

    fun notation(pos: GoPosition, move: Move): String {
        if (move.from < 0) return (if (pos.toMove == 0) "黑" else "白") + "停一手"
        return (if (pos.toMove == 0) "黑 " else "白 ") + coord(pos.size, move.to)
    }

    /** 黑白各自的（活子数 + 单色地域），中立点不计。 */
    private fun territoryCount(pos: GoPosition, bounded: Boolean = false): Pair<Int, Int> {
        val n = pos.size
        val visited = BooleanArray(n * n)
        var black = 0
        var white = 0
        for (i in pos.board.indices) {
            if (pos.board[i] == 1) black++
            else if (pos.board[i] == 2) white++
        }
        for (i in pos.board.indices) {
            if (pos.board[i] != 0 || visited[i]) continue
            val region = ArrayList<Int>()
            val stack = ArrayDeque<Int>()
            stack.addLast(i)
            visited[i] = true
            var touchBlack = false
            var touchWhite = false
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                region.add(c)
                for (nb in neighbors(n, c)) {
                    val v = pos.board[nb]
                    if (v == 1) touchBlack = true
                    else if (v == 2) touchWhite = true
                    else if (!visited[nb]) {
                        visited[nb] = true
                        stack.addLast(nb)
                    }
                }
            }
            if (!bounded || region.size <= pos.size) {
                if (touchBlack && !touchWhite) black += region.size
                else if (touchWhite && !touchBlack) white += region.size
            }
        }
        return Pair(black, white)
    }

    /** 中国规则数子法：先移除死子，再按活子 + 单色地域计分，白加贴目。 */
    fun scoreArea(pos: GoPosition, dead: Set<Int>): GoScore {
        val board = pos.board.copyOf()
        for (d in dead) {
            if (d in board.indices && board[d] != 0) board[d] = 0
        }
        val cleaned = GoPosition(pos.size, board, pos.toMove, pos.captures, pos.koPoint, pos.consecutivePasses)
        val (b, w) = territoryCount(cleaned)
        val blackPts = b.toDouble()
        val whitePts = w + komi
        val diff = blackPts - whitePts
        val winnerText = when {
            diff > 0 -> "黑胜" + fmtNum(diff / 2) + "子"
            diff < 0 -> "白胜" + fmtNum(-diff / 2) + "子"
            else -> "和棋"
        }
        return GoScore(blackPts, whitePts, winnerText)
    }

    private fun fmtNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString()
        else String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')

    override fun evaluate(pos: GoPosition): Int {
        val (black, white) = territoryCount(pos, pos.consecutivePasses < 2)
        var score = ((black - white - komi) * 100).toInt()
        if (pos.consecutivePasses < 2) {
            val seen = HashSet<Int>()
            for (idx in pos.board.indices) {
                if (pos.board[idx] == 0 || idx in seen) continue
                val (group, liberties) = groupOf(pos.board, pos.size, idx)
                seen.addAll(group)
                val safety = minOf(liberties.size, 4) * 8 - if (liberties.size == 1) group.size * 85 else 0
                score += if (pos.board[idx] == 1) safety else -safety
            }
        }
        return if (pos.toMove == 0) score else -score
    }

    override fun searchMoves(pos: GoPosition): List<Move> {
        if (pos.consecutivePasses >= 2) return emptyList()
        val color = pos.toMove + 1
        val enemyColor = color xor 3
        val b = pos.board

        val atariOwn = HashMap<Int, Int>()
        val atariEnemy = HashMap<Int, Int>()
        val seen = HashSet<Int>()
        for (i in b.indices) {
            if (b[i] != 0 && !seen.contains(i)) {
                val (g, libs) = groupOf(b, pos.size, i)
                seen.addAll(g)
                if (libs.size == 1) {
                    val lib = libs.first()
                    if (b[i] == color) atariOwn[lib] = g.size else atariEnemy[lib] = g.size
                }
            }
        }

        val candidates = ArrayList<Pair<Move, Int>>()
        for (idx in 0 until pos.size * pos.size) {
            if (idx == pos.koPoint) continue
            val placed = tryPlace(pos, idx, color) ?: continue
            val board2 = placed.first
            val captured = placed.second
            var s = captured.size * 120
            atariOwn[idx]?.let { s += 100 + it * 10 }
            atariEnemy[idx]?.let { s += 40 }
            for (nb in neighbors(pos.size, idx)) {
                if (board2[nb] == enemyColor) {
                    val (g2, l2) = groupOf(board2, pos.size, nb)
                    if (l2.size == 1) {
                        s += 60 + g2.size * 5
                        break
                    }
                }
            }
            var touch = 0
            var ownNear = 0
            for (nb in neighbors(pos.size, idx)) {
                when (b[nb]) {
                    enemyColor -> touch++
                    color -> ownNear++
                }
            }
            s += touch * 12 + ownNear * 6
            val x = idx % pos.size
            val y = idx / pos.size
            val edge = minOf(x, y, pos.size - 1 - x, pos.size - 1 - y)
            s += when (edge) {
                0 -> -30
                1 -> 10
                2 -> 45
                else -> 25
            }
            var minDist = 999
            for (j in b.indices) {
                if (b[j] != 0) {
                    val d = abs(j % pos.size - x) + abs(j / pos.size - y)
                    if (d < minDist) minDist = d
                }
            }
            if (minDist in 2..4) s += 30 - minDist * 4
            if (minDist in 7..998) s -= (minDist - 6) * 3
            candidates.add(Move(idx, idx, 0) to s)
        }
        return candidates.sortedByDescending { it.second }.take(8).map { it.first } + Move(-1, -1)
    }

    override fun searchBest(pos: GoPosition, depth: Int): SearchResult {
        fun search(p: GoPosition, remaining: Int, alpha: Int, beta: Int): SearchResult {
            if (p.consecutivePasses >= 2 || remaining <= 0) return SearchResult(null, evaluate(p))
            var best: Move? = null
            var score = -200000
            var line: List<Move> = emptyList()
            var a = alpha
            for (move in searchMoves(p)) {
                val reply = search(searchPosition(p, move), remaining - 1, -beta, -a)
                val value = -reply.score
                if (value > score) {
                    best = move
                    score = value
                    line = listOf(move) + reply.pv
                }
                a = maxOf(a, score)
                if (a >= beta) break
            }
            return SearchResult(best, score, line)
        }
        return search(pos, depth.coerceIn(1, 4), -200000, 200000)
    }
}
