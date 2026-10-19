package com.sanqi.coach.engine

import com.sanqi.coach.coach.CandidateLine
import com.sanqi.coach.coach.PositionAnalysis
import com.sanqi.coach.core.GameEngine
import com.sanqi.coach.core.Move

class UciEngineClient(
    private val kind: String,
    private val rules: GameEngine<Any>,
    private val store: EngineModelStore,
    private val spec: EngineModelStore.Engine
) : AnalysisBackend<Any> {
    @Volatile private var active: EngineProcess? = null
    private val fairy = spec.binary == "fairystockfish"

    @Synchronized
    override fun analyze(position: Any, history: List<Move>, level: Int, forcedMove: Move?, cancelled: () -> Unit,
                         budgetMillis: Int, onProgress: (PositionAnalysis) -> Unit): PositionAnalysis {
        store.requireModels(spec, cancelled)
        cancelled()
        val existing = active
        val fresh = existing == null || !existing.isAlive
        val process = if (fresh) EngineProcess(store.executable(spec), store.directory(spec)) else existing!!
        active = process
        try {
            if (fresh) {
                process.send("uci")
                process.await("uciok", 20, cancelled)
                process.send("setoption name Threads value 2")
                process.send("setoption name Hash value 64")
                if (fairy) {
                    process.send("setoption name Use NNUE value false")
                    process.send("setoption name UCI_Variant value $kind")
                }
                if (spec.models.isNotEmpty()) process.send("setoption name EvalFile value ${store.directory(spec).resolve(spec.models.first().name).absolutePath}")
                if (kind == "chess" && spec.models.size > 1) process.send("setoption name EvalFileSmall value ${store.directory(spec).resolve(spec.models[1].name).absolutePath}")
                process.send("isready")
                process.await("readyok", 40, cancelled)
            }
            val positionCommand = if (history.isNotEmpty()) "position startpos moves " + history.joinToString(" ") { PositionCodec.uci(it, kind, fairy) }
                else "position fen ${PositionCodec.fen(position)}"
            val legal = rules.legalMoves(position)
            val count = minOf(3, legal.size)
            if (count == 0) return PositionAnalysis(emptyList(), 0, 0, 0, 0, false, false, spec.title)
            val depth = when (level) { 2 -> 10; 4 -> 18; else -> 14 }
            var searchMillis = 0L
            var lastProgress = 0L
            fun search(only: Move? = null): List<UciProtocol.Line> {
                val millis = if (only == null && forcedMove != null) budgetMillis / 2
                    else (budgetMillis - searchMillis).coerceAtLeast(0).toInt()
                if (millis <= 0) throw EngineException(EngineProblem.TIMEOUT)
                val started = System.nanoTime()
                process.send("setoption name MultiPV value ${if (only == null) count else 1}")
                process.send(positionCommand)
                process.send("go depth $depth movetime $millis" + if (only == null) "" else " searchmoves ${PositionCodec.uci(only, kind, fairy)}")
                val rounds = mutableMapOf<Int, MutableMap<Int, UciProtocol.Line>>()
                val deadline = System.nanoTime() + (millis + 10000L) * 1_000_000
                while (true) {
                    val line = process.read(deadline, cancelled)
                    if (line.startsWith("bestmove ")) break
                    UciProtocol.parse(line)?.let { value ->
                        rounds.getOrPut(value.depth) { mutableMapOf() }[value.rank] = value
                        val now = System.nanoTime()
                        if (value.rank == 1 && now - lastProgress >= 200_000_000L) {
                            lastProgress = now
                            onProgress(PositionAnalysis(emptyList(), value.score, value.depth, depth, value.nodes,
                                true, false, spec.title, comparable = false))
                        }
                        if (rounds.size > 4) rounds.remove(rounds.keys.minOrNull())
                    }
                }
                searchMillis += (System.nanoTime() - started) / 1_000_000L
                val needed = if (only == null) count else 1
                return rounds.entries.filter { (_, lines) -> (1..needed).all { it in lines } }
                    .maxByOrNull { it.key }?.value?.values?.sortedBy { it.rank } ?: throw EngineException(EngineProblem.PROTOCOL)
            }
            val lines = search().toMutableList()
            var comparable = true
            if (forcedMove != null && lines.none { PositionCodec.parseUci(it.pv.first(), kind, fairy) == forcedMove }) {
                val forced = search(forcedMove).first()
                comparable = forced.depth == lines.first().depth
                lines.add(forced)
            }
            val candidates = lines.map { line ->
                var pos = rules.copyPosition(position)
                val moves = line.pv.take(16).map { token ->
                    val move = PositionCodec.parseUci(token, kind, fairy)
                    if (move !in rules.legalMoves(pos)) throw EngineException(EngineProblem.INVALID_LINE)
                    pos = rules.applyMove(pos, move).position
                    move
                }
                CandidateLine(moves.first(), line.score, moves, line.mate)
            }.distinctBy { it.move }.sortedByDescending { it.score }
            return PositionAnalysis(candidates, candidates.first().score, lines.minOf { it.depth }, depth,
                lines.maxOf { it.nodes }, lines.minOf { it.depth } < depth, false, "${spec.title} ${spec.version}", "cp", comparable && lines.all { it.mate == null }, searchMillis)
        } catch (error: Exception) {
            process.close()
            if (active === process) active = null
            throw error
        }
    }

    override fun close() { active?.close(); active = null }
}
