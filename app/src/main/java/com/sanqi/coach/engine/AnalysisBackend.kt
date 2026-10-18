package com.sanqi.coach.engine

import com.sanqi.coach.coach.PositionAnalysis
import com.sanqi.coach.core.Move

interface AnalysisBackend<P : Any> {
    fun analyze(position: P, history: List<Move>, level: Int, forcedMove: Move?, cancelled: () -> Unit): PositionAnalysis
    fun close()
}

enum class EngineProblem { NOT_PACKAGED, MODEL_MISSING, PROTOCOL, TIMEOUT, INVALID_LINE }
class EngineException(val problem: EngineProblem) : IllegalStateException(problem.name)
