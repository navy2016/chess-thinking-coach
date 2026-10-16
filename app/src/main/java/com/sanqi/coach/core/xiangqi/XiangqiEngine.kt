package com.sanqi.coach.core.xiangqi

import com.sanqi.coach.core.AppliedMove
import com.sanqi.coach.core.GameEngine
import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move
import com.sanqi.coach.core.SearchResult
import kotlin.math.abs

data class XiangqiPosition(
    val board: IntArray,
    val toMove: Int
)

/**
 * 中国象棋引擎：完整规则（蹩马腿、塞象眼、炮隔子、九宫、将帅照面）、
 * 中文纵线记谱、子力估值与 alpha-beta 搜索（含吃子静态延伸）。
 */
class XiangqiEngine : GameEngine<XiangqiPosition> {

    companion object {
        const val MATE = 100000
        private val VALUES = intArrayOf(0, 10000, 200, 200, 400, 900, 450, 100)
        private val CN_NUM = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九")
    }

    override fun initialPosition(): XiangqiPosition {
        val b = IntArray(90)
        val back = intArrayOf(5, 4, 3, 2, 1, 2, 3, 4, 5)
        for (x in 0..8) {
            b[x] = back[x]
            b[81 + x] = -back[x]
        }
        b[2 * 9 + 1] = 6
        b[2 * 9 + 7] = 6
        b[7 * 9 + 1] = -6
        b[7 * 9 + 7] = -6
        for (x in intArrayOf(0, 2, 4, 6, 8)) {
            b[3 * 9 + x] = 7
            b[6 * 9 + x] = -7
        }
        return XiangqiPosition(b, 0)
    }

    override fun sideToMove(pos: XiangqiPosition): Int = pos.toMove

    override fun sideName(side: Int): String = if (side == 0) "红方" else "黑方"

    override fun copyPosition(pos: XiangqiPosition): XiangqiPosition =
        XiangqiPosition(pos.board.copyOf(), pos.toMove)

    fun pieceAt(pos: XiangqiPosition, idx: Int): Int = pos.board[idx]

    fun isRed(code: Int): Boolean = code > 0

    fun fromBoard(board: IntArray, toMove: Int): XiangqiPosition =
        XiangqiPosition(board.copyOf(), toMove)

    fun pieceLabel(code: Int): String = when (abs(code)) {
        1 -> if (code > 0) "帅" else "将"
        2 -> if (code > 0) "仕" else "士"
        3 -> if (code > 0) "相" else "象"
        4 -> "马"
        5 -> "车"
        6 -> "炮"
        7 -> if (code > 0) "兵" else "卒"
        else -> "?"
    }

    private fun inBoard(x: Int, y: Int): Boolean = x in 0..8 && y in 0..9

    private fun inPalace(x: Int, y: Int, sideRed: Boolean): Boolean =
        x in 3..5 && (if (sideRed) y in 0..2 else y in 7..9)

    private fun ownSide(y: Int, sideRed: Boolean): Boolean =
        if (sideRed) y <= 4 else y >= 5

