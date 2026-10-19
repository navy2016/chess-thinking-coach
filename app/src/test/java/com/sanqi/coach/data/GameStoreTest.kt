package com.sanqi.coach.data

import com.sanqi.coach.coach.CandidateLine
import com.sanqi.coach.coach.MoveAssessment
import com.sanqi.coach.coach.SearchEvidence
import com.sanqi.coach.core.Move
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GameStoreTest {
    private fun assessment(): MoveAssessment {
        val evidence = SearchEvidence("id", "chess", "fish", "17", listOf("net:hash"), "cp", 1,
            1000, 10, 10, 5_000_000_000L, 0, 800, true, true, false, "Threads=2", true)
        val a = CandidateLine(Move(52, 36), 50, listOf(Move(52, 36)), evidence = evidence)
        val b = CandidateLine(Move(51, 35), -40, listOf(Move(51, 35)), evidence = evidence)
        return MoveAssessment("chess", a, b, null, true)
    }

    @Test fun versionTwoRoundTripPreservesAnalysisAndUnanalyzedMoves() {
        val value = assessment()
        val game = StoredGame("g", "chess", "TWO_PLAYER", 2, 8, 1, 2, false, "",
            listOf(StoredMove(51, 35, 0, "d5", value.loss, value.grade!!.name, "facts", value),
                StoredMove(12, 28, 0, "e4", null, "", "")))
        val json = GameStore.encode(game)
        assertEquals(2, json.getInt("schemaVersion"))
        val restored = GameStore.parse(File("g.json"), JSONObject(json.toString()))
        assertEquals(game, restored)
        assertEquals(5_000_000_000L, restored.moves[0].analysis!!.played.evidence!!.work)
        assertNull(restored.moves[1].loss)
        assertNull(restored.moves[1].analysis)
    }

    @Test fun legacyFileKeepsMovesAndTextButHasNoInventedEvidence() {
        val json = JSONObject("""{"id":"old","kind":"chess","moves":[{"from":12,"to":28,"notation":"e4","loss":0,"grade":"最佳","explanation":"old text"},{"from":52,"to":36}]}""")
        val game = GameStore.parse(File("old.json"), json)
        assertEquals(2, game.moves.size)
        assertEquals("old text", game.moves.first().explanation)
        assertNull(game.moves.first().analysis)
        assertNull(game.moves[1].loss)
        val saved = GameStore.encode(game)
        assertNull(GameStore.parse(File("old.json"), saved).moves.first().analysis)
    }

    @Test fun missingOrUnsupportedAnalysisIsUnknownNotZero() {
        assertNull(AnalysisRecord.decode(null))
        assertNull(AnalysisRecord.decode(JSONObject().put("version", 999)))
        assertNull(AnalysisRecord.decode(JSONObject().put("version", 1)))
        val json = AnalysisRecord.encode(assessment())
        json.getJSONObject("played").getJSONObject("source").put("batch", "different")
        val parsed = AnalysisRecord.decode(json)!!
        assertFalse(parsed.comparable)
        assertNull(parsed.loss)
        assertNull(parsed.grade)
    }

    @Test fun mateAndReplyKeepTheirOwnPerspective() {
        val base = assessment()
        val value = base.copy(played = base.played.copy(mateIn = -2),
            reply = base.reference.copy(evidence = base.reference.evidence!!.copy(side = 0, batch = "reply")))
        val decoded = AnalysisRecord.decode(JSONObject(AnalysisRecord.encode(value).toString()))!!
        assertEquals(value, decoded)
        assertNull(decoded.loss)
        assertEquals(1, decoded.played.evidence!!.side)
        assertEquals(0, decoded.reply!!.evidence!!.side)
    }

    @Test fun unknownAndMismatchedKindsOrUnitsAreRejected() {
        for (kind in listOf("", "unknown", "go", "xiangqi")) {
            assertNull(AnalysisRecord.decode(AnalysisRecord.encode(assessment()).put("kind", kind)))
        }
        for (key in listOf("reference", "played", "reply")) {
            val value = assessment().let { it.copy(reply = it.reference) }
            val kindMismatch = AnalysisRecord.encode(value)
            kindMismatch.getJSONObject(key).getJSONObject("source").put("kind", "go")
            assertNull(AnalysisRecord.decode(kindMismatch))
            val unitMismatch = AnalysisRecord.encode(value)
            unitMismatch.getJSONObject(key).getJSONObject("source").put("unit", "points")
            assertNull(AnalysisRecord.decode(unitMismatch))
        }
    }

    @Test fun analysisForAnotherGameDoesNotDiscardTheMoves() {
        val value = assessment()
        val game = StoredGame("g", "go", "TWO_PLAYER", 2, 9, 1, 2, false, "",
            listOf(StoredMove(0, 0, 0, "A9", null, "", "facts", value)))
        val restored = GameStore.parse(File("g.json"), GameStore.encode(game))
        assertEquals(1, restored.moves.size)
        assertEquals("facts", restored.moves.single().explanation)
        assertNull(restored.moves.single().analysis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureRecordVersionIsNotSilentlyDowngraded() {
        GameStore.parse(File("future.json"), JSONObject().put("schemaVersion", 3).put("moves", org.json.JSONArray()))
    }
}
