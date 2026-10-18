package com.sanqi.coach.engine

import com.sanqi.coach.coach.CandidateLine
import com.sanqi.coach.coach.PositionAnalysis
import com.sanqi.coach.core.GameEngine
import com.sanqi.coach.core.Move
import com.sanqi.coach.core.go.GoPosition
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

class KataGoClient(
    private val rules: GameEngine<Any>,
    private val store: EngineModelStore,
    private val spec: EngineModelStore.Engine
) : AnalysisBackend<Any> {
    @Volatile private var active: EngineProcess? = null

    @Synchronized
    override fun analyze(position: Any, history: List<Move>, level: Int, forcedMove: Move?, cancelled: () -> Unit): PositionAnalysis {
        val pos = position as GoPosition
        store.requireModels(spec, cancelled)
        cancelled()
        val directory = store.directory(spec)
        val existing = active
        val process = if (existing != null && existing.isAlive) existing else {
            val config = directory.resolve("analysis.cfg")
            config.writeText("logToStderr = true\nlogAllRequests = false\nnumAnalysisThreads = 1\nnumSearchThreads = 2\nnnMaxBatchSize = 1\nreportAnalysisWinratesAs = SIDETOMOVE\nanalysisPVLen = 12\n")
            EngineProcess(store.executable(spec), directory,
                listOf("analysis", "-config", config.absolutePath, "-model", directory.resolve(spec.models.first().name).absolutePath))
        }
        active = process
        try {
            val moves = JSONArray()
            for ((i, move) in history.withIndex()) moves.put(JSONArray().put(if (i % 2 == 0) "B" else "W").put(PositionCodec.go(move, pos.size)))
            val initial = JSONArray()
            if (history.isEmpty()) for (index in pos.board.indices) {
                if (pos.board[index] != 0) initial.put(JSONArray().put(if (pos.board[index] == 1) "B" else "W")
                    .put(PositionCodec.go(Move(index, index), pos.size)))
            }
            val template = JSONObject().put("boardXSize", pos.size).put("boardYSize", pos.size)
                .put("rules", "chinese").put("komi", 7.5).put("moves", moves).put("initialStones", initial)
                .put("initialPlayer", if (history.isEmpty() && pos.toMove == 1) "W" else "B")
                .put("maxVisits", when (level) { 2 -> 64; 4 -> 512; else -> 192 })
                .put("overrideSettings", JSONObject().put("maxTime", when (level) { 2 -> 2; 4 -> 8; else -> 4 }))
                .put("includeOwnership", false)
            fun query(only: Move?): PositionAnalysis {
                val id = UUID.randomUUID().toString()
                val request = JSONObject(template.toString()).put("id", id)
                if (only != null) request.put("allowMoves", JSONArray().put(JSONObject()
                    .put("player", if (pos.toMove == 0) "B" else "W").put("moves", JSONArray().put(PositionCodec.go(only, pos.size))).put("untilDepth", 1)))
                process.send(request.toString())
                val deadline = System.nanoTime() + 90_000_000_000L
                while (true) {
                    val line = process.read(deadline, cancelled)
                    if (!line.startsWith("{")) continue
                    val result = JSONObject(line)
                    if (result.optString("id") != id) continue
                    if (result.has("error")) throw EngineException(EngineProblem.PROTOCOL)
                    if (result.optBoolean("isDuringSearch", false)) continue
                    val infos = result.getJSONArray("moveInfos")
                    val candidates = (0 until infos.length()).map { infos.getJSONObject(it) }
                        .sortedBy { it.getInt("order") }.map { info ->
                            val pv = info.getJSONArray("pv")
                            var current = rules.copyPosition(position)
                            val variation = (0 until minOf(12, pv.length())).map { index ->
                                val move = PositionCodec.parseGo(pv.getString(index), pos.size)
                                if (move !in rules.legalMoves(current)) throw EngineException(EngineProblem.INVALID_LINE)
                                current = rules.applyMove(current, move).position
                                move
                            }
                            if (variation.isEmpty()) throw EngineException(EngineProblem.PROTOCOL)
                            val winrate = info.getDouble("winrate")
                            val lead = info.getDouble("scoreLead")
                            if (winrate !in 0.0..1.0 || !lead.isFinite()) throw EngineException(EngineProblem.PROTOCOL)
                            CandidateLine(variation.first(), (lead * 100).roundToInt(), variation, winrate = winrate)
                        }
                    if (candidates.isEmpty()) throw EngineException(EngineProblem.PROTOCOL)
                    val visits = result.getJSONObject("rootInfo").getInt("visits")
                    return PositionAnalysis(candidates, candidates.first().score, 0, 0, visits, false, false,
                        "${spec.title} ${spec.version}", "points")
                }
            }
            val baseline = query(null)
            if (forcedMove == null || baseline.candidates.any { it.move == forcedMove }) return baseline
            val forced = query(forcedMove)
            return baseline.copy(candidates = baseline.candidates + forced.candidates,
                nodes = baseline.nodes + forced.nodes)
        } catch (error: Exception) {
            process.close()
            if (active === process) active = null
            throw error
        }
    }

    override fun close() { active?.close(); active = null }
}
