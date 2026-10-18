package com.sanqi.coach.coach

import com.sanqi.coach.core.Move

data class CandidateLine(val move: Move, val score: Int, val moves: List<Move>)

data class PositionAnalysis(
    val candidates: List<CandidateLine>,
    val score: Int,
    val completedDepth: Int,
    val requestedDepth: Int,
    val nodes: Int,
    val limited: Boolean,
    val heuristic: Boolean
)
