package com.sanqi.coach.engine

import com.sanqi.coach.coach.PositionAnalysis
import com.sanqi.coach.core.Move

fun analysisMillis(level: Int): Int = when (level) { 2 -> 2000; 4 -> 10000; else -> 5000 }

interface AnalysisBackend<P : Any> {
    fun analyze(position: P, history: List<Move>, level: Int, forcedMove: Move?, cancelled: () -> Unit,
                budgetMillis: Int = analysisMillis(level), onProgress: (PositionAnalysis) -> Unit = {}): PositionAnalysis
    fun close()
}

enum class EngineProblem { NOT_PACKAGED, MODEL_MISSING, PROTOCOL, TIMEOUT, INVALID_LINE }
class EngineException(val problem: EngineProblem) : IllegalStateException(problem.name)
