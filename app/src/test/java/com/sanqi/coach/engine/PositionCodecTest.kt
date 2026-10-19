package com.sanqi.coach.engine

import com.sanqi.coach.core.Move
import com.sanqi.coach.core.chess.ChessEngine
import com.sanqi.coach.core.xiangqi.XiangqiEngine
import org.junit.Assert.*
import org.junit.Test

class PositionCodecTest {
    @Test fun initialChessFenKeepsRightsAndDirection() {
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            PositionCodec.fen(ChessEngine().initialPosition()))
    }
    @Test fun initialXiangqiFenKeepsRedAtBottom() {
        assertEquals("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1",
            PositionCodec.fen(XiangqiEngine().initialPosition()))
    }
    @Test fun chessCoordinatesAndAllPromotionsRoundTrip() {
        for (promo in 0..5) {
            if (promo == 1) continue
            val move = Move(48, 56, promo)
            assertEquals(move, PositionCodec.parseUci(PositionCodec.uci(move, "chess"), "chess"))
        }
        assertEquals(Move(4, 6), PositionCodec.parseUci("e1g1", "chess"))
        assertEquals("e2e4", PositionCodec.uci(Move(12, 28), "chess"))
    }
    @Test fun xiangqiRanksStartAtZero() {
        assertEquals("b0c2", PositionCodec.uci(Move(1, 20), "xiangqi"))
        assertEquals(Move(1, 20), PositionCodec.parseUci("b0c2", "xiangqi"))
        assertEquals(Move(85, 76), PositionCodec.parseUci("e9e8", "xiangqi"))
    }
    @Test fun fairyXiangqiUsesOneBasedVariableLengthRanks() {
        assertEquals("b1c3", PositionCodec.uci(Move(1, 20), "xiangqi", true))
        assertEquals("e10e9", PositionCodec.uci(Move(85, 76), "xiangqi", true))
        for (from in 0 until 90) for (to in 0 until 90) {
            val move = Move(from, to)
            assertEquals(move, PositionCodec.parseUci(PositionCodec.uci(move, "xiangqi", true), "xiangqi", true))
        }
    }
    @Test(expected = EngineException::class) fun fairyRejectsZeroBasedRank() { PositionCodec.parseUci("b0c2", "xiangqi", true) }
    @Test fun goCoordinatesSkipIAndPreservePass() {
        for (size in listOf(9, 13, 19)) for (index in 0 until size * size) {
            val move = Move(index, index)
            assertEquals(move, PositionCodec.parseGo(PositionCodec.go(move, size), size))
        }
        assertEquals("J10", PositionCodec.go(Move(179, 179), 19))
        assertEquals(Move(-1, -1), PositionCodec.parseGo("pass", 19))
    }
    @Test(expected = EngineException::class) fun rejectInvalidGoColumn() { PositionCodec.parseGo("I9", 19) }
    @Test(expected = EngineException::class) fun rejectInvalidChessRank() { PositionCodec.parseUci("e0e4", "chess") }
}
