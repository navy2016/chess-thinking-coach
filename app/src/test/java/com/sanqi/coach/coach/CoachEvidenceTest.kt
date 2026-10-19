package com.sanqi.coach.coach

import com.sanqi.coach.core.Move
import com.sanqi.coach.core.chess.ChessEngine
import com.sanqi.coach.core.chess.ChessPosition
import com.sanqi.coach.engine.AnalysisBackend
import org.junit.Assert.*
import org.junit.Test

class CoachEvidenceTest {
    private val rules = ChessEngine()
    private var searches = 0
    private val backend = object : AnalysisBackend<ChessPosition> {
        override fun analyze(position: ChessPosition, history: List<Move>, level: Int, forcedMove: Move?, cancelled: () -> Unit,
                             budgetMillis: Int, onProgress: (PositionAnalysis) -> Unit): PositionAnalysis {
            cancelled()
            searches++
            val legal = rules.legalMoves(position)
            val preferred = listOf(Move(12, 28), Move(59, 35)).firstOrNull { it in legal }
            val candidates = legal.sortedBy { if (it == preferred) 0 else 1 }.map { move ->
                val next = rules.applyMove(position, move).position
                val reply = rules.legalMoves(next).firstOrNull()
                val source = SearchEvidence("batch-${history.size}", "chess", "test", "1", emptyList(), "cp",
                    rules.sideToMove(position), budgetMillis, 10, 10, 1000, 0, 10, true, true, false, "test")
                CandidateLine(move, if (move == preferred) 50 else 0, listOfNotNull(move, reply), evidence = source)
            }
            return PositionAnalysis(candidates, candidates.first().score, 10, 10, 1000, false, false, searchMillis = 10)
        }
        override fun close() = Unit
    }

    private fun session() = CoachSession(rules, CoachSession.Mode.TWO_PLAYER, 2, true, backend = backend)

    @Test fun capturesAndRepliesAreFactsAtTheirOwnPositions() {
        val s = session()
        s.commitReview(Move(12, 28), null)
        s.commitReview(Move(51, 35), null)
        val board = s.current.board.copyOf()
        val p = s.analyze(2, Move(28, 35))
        assertEquals(1, p.playedStep.captured)
        assertEquals(0, p.playedStep.side)
        assertEquals(Move(59, 35), p.replies.first().moves.first())
        assertEquals(1, p.replies.first().steps.first().captured)
        assertEquals(1, p.replies.first().steps.first().side)
        assertEquals(0, p.assessment.played.evidence!!.side)
        assertEquals(1, p.assessment.reply!!.evidence!!.side)
        for ((branch, reply) in p.replies.withIndex()) {
            var position = rules.applyMove(s.current, p.move).position
            for ((i, step) in reply.steps.withIndex()) {
                assertTrue(step.move in rules.legalMoves(position))
                val applied = rules.applyMove(position, step.move)
                assertEquals(applied.captured, step.captured)
                assertEquals(applied.check, step.check)
                assertEquals(applied.notation, step.notation)
                position = applied.position
                assertArrayEquals(position.board, s.previewPosition(p, branch, i + 1).board)
            }
        }
        assertArrayEquals(board, s.current.board)
        assertEquals(2, s.history.size)
    }

    @Test fun alternateEvidenceStartsAtLiveBoardAndCommitStillUsesTentativeMove() {
        val s = session()
        val p = s.analyze(2, Move(11, 27))
        assertEquals(Move(12, 28), p.alternative.first().move)
        assertArrayEquals(s.current.board, s.alternativePosition(p, 0).board)
        val alternate = s.alternativePosition(p, 1)
        assertEquals(6, alternate.board[28])
        assertEquals(0, alternate.board[27])
        s.commit(p.move, p, "facts")
        assertEquals(6, s.current.board[27])
        assertEquals(0, s.current.board[28])
        assertEquals(1, s.history.size)
        assertNotNull(s.history.single().rating!!.assessment)
        assertEquals("facts", s.history.single().rating!!.explanation)
    }

    @Test fun twoPlansRetainIndependentPlaybackAndCommitOnlyOneMove() {
        val s = session()
        val plans = TentativePlans()
        val a = s.analyze(2, Move(12, 28))
        plans.put(a, 1f)
        plans.playback(1, 1, false)
        assertNull(plans.select(1, s.revision))
        val b = s.analyze(2, Move(11, 27))
        plans.put(b, 2f)
        plans.playback(0, 1, true)
        assertEquals(1, plans.select(0, s.revision)!!.branch)
        assertEquals(a, plans.get(0)!!.preview)
        assertTrue(plans.select(1, s.revision)!!.alternative)
        assertEquals(b, plans.get(1)!!.preview)
        assertTrue(s.history.isEmpty())
        s.commit(b.move, plans.get(1)!!.preview)
        assertEquals(1, s.history.size)
        assertNull(plans.select(0, s.revision))
        assertNull(plans.get(1))
        assertFalse(s.accepts(a))
    }

