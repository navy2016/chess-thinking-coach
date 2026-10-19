package com.sanqi.coach.content

import android.content.Context
import com.sanqi.coach.core.AppliedMove
import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

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
) {
    fun solvedBy(applied: AppliedMove<*>): Boolean = when (goal) {
        "mate" -> applied.status == GameStatus.CHECKMATE
        "capture" -> applied.captured > 0
        else -> false
    }
}

object Content {
    private val KINDS = listOf("go", "xiangqi", "chess")

    fun studies(ctx: Context): List<StudyItem> = KINDS.flatMap { kind ->
        try {
            parseStudies(kind, ctx.assets.open("study/$kind.json").bufferedReader().use { it.readText() }) { resolve(ctx, it) }
        } catch (_: Exception) { emptyList() }
    }

    fun puzzles(ctx: Context): List<PuzzleItem> = KINDS.flatMap { kind ->
        try {
            parsePuzzles(kind, ctx.assets.open("puzzles/$kind.json").bufferedReader().use { it.readText() }) { resolve(ctx, it) }
        } catch (_: Exception) { emptyList() }
    }

    fun parseStudies(kind: String, text: String, localize: (String) -> String = { it }): List<StudyItem> {
        val arr = JSONArray(text)
        return List(arr.length()) { index ->
            val o = arr.getJSONObject(index)
            val setup = o.optJSONArray("setup")?.let { list -> IntArray(list.length()) { list.getInt(it) } }
            val size = o.getInt("size")
            if (setup != null) validateBoard(kind, size, setup)
            val moves = o.getJSONArray("moves").let { list -> List(list.length()) { move(list.getJSONObject(it)) } }
            val notes = o.getJSONArray("notes").let { list -> List(list.length()) { localize(list.getString(it)) } }
            require(moves.isNotEmpty() && notes.size == moves.size)
            StudyItem(o.getString("id"), kind, localize(o.getString("title")), localize(o.getString("desc")),
                setup, size, moves, notes)
        }
    }

    fun parsePuzzles(kind: String, text: String, localize: (String) -> String = { it }): List<PuzzleItem> {
        val arr = JSONArray(text)
        return List(arr.length()) { index ->
            val o = arr.getJSONObject(index)
            val board = o.getJSONArray("board").let { list -> IntArray(list.length()) { list.getInt(it) } }
            val size = o.getInt("size")
            validateBoard(kind, size, board)
            val side = o.getInt("toMove")
            val goal = o.getString("goal")
            require(side in 0..1 && goal in listOf("mate", "capture"))
            PuzzleItem(o.getString("id"), kind, localize(o.getString("title")), localize(o.getString("desc")),
                goal, board, size, side, move(o.getJSONObject("solution")), localize(o.getString("teach")))
        }
    }

    private fun move(o: JSONObject): Move = Move(o.getInt("from"), o.getInt("to"), o.optInt("promo", 0))

    private fun resolve(ctx: Context, value: String): String {
        if (!value.startsWith("@string/")) return value
        val id = ctx.resources.getIdentifier(value.removePrefix("@string/"), "string", ctx.packageName)
        require(id != 0)
        return ctx.getString(id)
    }

    private fun validateBoard(kind: String, size: Int, board: IntArray) {
        when (kind) {
            "go" -> require(size in listOf(9, 13, 19) && board.size == size * size && board.all { it in 0..2 })
            "chess" -> require(size == 8 && board.size == 64 && board.all { abs(it) <= 6 } &&
                board.count { it == 1 } == 1 && board.count { it == -1 } == 1)
            "xiangqi" -> {
                require(size == 10 && board.size == 90 && board.all { abs(it) <= 7 })
                for (side in listOf(-1, 1)) {
                    require(board.count { it == side } == 1)
                    for (piece in 2..7) require(board.count { it == side * piece } <= if (piece == 7) 5 else 2)
                }
            }
            else -> error(kind)
        }
    }
}
