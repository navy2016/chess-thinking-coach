package com.sanqi.coach.coach

import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move
import com.sanqi.coach.core.chess.ChessEngine
import com.sanqi.coach.core.go.GoEngine
import com.sanqi.coach.core.xiangqi.XiangqiEngine
import org.junit.Assert.*
import org.junit.Test

class CoachSessionTest {
    @Test
    fun stalePreviewCannotBeCommittedAfterUndo() {
        val engine = ChessEngine()
        val session = CoachSession(engine, CoachSession.Mode.TWO_PLAYER, 1, true)
        session.commitReview(Move(12, 28), null)
        session.commitReview(Move(52, 36), null)
        val preview = session.analyze(1, Move(6, 21))
        session.undo()
        assertFalse(session.accepts(preview))
        try {
            session.commit(preview.move, preview)
            fail("Stale preview must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(1, session.history.size)
            assertEquals(1, session.sideToMove())
        }
    }

    @Test
    fun previewAndBranchesDoNotChangeLivePosition() {
        val engine = ChessEngine()
        val session = CoachSession(engine, CoachSession.Mode.TWO_PLAYER, 1, true)
        val original = session.current.board.copyOf()
        val p = session.analyze(1, Move(12, 28))
        for (branch in p.replies.indices) {
            for (step in 0..p.replies[branch].moves.size) session.previewPosition(p, branch, step)
        }
        assertArrayEquals(original, session.current.board)
        assertTrue(session.history.isEmpty())
        session.commit(p.move, p)
        assertEquals(1, session.history.size)
        assertFalse(session.accepts(p))
        assertEquals(6, session.current.board[28])
    }

    @Test
    fun undoReturnsToCustomInitialPosition() {
        val engine = ChessEngine()
        val initial = engine.applyMove(engine.initialPosition(), Move(12, 28)).position
        val session = CoachSession(engine, CoachSession.Mode.TWO_PLAYER, 1, true, initial)
        session.commitReview(Move(52, 36), null)
        session.undo()
        assertArrayEquals(initial.board, session.current.board)
        assertEquals(initial.whiteToMove, session.current.whiteToMove)
    }

    @Test
    fun goResumeRemovesTrailingPassesAndCanBeReplayed() {
        val engine = GoEngine(9)
        val session = CoachSession(engine, CoachSession.Mode.TWO_PLAYER, 1, false)
        session.commitReview(Move(20, 20), null)
        session.commitPass()
        session.commitPass()
        assertTrue(engine.legalMoves(session.current).isEmpty())
        session.resumeGo()
        assertEquals(1, session.history.size)
        assertEquals(0, session.current.consecutivePasses)
        assertTrue(session.isMoveLegal(Move(60, 60)))
        val restored = CoachSession(engine, CoachSession.Mode.TWO_PLAYER, 1, false)
        for (entry in session.history) restored.commitReview(entry.move, entry.rating)
        assertArrayEquals(session.current.board, restored.current.board)
        assertEquals(session.sideToMove(), restored.sideToMove())
    }

    @Test
    fun stalematedSideLosesInXiangqi() {
        val engine = XiangqiEngine()
        val board = IntArray(90)
        board[3] = 1; board[66] = 5; board[68] = 5; board[85] = -1
        val session = CoachSession(engine, CoachSession.Mode.TWO_PLAYER, 1, false, engine.fromBoard(board, 0))
        session.commitReview(Move(66, 75), null)
        assertEquals(GameStatus.STALEMATE, session.endStatus)
        assertEquals(0, session.winner)
        assertEquals("红方胜（困毙）", session.gameOverText)
    }

    @Test
    fun undoBeforeAiReplyDoesNotRemovePreviousRound() {
        val session = CoachSession(ChessEngine(), CoachSession.Mode.VS_AI, 1, true)
        session.commitReview(Move(12, 28), null)
        session.commitReview(Move(52, 36), null)
        session.commitReview(Move(6, 21), null)
        session.undo()
        assertEquals(2, session.history.size)
        assertEquals(0, session.sideToMove())
    }
}
