package com.sanqi.coach.coach

import com.sanqi.coach.core.Move
import com.sanqi.coach.core.chess.ChessEngine
import com.sanqi.coach.core.chess.ChessPosition
import com.sanqi.coach.engine.AnalysisBackend
import org.junit.Assert.*
import org.junit.Test

class ProfessionalBudgetTest {
    private class Backend : AnalysisBackend<ChessPosition> {
        val budgets = mutableListOf<Int>()
        val rules = ChessEngine()
        override fun analyze(position: ChessPosition, history: List<Move>, level: Int, forcedMove: Move?, cancelled: () -> Unit,
                             budgetMillis: Int, onProgress: (PositionAnalysis) -> Unit): PositionAnalysis {
            cancelled()
            budgets.add(budgetMillis)
            val moves = rules.legalMoves(position).take(3).toMutableList()
            if (forcedMove != null && forcedMove !in moves) moves.add(forcedMove)
            val result = PositionAnalysis(moves.map { CandidateLine(it, 0, listOf(it)) }, 0, 8, 14, 100,
                true, false, "test", searchMillis = budgetMillis.toLong())
            onProgress(result)
            return result
        }
        override fun close() = Unit
    }

    @Test fun aiSelectionIsAnalyzedOnlyOnceBeforeReplies() {
        val backend = Backend()
        val session = CoachSession(backend.rules, CoachSession.Mode.VS_AI, 3, true, backend = backend)
        var updates = 0
        val preview = session.analyzeBest(3, onProgress = { updates++ })!!
        assertEquals(listOf(2500, 2500), backend.budgets)
        assertEquals(2, updates)
        assertTrue(session.history.isEmpty())
        session.commit(preview.move, preview)
        assertEquals(1, session.history.size)
    }

    @Test fun selectedMoveAndRepliesShareOperationBudget() {
        val backend = Backend()
        val session = CoachSession(backend.rules, CoachSession.Mode.TWO_PLAYER, 2, true, backend = backend)
        val preview = session.analyze(2, Move(12, 28))
        assertEquals(listOf(1000, 1000), backend.budgets)
        assertTrue(session.accepts(preview))
        session.commit(preview.move, preview)
        assertFalse(session.accepts(preview))
    }

    @Test fun cancellationDoesNotCommitMove() {
        val backend = Backend()
        val session = CoachSession(backend.rules, CoachSession.Mode.VS_AI, 3, true, backend = backend)
        try {
            session.analyzeBest(3, cancelled = { throw java.util.concurrent.CancellationException() })
            fail("Expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) {
            assertTrue(session.history.isEmpty())
            assertTrue(backend.budgets.isEmpty())
        }
    }
}
