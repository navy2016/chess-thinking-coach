package com.sanqi.coach.coach

import com.sanqi.coach.core.Move
import com.sanqi.coach.core.chess.ChessEngine
import com.sanqi.coach.core.go.GoEngine
import com.sanqi.coach.core.go.GoPosition
import com.sanqi.coach.core.xiangqi.XiangqiEngine
import org.junit.Assert.*
import org.junit.Test

class TeachingFeaturesTest {
    @Test fun centralPawnMoveHasSpecificOccupancyAndControlEvidence() {
        val engine = ChessEngine()
        val before = engine.initialPosition()
        val after = engine.applyMove(before, Move(12, 28)).position
        val a = PositionFeatures.read(before, 0, 12)
        val b = PositionFeatures.read(after, 0, 28)
        assertEquals(0, a.values[TeachingFeature.CENTER_OCCUPANCY])
        assertEquals(1, b.values[TeachingFeature.CENTER_OCCUPANCY])
        assertTrue(b.values.getValue(TeachingFeature.CENTER_CONTROL) > a.values.getValue(TeachingFeature.CENTER_CONTROL))
        assertEquals(6, before.board[12])
        assertEquals(0, before.board[28])
    }

    @Test fun kingAttackFeatureUsesTheRequestedSide() {
        val engine = ChessEngine()
        val board = IntArray(64)
        board[4] = 1
        board[56] = -1
        board[60] = -3
        val pos = engine.fromBoard(board, true, 0, -1)
        assertEquals(1, PositionFeatures.read(pos, 0, 4).values[TeachingFeature.KING_ATTACKED])
        assertEquals(0, PositionFeatures.read(pos, 1, 56).values[TeachingFeature.KING_ATTACKED])
    }

    @Test fun goLibertiesAreDeduplicatedAndImmediateCaptureRespectsKo() {
        val board = IntArray(81)
        board[1] = 2; board[2] = 1; board[10] = 1
        val pos = GoPosition(9, board, 0, IntArray(2), -1, 0)
        val features = PositionFeatures.read(pos, 1, 1)
        assertEquals(1, features.values[TeachingFeature.GO_FOCUS_LIBERTIES])
        assertEquals(1, features.values[TeachingFeature.GO_ATARI_GROUPS])
        assertEquals(1, features.values[TeachingFeature.GO_CAPTURABLE_STONES])
        assertEquals(0, PositionFeatures.read(pos.copy(koPoint = 0), 1, 1).values[TeachingFeature.GO_CAPTURABLE_STONES])
        assertEquals(2, board[1])
    }

    @Test fun joiningGroupsProducesConnectionAndLibertyFacts() {
        val board = IntArray(81)
        board[10] = 1; board[12] = 1
        val pos = GoPosition(9, board, 0, IntArray(2), -1, 0)
        val engine = GoEngine(9)
        val after = engine.applyMove(pos, Move(11, 11)).position
        assertEquals(2, PositionFeatures.read(pos, 0, 11).values[TeachingFeature.GO_GROUPS])
        val features = PositionFeatures.read(after, 0, 11)
        assertEquals(1, features.values[TeachingFeature.GO_GROUPS])
        assertEquals(8, features.values[TeachingFeature.GO_FOCUS_LIBERTIES])
    }

    @Test fun xiangqiHorseAndRookCannonFeaturesAreAvailableWithoutMutation() {
        val engine = XiangqiEngine()
        val pos = engine.initialPosition()
        val original = pos.board.copyOf()
        val features = PositionFeatures.read(pos, 0, 7)
        assertTrue(features.values.getValue(TeachingFeature.BLOCKED_HORSE_LEGS) > 0)
        assertTrue(features.values.getValue(TeachingFeature.ROOK_CANNON_MOVES) > 0)
        assertArrayEquals(original, pos.board)
        assertEquals(0, pos.toMove)
    }

    @Test fun teachingPointsBindTheirLocationAndRetainOriginalSide() {
        val engine = ChessEngine()
        val session = CoachSession(engine, CoachSession.Mode.TWO_PLAYER, 1, true)
        val p = session.analyze(1, Move(12, 28))
        val lesson = p.teaching!!
        assertEquals("chess", lesson.kind)
        assertEquals(0, lesson.side)
        assertEquals(lesson.points.size, lesson.points.map { it.id }.toSet().size)
        assertTrue(lesson.points.any { it.step == 0 && !it.alternative })
        assertTrue(session.history.isEmpty())
    }

    @Test fun automaticSpeechDeduplicatesButManualReplayStillWorks() {
        val interaction = TeachingInteraction()
        assertEquals("lesson", interaction.narration("a", "lesson", true))
        assertNull(interaction.narration("a", "lesson", true))
        assertEquals("lesson", interaction.narration("a", "lesson", false))
        assertEquals("new", interaction.narration("b", "new", true))
        assertEquals("new", interaction.lastText)
        interaction.clear()
        assertEquals("", interaction.lastText)
        assertEquals("lesson", interaction.narration("a", "lesson", true))
    }
}
