package com.sanqi.coach.coach

import com.sanqi.coach.core.GameEngine
import com.sanqi.coach.core.Move
import com.sanqi.coach.core.SearchSupport

class PositionAnalyzer<P : Any>(private val engine: GameEngine<P>) {
    @Suppress("UNCHECKED_CAST")
    private val support: SearchSupport<P>? = engine as? SearchSupport<P>

    private data class Node(val score: Int, val moves: List<Move>)
    private class LimitReached : RuntimeException()

    private class Budget(val maxNodes: Int, timeMillis: Long, val cancelled: () -> Unit) {
        val deadline: Long = System.nanoTime() + timeMillis * 1_000_000L
        var nodes: Int = 0
        fun visit() {
            cancelled()
            if (nodes >= maxNodes || System.nanoTime() >= deadline) throw LimitReached()
            nodes++
        }
    }

    fun analyze(
        position: P,
        depth: Int,
        forcedMove: Move? = null,
        nodeLimit: Int = 2000 * depth.coerceIn(1, 4) * depth.coerceIn(1, 4),
        timeLimitMillis: Long = 700L * depth.coerceIn(1, 4),
        cancelled: () -> Unit = {}
    ): PositionAnalysis {
        val target = depth.coerceIn(1, 4)
        val pos = engine.copyPosition(position)
        cancelled()
        val legal = engine.legalMoves(pos)
        if (forcedMove != null) require(forcedMove in legal)
        val terminal = terminal(pos, legal, 0)
        if (terminal != null) return PositionAnalysis(emptyList(), terminal, 0, target, 0, false, support?.heuristic == true)
        val moves = (support?.searchMoves(pos) ?: legal).toMutableList()
        if (forcedMove != null && forcedMove !in moves) moves.add(forcedMove)
        var complete = moves.map { move ->
            cancelled()
            val child = next(pos, move)
            CandidateLine(move, -leaf(child, 1), listOf(move))
        }.sortedByDescending { it.score }
        var completedDepth = 0
        var limited = false
        val budget = Budget(nodeLimit, timeLimitMillis, cancelled)
        for (iteration in 1..target) {
            try {
                val scored = complete.map { candidate ->
                    budget.visit()
                    val child = next(pos, candidate.move)
                    val node = search(child, iteration - 1, -200000, 200000, 1, budget)
                    CandidateLine(candidate.move, -node.score, listOf(candidate.move) + node.moves)
                }.sortedByDescending { it.score }
                // Only publish a fully evaluated iteration so every candidate has the same horizon.
                complete = scored
                completedDepth = iteration
            } catch (_: LimitReached) {
                limited = true
                break
            }
        }
        return PositionAnalysis(complete, complete.firstOrNull()?.score ?: engine.evaluate(pos),
            completedDepth, target, budget.nodes, limited, support?.heuristic == true)
    }

    private fun next(pos: P, move: Move): P =
        support?.searchPosition(pos, move) ?: engine.applyMove(pos, move).position

    private fun terminal(pos: P, moves: List<Move>, ply: Int): Int? =
        support?.terminalScore(pos, moves, ply) ?: if (support == null && moves.isEmpty()) 0 else null

    private fun leaf(pos: P, ply: Int): Int =
        terminal(pos, engine.legalMoves(pos), ply) ?: engine.evaluate(pos)

    private fun search(pos: P, depth: Int, alpha: Int, beta: Int, ply: Int, budget: Budget): Node {
        budget.visit()
        val legal = engine.legalMoves(pos)
        terminal(pos, legal, ply)?.let { return Node(it, emptyList()) }
        if (depth <= 0) return quiet(pos, legal, alpha, beta, ply, 4, budget)
        val moves = support?.searchMoves(pos) ?: legal
        var best = Node(-200000, emptyList())
        var a = alpha
        for (move in moves) {
            val child = search(next(pos, move), depth - 1, -beta, -a, ply + 1, budget)
            val score = -child.score
            if (score > best.score) best = Node(score, listOf(move) + child.moves)
            if (score > a) a = score
            if (a >= beta) break
        }
        return best
    }

    private fun quiet(pos: P, legal: List<Move>, alpha: Int, beta: Int, ply: Int, left: Int, budget: Budget): Node {
        val checked = support?.inCheck(pos) == true
        val stand = engine.evaluate(pos)
        var best = Node(if (checked) -200000 else stand, emptyList())
        var a = alpha
        if (!checked) {
            if (stand >= beta || left <= 0) return best
            a = maxOf(a, stand)
        }
        val moves = if (checked) legal else legal.filter { support?.isTactical(pos, it) == true }
        for (move in moves) {
            budget.visit()
            val child = next(pos, move)
            val childMoves = engine.legalMoves(child)
            val end = terminal(child, childMoves, ply + 1)
            val node = when {
                end != null -> Node(end, emptyList())
                left <= -2 -> Node(engine.evaluate(child), emptyList())
                else -> quiet(child, childMoves, -beta, -a, ply + 1, left - 1, budget)
            }
            val score = -node.score
            if (score > best.score) best = Node(score, listOf(move) + node.moves)
            if (score > a) a = score
            if (a >= beta) break
        }
        return best
    }
}
