package com.sanqi.coach.core.chess

import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessEngineTest {

    private val e = ChessEngine()

    private fun board(vararg placements: Pair<Int, Int>): IntArray {
        val b = IntArray(64)
        for ((sq, code) in placements) b[sq] = code
        return b
    }

    private fun pos(b: IntArray, whiteToMove: Boolean, castling: Int = 0, ep: Int = -1) =
        ChessPosition(b, whiteToMove, castling, ep, 0)

    @Test
    fun testCastling() {
        val clean = board(4 to 1, 0 to 3, 7 to 3, 56 to -1)
        val p = pos(clean, true, 3)
        val moves = e.legalMoves(p)
        assertTrue(moves.contains(Move(4, 6)))
        assertTrue(moves.contains(Move(4, 2)))
        val r = e.applyMove(p, Move(4, 6))
        assertEquals("O-O", r.notation)
        assertEquals(1, r.position.board[6])
        assertEquals(3, r.position.board[5])
        assertEquals(0, r.position.board[7])
        assertEquals(0, r.position.castling and 3)

        val blocked = board(4 to 1, 0 to 3, 7 to 3, 5 to 5, 56 to -1)
        assertFalse(e.legalMoves(pos(blocked, true, 3)).contains(Move(4, 6)))
        assertTrue(e.legalMoves(pos(blocked, true, 3)).contains(Move(4, 2)))

        // 黑车控制 f 线：O-O 不合法，O-O-O 仍可
        val throughCheck = board(4 to 1, 0 to 3, 7 to 3, 61 to -3, 63 to -1)
        val moves2 = e.legalMoves(pos(throughCheck, true, 3))
        assertFalse(moves2.contains(Move(4, 6)))
        assertTrue(moves2.contains(Move(4, 2)))
    }

    @Test
    fun testEnPassant() {
        val b = board(36 to 6, 35 to -6, 4 to 1, 63 to -1)
        val p = pos(b, true, 0, 43)
        val epMove = Move(36, 43)
        assertTrue(e.legalMoves(p).contains(epMove))
        val r = e.applyMove(p, epMove)
        assertEquals(0, r.position.board[35])
        assertEquals(6, r.position.board[43])
        assertEquals("exd6", r.notation)

        // ep 窗口已过：同样的目标格不再是吃子
        val expired = pos(b, true, 0, -1)
        assertFalse(e.legalMoves(expired).contains(epMove))
    }

    @Test
    fun testPromotion() {
        val b = board(48 to 6, 4 to 1, 47 to -1)
        val p = pos(b, true)
        val promoMoves = e.legalMoves(p).filter { it.from == 48 }
        assertEquals(4, promoMoves.size)
        val r = e.applyMove(p, Move(48, 56, 2))
        assertEquals(2, r.position.board[56])
        assertEquals("a8=Q", r.notation)
        // promo=0 时默认升后
        val r2 = e.applyMove(p, Move(48, 56, 0))
        assertEquals(2, r2.position.board[56])
    }

    @Test
    fun testPinnedKnightCannotMove() {
        val b = board(4 to 1, 36 to 5, 60 to -3, 56 to -1)
        val p = pos(b, true)
        assertTrue(e.legalMoves(p).none { it.from == 36 })
        // 王自己可以动
        assertTrue(e.legalMoves(p).any { it.from == 4 })
    }

    @Test
    fun testFoolsMate() {
        var p = e.initialPosition()
        p = e.applyMove(p, Move(13, 21)).position
        p = e.applyMove(p, Move(52, 36)).position
        p = e.applyMove(p, Move(14, 30)).position
        val r = e.applyMove(p, Move(59, 31))
        assertEquals(GameStatus.CHECKMATE, r.status)
        assertEquals("Qh4#", r.notation)
    }

    @Test
    fun testStalemate() {
        val b = board(33 to 2, 4 to 1, 56 to -1)
        val r = e.applyMove(pos(b, true), Move(33, 41))
        assertEquals(GameStatus.STALEMATE, r.status)
        assertFalse(r.check)
    }

    @Test
    fun testInsufficientMaterialDraw() {
        val b = board(4 to 1, 56 to -1, 38 to -5)
        val r = e.applyMove(pos(b, true), Move(4, 12))
        assertEquals(GameStatus.DRAW, r.status)
    }

    @Test
    fun testSanNotation() {
        val p = e.initialPosition()
        assertEquals("e4", e.notation(p, Move(12, 28)))
        assertEquals("Nf3", e.notation(p, Move(6, 21)))

        val cap = board(28 to 6, 35 to -6, 4 to 1, 63 to -1)
        assertEquals("exd5", e.notation(pos(cap, true), Move(28, 35)))

        val promo = board(52 to 6, 4 to 1, 63 to -1)
        assertEquals("e8=Q+", e.notation(pos(promo, true), Move(52, 60, 2)))

        val castles = board(4 to 1, 0 to 3, 7 to 3, 56 to -1)
        assertEquals("O-O", e.notation(pos(castles, true, 3), Move(4, 6)))
        assertEquals("O-O-O", e.notation(pos(castles, true, 3), Move(4, 2)))

        val disamb = board(1 to 5, 34 to 5, 4 to 1, 56 to -1)
        assertEquals("Nbc3", e.notation(pos(disamb, true), Move(1, 18)))
    }

    @Test
    fun testEvaluateDirection() {
        val b = board(3 to 2, 0 to 1, 63 to -1)
        assertTrue(e.evaluate(pos(b, true)) > 0)
        assertTrue(e.evaluate(pos(b, false)) < 0)
    }

    @Test
    fun testSearchFindsQueenCapture() {
        val b = board(35 to 2, 59 to -3, 0 to 1, 63 to -1)
        val r = e.searchBest(pos(b, false), 2)
        assertTrue(r.bestMove != null && r.bestMove!!.from == 59 && r.bestMove!!.to == 35)
    }
}