    @Test fun restoringChoicesDoesNotInventAnalysis() {
        val plans = TentativePlans()
        plans.restore(0, Move(12, 28))
        plans.restore(1, Move(11, 27))
        assertNull(plans.select(1, 0)!!.preview)
        assertFalse(plans.comparable())
        plans.clear()
        assertNull(plans.get(0))
        assertNull(plans.get(1))
        assertEquals(0, plans.active)
    }

    @Test fun selectingRestoredDraftDoesNotSearchOrCommit() {
        val s = session()
        val plans = TentativePlans()
        val move = Move(11, 27)
        plans.restore(0, move, branch = 1, step = 2, alternative = true)
        val draft = plans.select(0, s.revision)!!
        plans.stage(move, resetPlayback = false)
        repeat(3) {
            assertEquals(draft, plans.select(0, s.revision))
            assertNull(plans.get(0)!!.preview)
        }
        assertEquals(0, searches)
        assertTrue(s.history.isEmpty())
    }

    @Test fun switchingDuringSearchKeepsNewDraftAndOtherCompletedPlan() {
        val s = session()
        val plans = TentativePlans()
        val other = s.analyze(2, Move(11, 27))
        plans.select(1, s.revision)
        plans.put(other, 2f)
        plans.select(0, s.revision)
        val draft = Move(12, 28)
        plans.stage(draft)
        val count = searches
        assertEquals(other, plans.select(1, s.revision)!!.preview)
        val restored = plans.select(0, s.revision)!!
        assertEquals(draft, restored.move)
        assertNull(restored.preview)
        assertEquals(count, searches)
        assertTrue(s.history.isEmpty())
    }

    @Test fun editingCompletedPlanStagesReplacementWithoutOldScore() {
        val s = session()
        val plans = TentativePlans()
        val previous = s.analyze(2, Move(12, 28))
        plans.put(previous, 2f)
        val replacement = Move(11, 27)
        plans.stage(replacement)
        plans.select(1, s.revision)
        val restored = plans.select(0, s.revision)!!
        assertEquals(replacement, restored.move)
        assertNull(restored.preview)
        assertEquals(0, restored.step)
        assertFalse(restored.alternative)
        assertFalse(plans.comparable())
    }

    @Test fun reanalysisRestoresEachPlansPlaybackModeAndClampsItsOwnLine() {
        val s = session()
        val plans = TentativePlans()
        val a = s.analyze(2, Move(11, 27))
        val b = s.analyze(2, Move(12, 28))
        plans.restore(0, a.move, branch = 0, step = 99, alternative = true)
        plans.restore(1, b.move, branch = 1, step = 1, alternative = false)
        plans.select(0, s.revision)
        plans.stage(a.move, resetPlayback = false)
        plans.put(a, 1f)
        assertTrue(plans.get(0)!!.alternative)
        assertEquals(a.alternative.size, plans.get(0)!!.step)
        assertArrayEquals(s.alternativePosition(a, a.alternative.size).board,
            s.alternativePosition(a, plans.get(0)!!.step).board)
        plans.select(1, s.revision)
        plans.stage(b.move, resetPlayback = false)
        plans.put(b, 1f)
        assertFalse(plans.get(1)!!.alternative)
        assertEquals(1, plans.get(1)!!.branch)
        assertEquals(1, plans.get(1)!!.step)
        assertTrue(plans.select(0, s.revision)!!.alternative)
        assertTrue(s.history.isEmpty())
    }

    @Test fun vanishedAlternativeDoesNotReuseItsStepAsReplyStep() {
        val s = session()
        val plans = TentativePlans()
        val preview = s.analyze(2, Move(12, 28))
        assertTrue(preview.alternative.isEmpty())
        plans.restore(0, preview.move, branch = 0, step = 2, alternative = true)
        plans.stage(preview.move, resetPlayback = false)
        plans.put(preview, 1f)
        assertFalse(plans.get(0)!!.alternative)
        assertEquals(0, plans.get(0)!!.step)
        assertTrue(s.history.isEmpty())
    }

    @Test fun insufficientSearchStillStoresFactsWithoutGrade() {
        val s = session()
        val original = s.analyze(2, Move(12, 28))
        val limited = original.analysis.copy(limited = true)
        val assessment = MoveAssessment.from(limited, original.move, null)
        val preview = original.copy(analysis = limited, assessment = assessment, loss = assessment.loss, grade = assessment.grade)
        s.commit(preview.move, preview, "verified move, insufficient search")
        val rating = s.history.single().rating!!
        assertNull(rating.loss)
        assertNull(rating.grade)
        assertNotNull(rating.assessment)
        assertTrue(rating.explanation.isNotEmpty())
    }
}
