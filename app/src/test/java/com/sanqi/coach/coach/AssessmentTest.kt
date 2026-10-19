package com.sanqi.coach.coach

import com.sanqi.coach.core.Move
import com.sanqi.coach.engine.UciProtocol
import org.junit.Assert.*
import org.junit.Test

class AssessmentTest {
    private val a = Move(12, 28)
    private val b = Move(11, 27)
    private fun evidence() = SearchEvidence("batch", "chess", "fish", "1", listOf("model:hash"), "cp", 0,
        1000, 10, 10, 5_000_000_000L, 0, 800, true, true, false, "Threads=2;MultiPV=3")

    private fun analysis(left: SearchEvidence? = evidence(), right: SearchEvidence? = left): PositionAnalysis =
        PositionAnalysis(listOf(CandidateLine(a, 40, listOf(a), evidence = left),
            CandidateLine(b, -400, listOf(b), evidence = right)), 40, 10, 10, 1000, false, false)

    @Test fun completeSameBatchChessCanHaveProvisionalGrade() {
        val result = MoveAssessment.from(analysis(), b, null)
        assertEquals(440, result.loss)
        assertEquals(Grade.BLUNDER, result.grade)
        assertEquals(0, result.played.evidence!!.side)
    }

    @Test fun incompleteRestrictedUnknownAndDifferentSourcesCannotGrade() {
        val original = evidence()
        for (changed in listOf(original.copy(targetReached = false), original.copy(finished = false),
            original.copy(restricted = true), original.copy(batch = "other"), original.copy(side = 1),
            original.copy(engineId = "other"), original.copy(models = listOf("new:hash")),
            original.copy(depth = 9), original.copy(budgetMillis = 500), original.copy(settings = "MultiPV=1"))) {
            val result = MoveAssessment.from(analysis(original, changed), b, null)
            assertFalse(result.comparable)
            assertNull(result.loss)
            assertNull(result.grade)
        }
        assertNull(MoveAssessment.from(analysis(null, null), b, null).grade)
        assertNull(MoveAssessment.from(analysis().copy(limited = true), b, null).grade)
        assertNull(MoveAssessment.from(analysis().copy(heuristic = true), b, null).grade)
    }

    @Test fun sameDepthRestrictedSupplementStillCannotCompare() {
        val original = evidence()
        assertEquals(original.depth, original.copy(restricted = true).depth)
        assertFalse(original.comparableWith(original.copy(restricted = true)))
    }

    @Test fun goAndXiangqiNeverUseChessThresholds() {
        for (kind in listOf("go", "xiangqi")) {
            val source = evidence().copy(kind = kind, unit = if (kind == "go") "points" else "cp")
            val result = MoveAssessment.from(analysis(source), b, null)
            assertEquals(440, result.loss)
            assertNull(result.grade)
        }
        val go = evidence().copy(kind = "go", unit = "points")
        val value = analysis(go).let { it.copy(candidates = it.candidates.map { c -> if (c.move == b) c.copy(score = 80) else c }) }
        assertEquals(-40, MoveAssessment.from(value, b, null).loss)
    }

    @Test fun mateIsNeverConvertedToPawnLoss() {
        for (mate in listOf(-3, 0, 2)) {
            val base = analysis()
            val value = base.copy(candidates = base.candidates.map { if (it.move == b) it.copy(mateIn = mate) else it })
            val result = MoveAssessment.from(value, b, null)
            assertNull(result.loss)
            assertNull(result.grade)
            assertEquals(mate, result.played.mateIn)
        }
    }

    @Test fun cachedAnalysisRetainsOriginalSearchAndBatch() {
        val base = analysis().copy(searchMillis = 800)
        val cached = base.cachedResult()
        assertEquals(0L, cached.searchMillis)
        assertEquals(800L, cached.candidates.first().evidence!!.elapsedMillis)
        assertEquals(5_000_000_000L, cached.candidates.first().evidence!!.work)
        assertTrue(cached.candidates.first().evidence!!.cached)
        assertTrue(base.candidates.first().evidence!!.comparableWith(cached.candidates.first().evidence!!))
    }

    @Test fun protocolPreservesLongNodeCountAndSignedMate() {
        val value = UciProtocol.parse("info depth 10 score cp 25 nodes 5000000000 pv e2e4")!!
        assertEquals(5_000_000_000L, value.totalNodes)
        assertEquals(Int.MAX_VALUE, value.nodes)
        assertEquals(0, UciProtocol.parse("info depth 1 score mate 0 pv e2e4")!!.mate)
        assertEquals(-2, UciProtocol.parse("info depth 1 score mate -2 pv e2e4")!!.mate)
    }
}
