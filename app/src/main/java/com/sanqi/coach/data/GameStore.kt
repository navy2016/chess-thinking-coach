package com.sanqi.coach.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredMove(
    val from: Int,
    val to: Int,
    val promo: Int,
    val notation: String,
    val loss: Int,
    val gradeName: String,
    val explanation: String
)

data class StoredGame(
    val id: String,
    val kind: String,
    val mode: String,
    val aiDepth: Int,
    val boardSize: Int,
    val startedAt: Long,
    val updatedAt: Long,
    val finished: Boolean,
    val resultText: String,
    val moves: List<StoredMove>,
    val deadStones: List<Int> = emptyList()
)

/**
 * 本地对局存储：每局一个 JSON 文件，存 filesDir/games/。
 */
object GameStore {

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "games")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun save(ctx: Context, game: StoredGame) {
        val arr = JSONArray()
        for (m in game.moves) {
            val o = JSONObject()
            o.put("from", m.from)
            o.put("to", m.to)
            o.put("promo", m.promo)
            o.put("notation", m.notation)
            o.put("loss", m.loss)
            o.put("grade", m.gradeName)
            o.put("explanation", m.explanation)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("id", game.id)
        root.put("kind", game.kind)
        root.put("mode", game.mode)
        root.put("aiDepth", game.aiDepth)
        root.put("boardSize", game.boardSize)
        root.put("startedAt", game.startedAt)
        root.put("updatedAt", game.updatedAt)
        root.put("finished", game.finished)
        root.put("result", game.resultText)
        root.put("moves", arr)
        root.put("deadStones", JSONArray(game.deadStones))
        val file = AtomicFile(File(dir(ctx), game.id + ".json"))
        val stream = file.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun list(ctx: Context): List<StoredGame> {
        val out = mutableListOf<StoredGame>()
        val files = dir(ctx).listFiles() ?: return out
        val records = files.filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
            .map { File(it.parentFile, it.name.removeSuffix(".bak")) }.distinctBy { it.name }
        for (f in records) {
            try {
                val o = JSONObject(AtomicFile(f).openRead().bufferedReader().use { it.readText() })
                out.add(parse(f, o))
            } catch (_: Exception) {
            }
        }
        out.sortByDescending { it.updatedAt }
        return out
    }

    fun load(ctx: Context, id: String): StoredGame? {
        val f = File(dir(ctx), id + ".json")
        if (!f.exists() && !File(f.path + ".bak").exists()) return null
        return try {
            parse(f, JSONObject(AtomicFile(f).openRead().bufferedReader().use { it.readText() }))
        } catch (_: Exception) {
            null
        }
    }

    fun delete(ctx: Context, id: String) {
        val f = File(dir(ctx), id + ".json")
        AtomicFile(f).delete()
    }

    private fun parse(f: File, o: JSONObject): StoredGame {
        val arr = o.getJSONArray("moves")
        val moves = mutableListOf<StoredMove>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            moves.add(
                StoredMove(
                    m.optInt("from", -1),
                    m.optInt("to", -1),
                    m.optInt("promo", 0),
                    m.optString("notation", ""),
                    m.optInt("loss", 0),
                    m.optString("grade", ""),
                    m.optString("explanation", "")
                )
            )
        }
        return StoredGame(
            id = o.optString("id", f.name.removeSuffix(".json")),
            kind = o.optString("kind", ""),
            mode = o.optString("mode", "TWO_PLAYER"),
            aiDepth = o.optInt("aiDepth", 3),
            boardSize = o.optInt("boardSize", 19),
            startedAt = o.optLong("startedAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
            finished = o.optBoolean("finished", false),
            resultText = o.optString("result", ""),
            moves = moves,
            deadStones = o.optJSONArray("deadStones")?.let { marks -> List(marks.length()) { marks.getInt(it) } }.orEmpty()
        )
    }
}