    private val ORTHO = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1)
    )

    private val KNIGHT = arrayOf(
        intArrayOf(1, 2), intArrayOf(-1, 2), intArrayOf(1, -2), intArrayOf(-1, -2),
        intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(-2, 1), intArrayOf(-2, -1)
    )

    private fun pseudoMoves(pos: XiangqiPosition): List<Move> {
        val out = ArrayList<Move>()
        val b = pos.board
        val redTurn = pos.toMove == 0
        for (idx in b.indices) {
            val c = b[idx]
            if (c == 0 || (c > 0) != redTurn) continue
            val x = idx % 9
            val y = idx / 9
            when (abs(c)) {
                1 -> {
                    for (d in ORTHO) {
                        val nx = x + d[0]
                        val ny = y + d[1]
                        if (inPalace(nx, ny, redTurn)) addIfOk(pos, idx, ny * 9 + nx, out)
                    }
                }
                2 -> {
                    for (dx in intArrayOf(1, -1)) for (dy in intArrayOf(1, -1)) {
                        val nx = x + dx
                        val ny = y + dy
                        if (inPalace(nx, ny, redTurn)) addIfOk(pos, idx, ny * 9 + nx, out)
                    }
                }
                3 -> {
                    for (dx in intArrayOf(2, -2)) for (dy in intArrayOf(2, -2)) {
                        val nx = x + dx
                        val ny = y + dy
                        if (!inBoard(nx, ny)) continue
                        if (!ownSide(ny, redTurn)) continue
                        if (b[(y + dy / 2) * 9 + (x + dx / 2)] != 0) continue
                        addIfOk(pos, idx, ny * 9 + nx, out)
                    }
                }
                4 -> {
                    for (d in KNIGHT) {
                        val nx = x + d[0]
                        val ny = y + d[1]
                        if (!inBoard(nx, ny)) continue
                        val legX: Int
                        val legY: Int
                        if (abs(d[0]) == 2) {
                            legX = x + d[0] / 2
                            legY = y
                        } else {
                            legX = x
                            legY = y + d[1] / 2
                        }
                        if (b[legY * 9 + legX] != 0) continue
                        addIfOk(pos, idx, ny * 9 + nx, out)
                    }
                }
                5 -> {
                    for (d in ORTHO) {
                        var nx = x + d[0]
                        var ny = y + d[1]
                        while (inBoard(nx, ny)) {
                            if (b[ny * 9 + nx] == 0) {
                                out.add(Move(idx, ny * 9 + nx))
                            } else {
                                addIfOk(pos, idx, ny * 9 + nx, out)
                                break
                            }
                            nx += d[0]
                            ny += d[1]
                        }
                    }
                }
                6 -> {
                    for (d in ORTHO) {
                        var nx = x + d[0]
                        var ny = y + d[1]
                        var screen = false
                        while (inBoard(nx, ny)) {
                            val v = b[ny * 9 + nx]
                            if (!screen) {
                                if (v != 0) screen = true
                                else out.add(Move(idx, ny * 9 + nx))
                            } else if (v != 0) {
                                addIfOk(pos, idx, ny * 9 + nx, out)
                                break
                            }
                            nx += d[0]
                            ny += d[1]
                        }
                    }
                }
                7 -> {
                    val fwd = if (redTurn) 1 else -1
                    val ny = y + fwd
                    if (ny in 0..9) addIfOk(pos, idx, ny * 9 + x, out)
                    if (ownSide(y, !redTurn)) {
                        for (d in intArrayOf(1, -1)) {
                            val nx = x + d
                            if (nx in 0..8) addIfOk(pos, idx, y * 9 + nx, out)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun addIfOk(pos: XiangqiPosition, from: Int, to: Int, out: MutableList<Move>) {
        val t = pos.board[to]
        if (t == 0 || (t > 0) != (pos.board[from] > 0)) out.add(Move(from, to))
    }

    private fun kingSquare(b: IntArray, sideRed: Boolean): Int {
        val target = if (sideRed) 1 else -1
        for (i in b.indices) if (b[i] == target) return i
        return -1
    }

    /** b 上 sq 是否被 byRed 方攻击（含将帅照面与邻格短攻）。 */
    private fun isAttackedBoard(b: IntArray, sq: Int, byRed: Boolean): Boolean {
        val x = sq % 9
        val y = sq / 9
        for (d in ORTHO) {
            var cx = x + d[0]
            var cy = y + d[1]
            var screen = false
            while (cx in 0..8 && cy in 0..9) {
                val c = b[cy * 9 + cx]
                if (c != 0) {
                    if ((byRed && c > 0) || (!byRed && c < 0)) {
                        val a = abs(c)
                        if (!screen) {
                            if (a == 5) return true
                            if (a == 1) {
                                if (abs(cx - x) + abs(cy - y) == 1) return true
                                if (d[0] == 0) return true
                            }
                        } else {
                            if (a == 6) return true
                            break
                        }
                    }
                    if (!screen) screen = true else break
                }
                cx += d[0]
                cy += d[1]
            }
        }
        for (d in KNIGHT) {
            val nx = x + d[0]
            val ny = y + d[1]
            if (!inBoard(nx, ny)) continue
            val c = b[ny * 9 + nx]
            if (c == 0 || abs(c) != 4) continue
            if ((c > 0) != byRed) continue
            val legX: Int
            val legY: Int
            if (abs(d[0]) == 2) {
                legX = x + d[0] / 2
                legY = y
            } else {
                legX = x
                legY = y + d[1] / 2
            }
            if (b[legY * 9 + legX] == 0) return true
        }
        for (d in ORTHO) {
            val nx = x + d[0]
            val ny = y + d[1]
            if (!inBoard(nx, ny)) continue
            val c = b[ny * 9 + nx]
            if (c == 0 || abs(c) != 7) continue
            if ((c > 0) != byRed) continue
            if (byRed) {
                if (nx == x && ny == y - 1) return true
                if (ny == y && ny >= 5) return true
            } else {
                if (nx == x && ny == y + 1) return true
                if (ny == y && ny <= 4) return true
            }
        }
        return false
    }

    private fun inCheck(pos: XiangqiPosition): Boolean {
        val k = kingSquare(pos.board, pos.toMove == 0)
        if (k < 0) return true
        return isAttackedBoard(pos.board, k, pos.toMove == 1)
    }

    private fun isLegalAfter(pos: XiangqiPosition, move: Move): Boolean {
        val b = pos.board.copyOf()
        b[move.to] = b[move.from]
        b[move.from] = 0
        val k = if (abs(b[move.to]) == 1) move.to else kingSquare(b, pos.toMove == 0)
        if (k < 0) return false
        return !isAttackedBoard(b, k, pos.toMove == 1)
    }

    override fun legalMoves(pos: XiangqiPosition): List<Move> {
        val out = ArrayList<Move>()
        for (m in pseudoMoves(pos)) if (isLegalAfter(pos, m)) out.add(m)
        return out
    }

    override fun applyMove(pos: XiangqiPosition, move: Move): AppliedMove<XiangqiPosition> {
        val b = pos.board.copyOf()
        b[move.to] = b[move.from]
        b[move.from] = 0
        val newPos = XiangqiPosition(b, pos.toMove xor 1)
        val moverRed = pos.toMove == 0
        val captured = if (pos.board[move.to] != 0) 1 else 0
        val enemyKing = kingSquare(b, !moverRed)
        val check = enemyKing >= 0 && isAttackedBoard(b, enemyKing, moverRed)
        val enemyHasMoves = legalMoves(newPos).isNotEmpty()
        val status = when {
            check && !enemyHasMoves -> GameStatus.CHECKMATE
            !check && !enemyHasMoves -> GameStatus.STALEMATE
            check -> GameStatus.CHECK
            else -> GameStatus.ONGOING
        }
        return AppliedMove(newPos, notation(pos, move), captured, check, status)
    }

    private fun lineOf(x: Int, red: Boolean): String =
        if (red) CN_NUM[8 - x] else (x + 1).toString()

    override fun notation(pos: XiangqiPosition, move: Move): String {
        val code = pos.board[move.from]
        val red = code > 0
        val a = abs(code)
        val label = pieceLabel(code)
        val fromX = move.from % 9
        val fromY = move.from / 9
        val toX = move.to % 9
        val toY = move.to / 9

        var prefix = ""
        if (a in intArrayOf(4, 5, 6, 7)) {
            val sameCol = ArrayList<Int>()
            for (yy in 0..9) {
                val sq = yy * 9 + fromX
                if (sq != move.from && pos.board[sq] == code) sameCol.add(yy)
            }
            if (sameCol.isNotEmpty()) {
                val ys = sameCol + fromY
                val sorted = if (red) ys.sortedDescending() else ys.sorted()
                val rank = sorted.indexOf(fromY)
                prefix = when {
                    sorted.size == 2 -> if (rank == 0) "前" else "后"
                    sorted.size == 3 -> arrayOf("前", "中", "后")[rank]
                    else -> if (rank == 0) "前" else "后"
                }
            }
        }

        val verb: String
        if (fromX == toX) {
            val steps = abs(toY - fromY)
            val forward = if (red) toY > fromY else toY < fromY
            val dir = if (forward) "进" else "退"
            verb = if (a == 2 || a == 3 || a == 4) dir + lineOf(toX, red) else dir + steps
        } else if (fromY == toY) {
            verb = "平" + lineOf(toX, red)
        } else {
            val forward = if (red) toY > fromY else toY < fromY
            verb = (if (forward) "进" else "退") + lineOf(toX, red)
        }

        return if (prefix.isEmpty()) label + lineOf(fromX, red) + verb else prefix + label + verb
    }

    override fun squareDesc(pos: XiangqiPosition, square: Int): String =
        lineOf(square % 9, pos.toMove == 0) + "路"

    override fun evaluate(pos: XiangqiPosition): Int {
        var s = 0
        for (i in pos.board.indices) {
            val c = pos.board[i]
            if (c == 0) continue
            val a = abs(c)
            var v = VALUES[a]
            val x = i % 9
            val y = i / 9
            if (a == 7) {
                val crossed = if (c > 0) y >= 5 else y <= 4
                if (crossed) {
                    v = 200
                    if (x == 4) v += 20
                }
            }
            if (a == 4 || a == 6) {
                if (x in 3..5) v += 10
            }
            s += if (c > 0) v else -v
        }
        return if (pos.toMove == 0) s else -s
    }

    private fun applyForSearch(pos: XiangqiPosition, move: Move): XiangqiPosition {
        val b = pos.board.copyOf()
        b[move.to] = b[move.from]
        b[move.from] = 0
        return XiangqiPosition(b, pos.toMove xor 1)
    }

    private fun orderMoves(pos: XiangqiPosition, moves: List<Move>): List<Move> =
        moves.sortedByDescending { m ->
            val victim = abs(pos.board[m.to])
            if (victim == 0) 0 else victim * 10 - abs(pos.board[m.from])
        }

    private fun quiescence(pos: XiangqiPosition, alpha: Int, beta: Int, qd: Int): Int {
        val stand = evaluate(pos)
        if (stand >= beta) return beta
        var a = if (stand > alpha) stand else alpha
        if (qd >= 4) return a
        val caps = legalMoves(pos).filter { pos.board[it.to] != 0 }
        for (m in orderMoves(pos, caps)) {
            val sc = -quiescence(applyForSearch(pos, m), -beta, -a, qd + 1)
            if (sc >= beta) return beta
            if (sc > a) a = sc
        }
        return a
    }

    private fun negamax(pos: XiangqiPosition, depth: Int, alpha: Int, beta: Int, ply: Int): Int {
        val moves = legalMoves(pos)
        if (moves.isEmpty()) return if (inCheck(pos)) -MATE + ply else 0
        if (depth <= 0) return quiescence(pos, alpha, beta, 0)
        var a = alpha
        var best = -Int.MAX_VALUE
        for (m in orderMoves(pos, moves)) {
            val sc = -negamax(applyForSearch(pos, m), depth - 1, -beta, -a, ply + 1)
            if (sc > best) best = sc
            if (best > a) a = best
            if (a >= beta) break
        }
        return best
    }

    override fun searchBest(pos: XiangqiPosition, depth: Int): SearchResult {
        val moves = legalMoves(pos)
        if (moves.isEmpty()) return SearchResult(null, if (inCheck(pos)) -MATE else 0)
        var bestMove = moves[0]
        var bestScore = -Int.MAX_VALUE
        var alpha = -MATE * 2
        for (m in orderMoves(pos, moves)) {
            val sc = -negamax(applyForSearch(pos, m), depth - 1, -MATE * 2, -alpha, 1)
            if (sc > bestScore) {
                bestScore = sc
                bestMove = m
            }
            if (sc > alpha) alpha = sc
        }
        return SearchResult(bestMove, bestScore, listOf(bestMove))
    }
}
