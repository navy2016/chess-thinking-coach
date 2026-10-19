package com.sanqi.coach.content

import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move
import com.sanqi.coach.core.go.GoPosition
import com.sanqi.coach.ui.GameKit
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ContentLegalityTest {
    private fun asset(path: String): String = File(System.getProperty("coach.assets"), path).readText()
    private val kinds = listOf("go", "xiangqi", "chess")

    @Test fun everyStudyReplaysOnlyLegalMoves() {
        val ids = mutableSetOf<String>()
        var count = 0
        for (kind in kinds) {
            val items = Content.parseStudies(kind, asset("study/$kind.json"))
            assertTrue(items.isNotEmpty())
            for (item in items) {
                assertTrue(ids.add(item.id))
                val kit = GameKit(kind, item.size)
                var position = item.setupBoard?.let { kit.positionFromBoard(it, 0) } ?: kit.engine.initialPosition()
                var status = GameStatus.ONGOING
                for ((index, move) in item.moves.withIndex()) {
                    assertTrue("${item.id} ply ${index + 1}: $move", move in kit.engine.legalMoves(position))
                    val applied = kit.engine.applyMove(position, move)
                    position = applied.position
                    status = applied.status
                    if (item.id == "go-capture" && index == 0) assertEquals(1, applied.captured)
                    if (item.id == "xq-sha-fa") assertTrue(applied.notation.startsWith("车九平五"))
                }
                if (item.id in listOf("chess-opera", "xq-sha-fa")) assertEquals(GameStatus.CHECKMATE, status)
                assertEquals(item.moves.size, item.notes.size)
                count++
            }
        }
        assertEquals(6, count)
    }

    @Test fun everyPuzzleSolutionActuallyMeetsItsGoal() {
        val ids = mutableSetOf<String>()
        var count = 0
        for (kind in kinds) {
            val items = Content.parsePuzzles(kind, asset("puzzles/$kind.json"))
            assertTrue(items.isNotEmpty())
            for (item in items) {
                assertTrue(ids.add(item.id))
                val kit = GameKit(kind, item.size)
                val position = kit.positionFromBoard(item.board, item.toMove)
                assertTrue(item.id, item.solution in kit.engine.legalMoves(position))
                val result = kit.engine.applyMove(position, item.solution)
                assertTrue(item.id, item.solvedBy(result))
                when (item.id) {
                    "go-cap1" -> assertEquals(1, result.captured)
                    "go-cap2" -> assertEquals(2, result.captured)
                    "xq-shuangche" -> {
                        assertEquals(2, item.board.count { it == 5 })
                        assertTrue(result.notation.startsWith("车九平五"))
                    }
                }
                count++
            }
        }
        assertEquals(5, count)
    }

    @Test fun originalIncorrectGoAnswerDoesNotPassWithoutCapture() {
        val item = Content.parsePuzzles("go", asset("puzzles/go.json")).first { it.id == "go-cap1" }
        val board = IntArray(81)
        board[10] = 2
        board[11] = 1
        board[19] = 1
        val kit = GameKit("go", 9)
        val pos = kit.positionFromBoard(board, 0)
        val result = kit.engine.applyMove(pos, Move(0, 0))
        assertEquals(0, result.captured)
        assertFalse(item.solvedBy(result))
        assertFalse(item.copy(goal = "unknown").solvedBy(result))
    }

    @Test fun fillingLastLibertyRemovesCapturedStone() {
        val item = Content.parsePuzzles("go", asset("puzzles/go.json")).first { it.id == "go-cap1" }
        val kit = GameKit("go", 9)
        val result = kit.engine.applyMove(kit.positionFromBoard(item.board, 0), item.solution)
        assertEquals(0, (result.position as GoPosition).board[1])
        assertEquals(1, (result.position as GoPosition).board[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun threeRookXiangqiSetupIsRejectedAtContentBoundary() {
        val json = org.json.JSONArray(asset("puzzles/xiangqi.json"))
        val board = json.getJSONObject(0).getJSONArray("board")
        board.put(4, 5)
        Content.parsePuzzles("xiangqi", json.toString())
    }
}
