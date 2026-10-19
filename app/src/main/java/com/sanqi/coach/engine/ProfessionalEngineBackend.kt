package com.sanqi.coach.engine

import android.content.Context
import com.sanqi.coach.coach.PositionAnalysis
import com.sanqi.coach.core.GameEngine
import com.sanqi.coach.core.Move
import com.sanqi.coach.core.go.GoPosition

class ProfessionalEngineBackend(context: Context, private val kind: String, private val rules: GameEngine<Any>) : AnalysisBackend<Any> {
    private val store = EngineModelStore(context)
    @Volatile private var client: AnalysisBackend<Any>? = null
    private var selected = ""
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var cacheGeneration = 0L
    private data class Key(val position: String, val history: List<Move>, val level: Int, val millis: Int, val forced: Move?)
    private val cache = LinkedHashMap<Key, PositionAnalysis>()

    @Synchronized
    override fun analyze(position: Any, history: List<Move>, level: Int, forcedMove: Move?, cancelled: () -> Unit,
                         budgetMillis: Int, onProgress: (PositionAnalysis) -> Unit): PositionAnalysis {
        cancelled()
        val token = generation.get()
        val spec = try { store.selected(kind) } catch (_: Exception) { throw EngineException(EngineProblem.NOT_PACKAGED) }
        val identity = "${spec.id}:${spec.version}:${spec.models.joinToString { it.sha256 }}"
        if (identity != selected || cacheGeneration != token) {
            client?.close()
            client = null
            cache.clear()
            selected = identity
            cacheGeneration = token
        }
        val snapshot = if (position is GoPosition) {
            "${position.size}:${position.toMove}:${position.koPoint}:${position.consecutivePasses}:" +
                position.captures.joinToString(",") + ":" + position.board.joinToString(",")
        } else PositionCodec.fen(position)
        val key = Key(snapshot, history.toList(), level, budgetMillis, forcedMove)
        val cached = cache[key] ?: cache[key.copy(forced = null)]?.takeIf { result ->
            forcedMove == null || result.candidates.any { it.move == forcedMove }
        }
        if (cached != null) {
            cancelled()
            onProgress(cached)
            return cached.copy(searchMillis = 0)
        }
        val engine = client ?: (if (kind == "go") KataGoClient(rules, store, spec)
            else UciEngineClient(kind, rules, store, spec)).also { client = it }
        val result = engine.analyze(position, history, level, forcedMove, cancelled, budgetMillis, onProgress)
        cancelled()
        if (generation.get() == token) {
            cache[key] = result
            if (cache.size > 12) cache.remove(cache.keys.first())
        }
        return result
    }

    override fun close() {
        generation.incrementAndGet()
        client?.close()
    }
}
