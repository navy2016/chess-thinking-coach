package com.sanqi.coach.data

import com.sanqi.coach.coach.CandidateLine
import com.sanqi.coach.coach.MoveAssessment
import com.sanqi.coach.coach.SearchEvidence
import com.sanqi.coach.core.Move
import org.json.JSONArray
import org.json.JSONObject

internal object AnalysisRecord {
    fun encode(value: MoveAssessment): JSONObject = JSONObject().put("version", 1).put("kind", value.kind)
        .put("reference", line(value.reference)).put("played", line(value.played))
        .put("reply", value.reply?.let { line(it) }).put("comparable", value.comparable)

    fun decode(value: JSONObject?): MoveAssessment? {
        if (value == null || value.optInt("version") != 1) return null
        return try {
            val kind = value.getString("kind")
            require(kind in listOf("go", "xiangqi", "chess"))
            val reference = readLine(value.getJSONObject("reference"))
            val played = readLine(value.getJSONObject("played"))
            val reply = value.optJSONObject("reply")?.let { readLine(it) }
            val unit = if (kind == "go") "points" else "cp"
            for (line in listOfNotNull(reference, played, reply)) {
                line.evidence?.let { require(it.kind == kind && it.unit == unit) }
            }
            val a = reference.evidence
            val b = played.evidence
            MoveAssessment(kind, reference, played, reply, value.getBoolean("comparable") &&
                    a != null && b != null && a.comparableWith(b))
        } catch (_: Exception) {
            null
        }
    }

    private fun line(value: CandidateLine): JSONObject = JSONObject().put("score", value.score)
        .put("mate", value.mateIn).put("winrate", value.winrate)
        .put("moves", JSONArray().apply {
            for (move in value.moves) put(JSONArray().put(move.from).put(move.to).put(move.promo))
        }).put("source", value.evidence?.let { e ->
            JSONObject().put("batch", e.batch).put("kind", e.kind).put("engine", e.engineId)
                .put("engineVersion", e.engineVersion).put("models", JSONArray(e.models))
                .put("unit", e.unit).put("scale", 100).put("side", e.side).put("budgetMillis", e.budgetMillis)
                .put("depth", e.depth).put("targetDepth", e.targetDepth).put("work", e.work)
                .put("targetVisits", e.targetVisits).put("elapsedMillis", e.elapsedMillis)
                .put("finished", e.finished).put("targetReached", e.targetReached)
                .put("restricted", e.restricted).put("settings", e.settings).put("cached", e.cached)
        })

    private fun readLine(value: JSONObject): CandidateLine {
        val moves = value.getJSONArray("moves").let { list ->
            List(list.length()) { i -> list.getJSONArray(i).let { Move(it.getInt(0), it.getInt(1), it.getInt(2)) } }
        }
        require(moves.isNotEmpty())
        val evidence = value.optJSONObject("source")?.let { e ->
            require(e.getInt("scale") == 100 && e.getInt("side") in 0..1)
            SearchEvidence(e.getString("batch"), e.getString("kind"), e.getString("engine"),
                e.getString("engineVersion"), e.getJSONArray("models").let { list -> List(list.length()) { list.getString(it) } },
                e.getString("unit"), e.getInt("side"), e.getInt("budgetMillis"), e.getInt("depth"),
                e.getInt("targetDepth"), e.getLong("work"), e.getInt("targetVisits"), e.getLong("elapsedMillis"),
                e.getBoolean("finished"), e.getBoolean("targetReached"), e.getBoolean("restricted"),
                e.getString("settings"), e.getBoolean("cached"))
        }
        return CandidateLine(moves.first(), value.getInt("score"), moves,
            if (value.isNull("mate")) null else value.getInt("mate"),
            if (value.isNull("winrate")) null else value.getDouble("winrate"), evidence)
    }
}
