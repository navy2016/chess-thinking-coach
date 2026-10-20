package com.sanqi.coach.core.chess

import com.sanqi.coach.core.AppliedMove
import com.sanqi.coach.core.SearchSupport
import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move
import com.sanqi.coach.core.SearchResult
import kotlin.math.abs

data class ChessPosition(
    val board: IntArray,
    val whiteToMove: Boolean,
    val castling: Int,
    val ep: Int,
    val halfmove: Int
)

/**
 * 国际象棋引擎：完整规则（王车易位、吃过路兵、升变、逼和、子力不足、50 步）、
 * SAN 记谱、子力+位置估值、alpha-beta 搜索（含吃子静态延伸）。
 * 棋子编码：白 1王 2后 3车 4象 5马 6兵（正数），黑为负。
 */
class ChessEngine : SearchSupport<ChessPosition> {

    companion object {
        const val MATE = 100000
        private val VALUES = intArrayOf(0, 0, 900, 500, 330, 320, 100)
        private val LETTERS = charArrayOf('?', 'K', 'Q', 'R', 'B', 'N', 'P')
        private val KNIGHT_D = arrayOf(
            intArrayOf(1, 2), intArrayOf(-1, 2), intArrayOf(1, -2), intArrayOf(-1, -2),
            intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(-2, 1), intArrayOf(-2, -1)
        )
        private val ORTHO = arrayOf(
            intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1)
        )
        private val DIAG = arrayOf(
            intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1)
        )
    }

    override fun initialPosition(): ChessPosition {
        val b = IntArray(64)
        val back = intArrayOf(3, 5, 4, 2, 1, 4, 5, 3)
        for (x in 0..7) {
            b[x] = back[x]
            b[8 + x] = 6
            b[48 + x] = -6
            b[56 + x] = -back[x]
        }
        return ChessPosition(b, true, 15, -1, 0)
    }

    override fun sideToMove(pos: ChessPosition): Int = if (pos.whiteToMove) 0 else 1

    override fun sideName(side: Int): String = if (side == 0) "白方" else "黑方"

    override fun copyPosition(pos: ChessPosition): ChessPosition =
        ChessPosition(pos.board.copyOf(), pos.whiteToMove, pos.castling, pos.ep, pos.halfmove)

    fun pieceAt(pos: ChessPosition, idx: Int): Int = pos.board[idx]

    fun isWhitePiece(code: Int): Boolean = code > 0

    fun pieceLetter(code: Int): Char = LETTERS[abs(code)]

    fun fromBoard(board: IntArray, whiteToMove: Boolean, castling: Int = 0, ep: Int = -1): ChessPosition =
        ChessPosition(board.copyOf(), whiteToMove, castling, ep, 0)

    private fun inBoard(x: Int, y: Int): Boolean = x in 0..7 && y in 0..7

    private fun isCastleMove(pos: ChessPosition, move: Move): Boolean =
        abs(pos.board[move.from]) == 1 && abs(move.to - move.from) == 2

    private fun pseudoMoves(pos: ChessPosition): List<Move> {
        val out = ArrayList<Move>()
        val b = pos.board
        val white = pos.whiteToMove
        for (idx in b.indices) {
            val c = b[idx]
            if (c == 0 || (c > 0) != white) continue
            val x = idx % 8
            val y = idx / 8
            when (abs(c)) {
                1 -> {
                    for (d in ORTHO + DIAG) {
                        val nx = x + d[0]
                        val ny = y + d[1]
                        if (!inBoard(nx, ny)) continue
                        val t = b[ny * 8 + nx]
                        if (t == 0 || (t > 0) != white) out.add(Move(idx, ny * 8 + nx))
                    }
                    if (white && pos.castling and 1 != 0 && b[5] == 0 && b[6] == 0 && b[7] == 3 &&
                        !attacked(b, 4, false) && !attacked(b, 5, false) && !attacked(b, 6, false)
                    ) {
                        out.add(Move(4, 6))
                    }
                    if (white && pos.castling and 2 != 0 && b[3] == 0 && b[2] == 0 && b[1] == 0 && b[0] == 3 &&
                        !attacked(b, 4, false) && !attacked(b, 3, false) && !attacked(b, 2, false)
                    ) {
                        out.add(Move(4, 2))
                    }
                    if (!white && pos.castling and 4 != 0 && b[61] == 0 && b[62] == 0 && b[63] == -3 &&
                        !attacked(b, 60, true) && !attacked(b, 61, true) && !attacked(b, 62, true)
                    ) {
                        out.add(Move(60, 62))
                    }
                    if (!white && pos.castling and 8 != 0 && b[59] == 0 && b[58] == 0 && b[57] == 0 && b[56] == -3 &&
                        !attacked(b, 60, true) && !attacked(b, 59, true) && !attacked(b, 58, true)
                    ) {
                        out.add(Move(60, 58))
                    }
                }
                2, 4, 3 -> {
                    val dirs = if (abs(c) == 3) ORTHO else if (abs(c) == 4) DIAG else ORTHO + DIAG
                    for (d in dirs) {
                        var nx = x + d[0]
                        var ny = y + d[1]
                        while (inBoard(nx, ny)) {
                            val t = b[ny * 8 + nx]
                            if (t == 0) out.add(Move(idx, ny * 8 + nx))
                            else {
                                if ((t > 0) != white) out.add(Move(idx, ny * 8 + nx))
                                break
                            }
                            nx += d[0]
                            ny += d[1]
                        }
                    }
                }
                5 -> {
                    for (d in KNIGHT_D) {
                        val nx = x + d[0]
                        val ny = y + d[1]
                        if (!inBoard(nx, ny)) continue
                        val t = b[ny * 8 + nx]
                        if (t == 0 || (t > 0) != white) out.add(Move(idx, ny * 8 + nx))
                    }
                }
                6 -> {
                    val fwd = if (white) 1 else -1
                    val startRank = if (white) 1 else 6
                    val lastRank = if (white) 7 else 0
                    val ny = y + fwd
                    if (inBoard(x, ny) && b[ny * 8 + x] == 0) {
                        addPawnMove(out, idx, ny * 8 + x, y + fwd, lastRank)
                        val ny2 = y + 2 * fwd
                        if (y == startRank && b[ny2 * 8 + x] == 0) out.add(Move(idx, ny2 * 8 + x))
                    }
                    for (dx in intArrayOf(1, -1)) {
                        val nx = x + dx
                        if (!inBoard(nx, ny)) continue
                        val t = b[ny * 8 + nx]
                        if (t != 0 && (t > 0) != white) addPawnMove(out, idx, ny * 8 + nx, y + fwd, lastRank)
                        else if (t == 0 && pos.ep == ny * 8 + nx) out.add(Move(idx, ny * 8 + nx))
                    }
                }
            }
        }
        return out
    }

    private fun addPawnMove(out: MutableList<Move>, from: Int, to: Int, toY: Int, lastRank: Int) {
        if (toY == lastRank) {
            for (p in intArrayOf(2, 3, 4, 5)) out.add(Move(from, to, p))
        } else {
            out.add(Move(from, to))
        }
    }

    fun isSquareAttacked(pos: ChessPosition, square: Int, bySide: Int): Boolean =
        attacked(pos.board, square, bySide == 0)

    /** b 上 sq 是否被 byWhite 方攻击。 */
    private fun attacked(b: IntArray, sq: Int, byWhite: Boolean): Boolean {
        val x = sq % 8
        val y = sq / 8
        for (d in KNIGHT_D) {
            val nx = x + d[0]
            val ny = y + d[1]
            if (!inBoard(nx, ny)) continue
            val t = b[ny * 8 + nx]
            if (t != 0 && (t > 0) == byWhite && abs(t) == 5) return true
        }
        for (d in ORTHO + DIAG) {
            val nx = x + d[0]
            val ny = y + d[1]
            if (!inBoard(nx, ny)) continue
            val t = b[ny * 8 + nx]
            if (t != 0 && (t > 0) == byWhite && abs(t) == 1) return true
        }
        val py = if (byWhite) y - 1 else y + 1
        if (py in 0..7) {
            for (px in intArrayOf(x - 1, x + 1)) {
                if (px in 0..7) {
                    val t = b[py * 8 + px]
                    if (t != 0 && (t > 0) == byWhite && abs(t) == 6) return true
                }
            }
        }
        for (dirs in arrayOf(ORTHO, DIAG)) {
            for (d in dirs) {
                var nx = x + d[0]
                var ny = y + d[1]
                while (inBoard(nx, ny)) {
                    val t = b[ny * 8 + nx]
                    if (t != 0) {
                        if ((t > 0) == byWhite) {
                            val a = abs(t)
                            val slider = if (dirs === ORTHO) a == 3 || a == 2 else a == 4 || a == 2
                            if (slider) return true
                        }
                        break
                    }
                    nx += d[0]
                    ny += d[1]
                }
            }
        }
        return false
    }

    private fun kingSquare(b: IntArray, white: Boolean): Int {
        val target = if (white) 1 else -1
        for (i in b.indices) if (b[i] == target) return i
        return -1
    }

    override fun searchPosition(pos: ChessPosition, move: Move): ChessPosition {
        val b = pos.board.copyOf()
        val white = pos.whiteToMove
        val piece = abs(b[move.from])
        val isCapture = b[move.to] != 0
        if (piece == 6 && move.to == pos.ep && b[move.to] == 0) {
            val capSq = if (white) move.to - 8 else move.to + 8
            b[capSq] = 0
        }
        var castling = pos.castling
        if (piece == 1) {
            if (white) castling = castling and 3.inv()
            else castling = castling and 12.inv()
            if (abs(move.to - move.from) == 2) {
                if (move.to == 6) { b[5] = b[7]; b[7] = 0 }
                else if (move.to == 2) { b[3] = b[0]; b[0] = 0 }
                else if (move.to == 62) { b[61] = b[63]; b[63] = 0 }
                else if (move.to == 58) { b[59] = b[56]; b[56] = 0 }
            }
        }
        val lastRank = if (white) 7 else 0
        val promo = if (piece == 6 && move.to / 8 == lastRank) {
            if (move.promo != 0) move.promo else 2
        } else 0
        if (promo != 0) b[move.to] = if (white) promo else -promo
        else { b[move.to] = b[move.from]; }
        b[move.from] = 0
        if (move.from == 7 || move.to == 7) castling = castling and 1.inv()
        if (move.from == 0 || move.to == 0) castling = castling and 2.inv()
        if (move.from == 63 || move.to == 63) castling = castling and 4.inv()
        if (move.from == 56 || move.to == 56) castling = castling and 8.inv()
        var ep = -1
        if (piece == 6 && abs(move.to - move.from) == 16) {
            ep = (move.from + move.to) / 2
        }
        val halfmove = if (piece == 6 || isCapture) 0 else pos.halfmove + 1
        return ChessPosition(b, !white, castling, ep, halfmove)
    }

    override fun inCheck(pos: ChessPosition): Boolean {
        val k = kingSquare(pos.board, pos.whiteToMove)
        if (k < 0) return true
        return attacked(pos.board, k, !pos.whiteToMove)
    }

    override fun legalMoves(pos: ChessPosition): List<Move> {
        val out = ArrayList<Move>()
        val white = pos.whiteToMove
        for (m in pseudoMoves(pos)) {
            val child = searchPosition(pos, m)
            val k = kingSquare(child.board, white)
            if (k < 0) continue
            if (!attacked(child.board, k, !white)) out.add(m)
        }
        return out
    }

    private fun insufficientMaterial(pos: ChessPosition): Boolean {
        var minors = 0
        for (c in pos.board) {
            if (c == 0 || abs(c) == 1) continue
            if (abs(c) == 4 || abs(c) == 5) minors++
            else return false
        }
        return minors <= 1
    }

    override fun applyMove(pos: ChessPosition, move: Move): AppliedMove<ChessPosition> {
        val child = searchPosition(pos, move)
        val opponentMoves = legalMoves(child)
        val check = inCheck(child)
        val status = when {
            opponentMoves.isEmpty() && check -> GameStatus.CHECKMATE
            opponentMoves.isEmpty() -> GameStatus.STALEMATE
            insufficientMaterial(child) || child.halfmove >= 100 -> GameStatus.DRAW
            check -> GameStatus.CHECK
            else -> GameStatus.ONGOING
        }
        val suffix = when (status) {
            GameStatus.CHECKMATE -> "#"
            GameStatus.CHECK -> "+"
            else -> ""
        }
        val captured = if (pos.board[move.to] != 0) 1
        else if (abs(pos.board[move.from]) == 6 && move.to == pos.ep) 1 else 0
        return AppliedMove(child, notation(pos, move) + suffix, captured, check, status)
    }

    private fun squareName(sq: Int): String {
        val x = sq % 8
        val y = sq / 8
        return ('a' + x).toString() + (y + 1)
    }

    fun notation(pos: ChessPosition, move: Move): String {
        val piece = abs(pos.board[move.from])
        if (piece == 1 && abs(move.to - move.from) == 2) {
            return if (move.to > move.from) "O-O" else "O-O-O"
        }
        val capture = pos.board[move.to] != 0 ||
            (piece == 6 && move.to == pos.ep && pos.board[move.to] == 0)
        val sb = StringBuilder()
        if (piece == 6) {
            if (capture) sb.append(('a' + (move.from % 8)).toString()).append("x")
            sb.append(squareName(move.to))
            val white = pos.whiteToMove
            val lastRank = if (white) 7 else 0
            if (move.to / 8 == lastRank) {
                val promo = if (move.promo != 0) move.promo else 2
                sb.append("=").append(LETTERS[promo])
            }
            return sb.toString()
        }
        sb.append(LETTERS[piece])
        // 消歧义：同种同色还有别的子能到同一目标
        val rivals = legalMoves(pos).filter {
            it.to == move.to && it.from != move.from && abs(pos.board[it.from]) == piece
        }
        if (rivals.isNotEmpty()) {
            val myFile = move.from % 8
            val myRank = move.from / 8
            val fileUnique = rivals.none { it.from % 8 == myFile }
            val rankUnique = rivals.none { it.from / 8 == myRank }
            when {
                fileUnique -> sb.append(('a' + myFile).toString())
                rankUnique -> sb.append((myRank + 1).toString())
                else -> sb.append(squareName(move.from))
            }
        }
        if (capture) sb.append("x")
        sb.append(squareName(move.to))
        return sb.toString()
    }

    override fun squareDesc(pos: ChessPosition, square: Int): String = squareName(square)

    override fun evaluate(pos: ChessPosition): Int {
        var s = 0
        for (i in pos.board.indices) {
            val c = pos.board[i]
            if (c == 0) continue
            val a = abs(c)
            var v = VALUES[a]
            val x = i % 8
            val y = i / 8
            val centerDist = abs(x - 3) + abs(y - 3)
            if (a == 5 || a == 4) v += (6 - centerDist) * 4
            if (a == 6) {
                val advance = if (c > 0) y - 1 else 6 - y
                v += advance * 6
                if (x in 3..4 && y in 3..4) v += 10
            }
            if (a == 2) v -= centerDist * 2
            if (a == 1) {
                val homeRank = if (c > 0) 0 else 7
                if (y == homeRank) v += 20
            }
            s += if (c > 0) v else -v
        }
        return if (pos.whiteToMove) s else -s
    }

    override fun searchMoves(pos: ChessPosition): List<Move> = orderMoves(pos, legalMoves(pos))

    override fun isTactical(pos: ChessPosition, move: Move): Boolean =
        pos.board[move.to] != 0 || move.promo != 0 ||
            (abs(pos.board[move.from]) == 6 && move.to == pos.ep)

    override fun terminalScore(pos: ChessPosition, moves: List<Move>, ply: Int): Int? = when {
        moves.isEmpty() -> if (inCheck(pos)) -MATE + ply else 0
        insufficientMaterial(pos) || pos.halfmove >= 100 -> 0
        else -> null
    }

    private fun orderMoves(pos: ChessPosition, moves: List<Move>): List<Move> =
        moves.sortedByDescending { m ->
            val victim = abs(pos.board[m.to])
            var s = if (victim == 0) 0 else VALUES[victim] * 10 - VALUES[abs(pos.board[m.from])]
            if (m.promo != 0) s += 80
            s
        }

    private fun quiescence(pos: ChessPosition, alpha: Int, beta: Int, qd: Int): Int {
        val moves = legalMoves(pos)
        terminalScore(pos, moves, qd)?.let { return it }
        val checked = inCheck(pos)
        val stand = evaluate(pos)
        if (!checked && stand >= beta) return beta
        var a = if (!checked && stand > alpha) stand else alpha
        if (!checked && qd >= 4) return a
        val caps = if (checked) moves else moves.filter { isTactical(pos, it) }
        if (qd >= 6) return caps.maxOf { m ->
            val child = searchPosition(pos, m)
            -(terminalScore(child, legalMoves(child), qd + 1) ?: evaluate(child))
        }
        for (m in orderMoves(pos, caps)) {
            val sc = -quiescence(searchPosition(pos, m), -beta, -a, qd + 1)
            if (sc >= beta) return beta
            if (sc > a) a = sc
        }
        return a
    }

    private fun negamax(pos: ChessPosition, depth: Int, alpha: Int, beta: Int, ply: Int): Int {
        val moves = legalMoves(pos)
        if (moves.isEmpty()) return if (inCheck(pos)) -MATE + ply else 0
        if (insufficientMaterial(pos) || pos.halfmove >= 100) return 0
        if (depth <= 0) return quiescence(pos, alpha, beta, 0)
        var a = alpha
        var best = -Int.MAX_VALUE
        for (m in orderMoves(pos, moves)) {
            val sc = -negamax(searchPosition(pos, m), depth - 1, -beta, -a, ply + 1)
            if (sc > best) best = sc
            if (best > a) a = best
            if (a >= beta) break
        }
        return best
    }

    override fun searchBest(pos: ChessPosition, depth: Int): SearchResult {
        val moves = legalMoves(pos)
        terminalScore(pos, moves, 0)?.let { return SearchResult(null, it) }
        var bestMove = moves[0]
        var bestScore = -Int.MAX_VALUE
        var alpha = -MATE * 2
        for (m in orderMoves(pos, moves)) {
            val sc = -negamax(searchPosition(pos, m), depth - 1, -MATE * 2, -alpha, 1)
            if (sc > bestScore) {
                bestScore = sc
                bestMove = m
            }
            if (sc > alpha) alpha = sc
        }
        return SearchResult(bestMove, bestScore, listOf(bestMove))
    }
}
