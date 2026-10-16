package com.sanqi.coach.core.go

import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoEngineTest {

    private fun pos9(board: IntArray, toMove: Int): GoPosition =
        GoPosition(9, board, toMove, IntArray(2), -1, 0)

    private fun empty9(): GoPosition = GoEngine(9).initialPosition()

    @Test
    fun testCaptureSingleStone() {
        val e = GoEngine(9)
        var p = empty9()
        // 黑 (0,1)=9，白 (0,0)=0，黑 (1,0)=1 围住白
        p = e.applyMove(p, Move(9, 9)).position
        p = e.applyMove(p, Move(0, 0)).position
        val r = e.applyMove(p, Move(1, 1))
        assertEquals(1, r.captured)
        assertEquals(0, e.stoneAt(r.position, 0))
        assertEquals(1, r.position.captures[0])
    }

    @Test
    fun testCaptureChain() {
        val e = GoEngine(9)
        var p = empty9()
        // 白两子 (0,0)=0,(1,0)=1；黑 (0,1)=9,(1,1)=10，黑 (2,0)=2 提两子
        p = e.applyMove(p, Move(9, 9)).position
        p = e.applyMove(p, Move(0, 0)).position
        p = e.applyMove(p, Move(10, 10)).position
        p = e.applyMove(p, Move(1, 1)).position
        val r = e.applyMove(p, Move(2, 2))
        assertEquals(2, r.captured)
        assertEquals(0, e.stoneAt(r.position, 0))
        assertEquals(0, e.stoneAt(r.position, 1))
    }

    @Test
    fun testSuicideIsIllegal() {
        val e = GoEngine(9)
        var p = empty9()
        // 黑 (0,1)=9、黑 (1,0)=1 封住角，白下 (0,0)=0 即自杀
        p = e.applyMove(p, Move(9, 9)).position
        p = e.applyMove(p, Move(80, 80)).position
        p = e.applyMove(p, Move(1, 1)).position
        p = e.applyMove(p, Move(8, 8)).position
        p = e.applyMove(p, Move(76, 76)).position
        assertFalse(e.isMoveLegal(p, Move(0, 0)))
        assertTrue(e.legalMoves(p).none { it.to == 0 })
    }

    @Test
    fun testKoForbiddenThenAllowed() {
        val e = GoEngine(9)
        // 黑：(1,0)=1,(0,1)=9,(1,2)=19；白：(2,0)=2,(2,2)=21,(3,1)=22,(1,1)=10
        val board = IntArray(81)
        board[1] = 1; board[9] = 1; board[19] = 1
        board[2] = 2; board[20] = 2; board[12] = 2; board[10] = 2
        var p = pos9(board, 0)
        // 黑 (2,1)=11 提白 (1,1)
        val r = e.applyMove(p, Move(11, 11))
        assertEquals(1, r.captured)
        assertEquals(10, r.position.koPoint)
        // 白立即回提非法
        val p2 = r.position
        assertTrue(e.legalMoves(p2).none { it.to == 10 })
        // 白在别处落一手
        val r2 = e.applyMove(p2, Move(80, 80))
        // 黑再应对一手
        val r3 = e.applyMove(r2.position, Move(72, 72))
        // 现在白可以回提了
        assertTrue(e.legalMoves(r3.position).any { it.to == 10 })
        val recapture = e.applyMove(r3.position, Move(10, 10))
        assertEquals(1, recapture.captured)
        assertEquals(11, recapture.position.koPoint)
    }

    @Test
    fun testPassCounters() {
        val e = GoEngine(9)
        var p = empty9()
        p = e.applyMove(p, Move(-1, -1)).position
        assertEquals(1, p.consecutivePasses)
        assertEquals(1, p.toMove)
        p = e.applyMove(p, Move(20, 20)).position
        assertEquals(0, p.consecutivePasses)
        p = e.applyMove(p, Move(-1, -1)).position
        p = e.applyMove(p, Move(-1, -1)).position
        assertTrue(e.legalMoves(p).isEmpty())
        assertNull(e.searchBest(p, 2).bestMove)
    }

    @Test
    fun testNotation() {
        val e19 = GoEngine(19)
        val p = e19.initialPosition()
        assertEquals("黑 A19", e19.notation(p, Move(0, 0)))
        assertEquals("A19", e19.squareDesc(p, 0))
        assertEquals("A1", e19.squareDesc(p, 18 * 19))
        assertEquals("J10", e19.squareDesc(p, 9 * 19 + 8))
        val e9 = GoEngine(9)
        val p9 = e9.initialPosition()
        assertEquals("白 E5", e9.notation(GoPosition(9, IntArray(81), 1, IntArray(2), -1, 0), Move(40, 40)))
        assertEquals("黑停一手", e9.notation(GoPosition(9, IntArray(81), 0, IntArray(2), -1, 0), Move(-1, -1)))
    }

    @Test
    fun testScoreAreaSimple() {
        val e = GoEngine(9, 7.5)
        val board = IntArray(81)
        board[20] = 1
        board[60] = 2
        val p = pos9(board, 0)
        val s = e.scoreArea(p, emptySet())
        assertEquals(1.0, s.black, 0.001)
        assertEquals(8.5, s.white, 0.001)
        assertEquals("白胜7.5子", s.winnerText)
    }

    @Test
    fun testScoreAreaWithDeadStones() {
        val e = GoEngine(9, 7.5)
        val board = IntArray(81)
        // 白死子 (2,1)=11 被黑 (1,1)=10,(3,1)=28,(2,0)=2,(2,2)=20 包围
        board[10] = 1; board[28] = 1; board[2] = 1; board[20] = 1
        board[11] = 2
        val p = pos9(board, 0)
        val s = e.scoreArea(p, setOf(11))
        // 死子移除后全盘只接触黑：黑 = 4 子 + 全部空点；白只剩贴目
        assertEquals(81 - 4 + 4.0, s.black, 0.001)
        assertEquals(7.5, s.white, 0.001)
        assertTrue(s.winnerText.startsWith("黑胜"))
    }

    @Test
    fun testEvaluateDirection() {
        val e = GoEngine(9, 0.0)
        val board = IntArray(81)
        for (i in intArrayOf(9, 10, 11, 18, 20)) board[i] = 1
        board[70] = 2
        val blackToMove = pos9(board, 0)
        val whiteToMove = pos9(board, 1)
        assertTrue(e.evaluate(blackToMove) > 0)
        assertTrue(e.evaluate(whiteToMove) < 0)
    }

    @Test
    fun testSearchFindsCapture() {
        val e = GoEngine(9)
        val board = IntArray(81)
        // 白 (0,0)=0 只剩 (1,0)=1 一口气；黑 (0,1)=9
        board[0] = 2
        board[9] = 1
        val p = pos9(board, 0)
        val r = e.searchBest(p, 1)
        assertNotNull(r.bestMove)
        assertEquals(1, r.bestMove!!.to)
        val applied = e.applyMove(p, r.bestMove!!)
        assertEquals(1, applied.captured)
        assertEquals(GameStatus.ONGOING, applied.status)
    }

    @Test
    fun testApplyMoveDoesNotMutateInput() {
        val e = GoEngine(9)
        val p = empty9()
        val r = e.applyMove(p, Move(40, 40))
        assertEquals(0, p.board[40])
        assertEquals(1, e.stoneAt(r.position, 40))
        assertEquals(0, p.toMove)
        assertEquals(1, r.position.toMove)
    }
}
