package com.sanqi.coach.coach

import com.sanqi.coach.core.AppliedMove
import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move
import com.sanqi.coach.core.SearchResult
import com.sanqi.coach.core.SearchSupport
import com.sanqi.coach.core.chess.ChessEngine
import com.sanqi.coach.core.go.GoEngine
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class PositionAnalyzerTest {
    private data class Position(val node: Int, val side: Int)
    private class Tree : SearchSupport<Position> {
        override fun initialPosition(): Position = Position(0, 0)
        override fun sideToMove(pos: Position): Int = pos.side
        override fun sideName(side: Int): String = side.toString()
        override fun legalMoves(pos: Position): List<Move> = when (pos.node) {
            0 -> listOf(Move(0, 1), Move(0, 2))
            1 -> listOf(Move(1, 3), Move(1, 4))
            2 -> listOf(Move(2, 5), Move(2, 6))
            else -> emptyList()
        }
        override fun searchPosition(pos: Position, move: Move): Position = Position(move.to, 1 - pos.side)
        override fun applyMove(pos: Position, move: Move): AppliedMove<Position> =
            AppliedMove(searchPosition(pos, move), "${move.from}-${move.to}", 0, false, GameStatus.ONGOING)
        override fun evaluate(pos: Position): Int = when (pos.node) { 3 -> 30; 4 -> 10; 5 -> 40; 6 -> 20; else -> 0 }
        override fun searchBest(pos: Position, depth: Int): SearchResult = SearchResult(null, evaluate(pos))
        override fun copyPosition(pos: Position): Position = pos.copy()
        override fun squareDesc(pos: Position, square: Int): String = square.toString()
        override fun inCheck(pos: Position): Boolean = false
        override fun isTactical(pos: Position, move: Move): Boolean = false
        override fun terminalScore(pos: Position, moves: List<Move>, ply: Int): Int? = if (moves.isEmpty()) evaluate(pos) else null
    }

    @Test
    fun candidatesUseSameHorizonAndCarryContinuousLines() {
        val engine = Tree()
        val result = PositionAnalyzer(engine).analyze(engine.initialPosition(), 2, nodeLimit = 1000, timeLimitMillis = 10000)
        assertEquals(2, result.completedDepth)
        assertEquals(listOf(20, 10), result.candidates.map { it.score })
        assertEquals(listOf(Move(0, 2), Move(2, 6)), result.candidates.first().moves)
        for (line in result.candidates) {
            var position = engine.initialPosition()
            for (move in line.moves) {
                assertTrue(move in engine.legalMoves(position))
                position = engine.searchPosition(position, move)
            }
        }
    }

    @Test
    fun incompleteIterationDoesNotMixCandidateScores() {
        val engine = Tree()
        val result = PositionAnalyzer(engine).analyze(engine.initialPosition(), 3, nodeLimit = 1, timeLimitMillis = 10000)
        assertTrue(result.limited)
        assertEquals(0, result.completedDepth)
        assertEquals(listOf(0, 0), result.candidates.map { it.score })
    }

    @Test
    fun cancellationIsNotReportedAsCompletedAnalysis() {
        val engine = Tree()
        try {
            PositionAnalyzer(engine).analyze(engine.initialPosition(), 2, cancelled = { throw CancellationException() })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, engine.initialPosition().node)
        }
    }

    @Test
    fun forcedGoCandidateIsIncludedEvenOutsideShortlist() {
        val engine = GoEngine(9)
        val move = Move(80, 80)
        val result = PositionAnalyzer(engine).analyze(engine.initialPosition(), 1, move, nodeLimit = 0)
        assertTrue(result.candidates.any { it.move == move })
        assertTrue(result.heuristic)
    }

    @Test
    fun chessCandidatesReplayLegally() {
        val engine = ChessEngine()
        val initial = engine.initialPosition()
        val result = PositionAnalyzer(engine).analyze(initial, 2, nodeLimit = 1000, timeLimitMillis = 10000)
        assertEquals(20, result.candidates.size)
        for (line in result.candidates.take(3)) {
            var position = initial
            for (move in line.moves) {
                assertTrue(move in engine.legalMoves(position))
                position = engine.searchPosition(position, move)
            }
        }
        assertArrayEquals(engine.initialPosition().board, initial.board)
    }
}
