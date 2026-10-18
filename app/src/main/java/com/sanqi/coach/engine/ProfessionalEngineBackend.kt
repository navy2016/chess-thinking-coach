package com.sanqi.coach.engine

import android.content.Context
import com.sanqi.coach.coach.PositionAnalysis
import com.sanqi.coach.core.GameEngine
import com.sanqi.coach.core.Move

class ProfessionalEngineBackend(context: Context, private val kind: String, private val rules: GameEngine<Any>) : AnalysisBackend<Any> {
    private val store = EngineModelStore(context)
    @Volatile private var client: AnalysisBackend<Any>? = null

    override fun analyze(position: Any, history: List<Move>, level: Int, forcedMove: Move?, cancelled: () -> Unit): PositionAnalysis {
        var engine = client
        if (engine == null) {
            val id = when (kind) { "chess" -> "stockfish"; "xiangqi" -> "pikafish"; else -> "katago" }
            val spec = try { store.catalog().first { it.id == id } } catch (_: Exception) { throw EngineException(EngineProblem.NOT_PACKAGED) }
            engine = if (kind == "go") KataGoClient(rules, store, spec) else UciEngineClient(kind, rules, store, spec)
            client = engine
        }
        return engine.analyze(position, history, level, forcedMove, cancelled)
    }

    override fun close() { client?.close() }
}
