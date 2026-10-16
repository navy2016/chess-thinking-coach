package com.sanqi.coach.content

import android.content.Context
import com.sanqi.coach.core.Move
import org.json.JSONArray
import org.json.JSONObject

data class StudyItem(
    val id: String,
    val kind: String,
    val title: String,
    val desc: String,
    val setupBoard: IntArray?,
    val size: Int,
    val moves: List<Move>,
    val notes: List<String>
)

data class PuzzleItem(
    val id: String,
    val kind: String,
    val title: String,
    val desc: String,
    val goal: String,
    val board: IntArray,
    val size: Int,
    val toMove: Int,
    val solution: Move,
    val teach: String
)

object Content {

    private val KINDS = listOf("go", "xiangqi", "chess")

    fun studies(ctx: Context): List<StudyItem> {
        val out = mutableListOf<StudyItem>()
        for (kind in KINDS) {
            val text = readAsset(ctx, "study/$kind.json") ?: continue
            try {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val movesArr = o.getJSONArray("moves")
                    val moves = mutableListOf<Move>()
                    for (j in 0 until movesArr.length()) {
                        val m = movesArr.getJSONObject(j)
                        moves.add(Move(m.getInt("from"), m.getInt("to"), m.optInt("promo", 0)))
                    }
                    val notesArr = o.optJSONArray("notes")
                    val notes = mutableListOf<String>()
                    if (notesArr != null) {
                        for (j in 0 until notesArr.length()) notes.add(notesArr.optString(j, ""))
                    }
                    val setup = o.optJSONArray("setup")
                    val setupBoard = if (setup == null) null else IntArray(setup.length()) { setup.getInt(it) }
                    out.add(
                        StudyItem(
                            id = o.getString("id"),
                            kind = kind,
                            title = o.getString("title"),
                            desc = o.getString("desc"),
                            setupBoard = setupBoard,
                            size = o.optInt("size", 9),
                            moves = moves,
                            notes = notes
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun puzzles(ctx: Context): List<PuzzleItem> {
        val out = mutableListOf<PuzzleItem>()
        for (kind in KINDS) {
            val text = readAsset(ctx, "puzzles/$kind.json") ?: continue
            try {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o: JSONObject = arr.getJSONObject(i)
                    val boardArr = o.getJSONArray("board")
                    val sol = o.getJSONObject("solution")
                    out.add(
                        PuzzleItem(
                            id = o.getString("id"),
                            kind = kind,
                            title = o.getString("title"),
                            desc = o.getString("desc"),
                            goal = o.optString("goal", "mate"),
                            board = IntArray(boardArr.length()) { boardArr.getInt(it) },
                            size = o.optInt("size", 9),
                            toMove = o.optInt("toMove", 0),
                            solution = Move(sol.getInt("from"), sol.getInt("to"), sol.optInt("promo", 0)),
                            teach = o.optString("teach", "")
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun readAsset(ctx: Context, path: String): String? = try {
        ctx.assets.open(path).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }
}
