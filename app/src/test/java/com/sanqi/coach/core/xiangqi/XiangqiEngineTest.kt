package com.sanqi.coach.core.xiangqi

import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiangqiEngineTest {

    private val e = XiangqiEngine()

    private fun board(vararg placements: Pair<Int, Int>): IntArray {
        val b = IntArray(90)
        for ((sq, code) in placements) b[sq] = code
        return b
    }

    private fun pos(b: IntArray, toMove: Int) = XiangqiPosition(b, toMove)

    // 两王放在不相干的宫位，避免照面干扰专项测试
    private fun kingsTyped(): Array<Pair<Int, Int>> = arrayOf(3 to 1, 86 to -1)

    @Test
    fun testHorseLeg() {
        val b = board(40 to 4, 49 to 7, 30 to -7, *kingsTyped())
        val moves = e.legalMoves(pos(b, 0)).filter { it.from == 40 }
        // 蹩马腿：(4,5) 有子，(5,6)=59 与 (3,6)=57 不可达
        assertTrue(moves.none { it.to == 59 })
        assertTrue(moves.none { it.to == 57 })
        // (3,2)=21 与 (5,2)=23 可达
        assertTrue(moves.any { it.to == 21 })
        assertTrue(moves.any { it.to == 23 })
    }

    @Test
    fun testElephantEyeAndRiver() {
        // 相在 (4,4)，象眼 (3,3)=30 被塞，(6,2)=24 与 (2,2)=20 的眼是 (5,3)/(3,3)
        val blocked = board(40 to 3, 30 to -7, *kingsTyped())
        val moves = e.legalMoves(pos(blocked, 0)).filter { it.from == 40 }
        assertTrue(moves.none { it.to == 20 })
        assertTrue(moves.any { it.to == 24 })
        // 相不能过河：(2,6)=56、(6,6)=60 不可达
        assertTrue(moves.none { it.to == 56 })
        assertTrue(moves.none { it.to == 60 })

        val open = board(40 to 3, *kingsTyped())
        val openMoves = e.legalMoves(pos(open, 0)).filter { it.from == 40 }
        assertTrue(openMoves.any { it.to == 20 })
        assertTrue(openMoves.any { it.to == 24 })
    }

    @Test
    fun testCannonScreen() {
        // 无炮架：炮 (4,4)=40 不能吃 (4,8)=76
        val noScreen = board(40 to 6, 76 to -5, *kingsTyped())
        val m1 = e.legalMoves(pos(noScreen, 0)).filter { it.from == 40 }
        assertTrue(m1.none { it.to == 76 })
        assertTrue(m1.any { it.to == 67 })

        // 有炮架 (4,6)=58：可吃 (4,8)，但不能越架平走到 (4,7)=67
        val screened = board(40 to 6, 58 to -7, 76 to -5, *kingsTyped())
        val m2 = e.legalMoves(pos(screened, 0)).filter { it.from == 40 }
        assertTrue(m2.any { it.to == 76 })
        assertTrue(m2.none { it.to == 67 })
        assertTrue(m2.none { it.to == 58 })
        assertTrue(m2.any { it.to == 43 })
    }

    @Test
    fun testKingFacing() {
        // 两王同列对脸，红必须解将：车 (0,5)=45 平中挡住是唯一解将手段之一
        val b = board(4 to 1, 85 to -1, 45 to 5)
        val moves = e.legalMoves(pos(b, 0))
        assertTrue(moves.any { it.from == 45 && it.to == 49 })
        assertTrue(moves.any { it.from == 4 && it.to == 3 })
        assertTrue(moves.any { it.from == 4 && it.to == 5 })
        // 离开中线后仍照面的王车移动非法
        assertTrue(moves.none { it.from == 4 && it.to == 13 })
        assertTrue(moves.none { it.from == 45 && it.to == 0 })
    }

    @Test
    fun testPawnRules() {
        // 初始兵 (0,3)=27 未过河：只能前进到 (0,4)=36
        val init = e.initialPosition()
        val pawnMoves = e.legalMoves(init).filter { it.from == 27 }
        assertTrue(pawnMoves.isNotEmpty())
        assertTrue(pawnMoves.all { it.to == 36 })

        // 过河兵 (0,6)=54：可进 (0,7)=63，可横 (1,6)=55，不能退
        val crossed = board(54 to 7, *kingsTyped())
        val cm = e.legalMoves(pos(crossed, 0)).filter { it.from == 54 }
        assertTrue(cm.any { it.to == 63 })
        assertTrue(cm.any { it.to == 55 })
        assertTrue(cm.none { it.to == 45 })
    }

    @Test
    fun testDoubleRookMate() {
        // 双车错：红被照面将军，车四平五既解将又将杀
        val b = board(
            3 to 5, 4 to 1, 5 to 5,
            27 to -4,
            45 to 5,
            85 to -1
        )
        val r = e.applyMove(pos(b, 0), Move(45, 49))
        assertTrue(r.check)
        assertEquals(GameStatus.CHECKMATE, r.status)
    }

    @Test
    fun testStalemate() {
        // 红车 (0,5)→(0,8)：黑将 (4,9) 无子可动但未被将军——困毙
        val b = board(
            3 to 1,
            45 to 5,
            84 to -2, 85 to -1, 86 to -2
        )
        val r = e.applyMove(pos(b, 0), Move(45, 72))
        assertFalse(r.check)
        assertEquals(GameStatus.STALEMATE, r.status)
    }

    @Test
    fun testNotation() {
        val p = e.initialPosition()
        assertEquals("炮二平五", e.notation(p, Move(25, 22)))
        assertEquals("马八进七", e.notation(p, Move(1, 20)))
        assertEquals("红方", e.sideName(0))
        assertEquals("黑方", e.sideName(1))
    }

    @Test
    fun testEvaluateDirection() {
        val b = board(3 to 1, 86 to -1, 40 to 5)
        assertTrue(e.evaluate(pos(b, 0)) > 0)
        assertTrue(e.evaluate(pos(b, 1)) < 0)
    }

    @Test
    fun testSearchFindsFreeRook() {
        // 黑车 (4,6)=58 可白吃红车 (4,4)=40
        val b = board(3 to 1, 86 to -1, 40 to 5, 58 to -5)
        val r = e.searchBest(pos(b, 1), 2)
        assertTrue(r.bestMove != null && r.bestMove!!.from == 58 && r.bestMove!!.to == 40)
    }
}
