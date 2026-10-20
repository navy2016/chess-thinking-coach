package com.sanqi.coach.coach

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OnlineCoachProtocolTest {
    private fun response(text: String = "This is a conditional explanation.", reference: String = "known"): JSONObject =
        JSONObject().put("paragraphs", JSONArray().put(JSONObject().put("text", text)
            .put("evidenceIds", JSONArray().put(reference))))

    @Test fun validReplyRetainsCitations() {
        val result = OnlineCoachClient.parse(response().toString(), setOf("known"))
        assertEquals(1, result.size)
        assertEquals(listOf("known"), result.single().evidenceIds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun inventedEvidenceIsRejected() {
        OnlineCoachClient.parse(response(reference = "invented").toString(), setOf("known"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedParagraphIsRejectedBeforeSpeech() {
        OnlineCoachClient.parse(response("x".repeat(1201)).toString(), setOf("known"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun extraCommandFieldsAreRejected() {
        OnlineCoachClient.parse(response().put("command", "ignored").toString(), setOf("known"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyCitationsAreRejected() {
        val response = response()
        response.getJSONArray("paragraphs").getJSONObject(0).put("evidenceIds", JSONArray())
        OnlineCoachClient.parse(response.toString(), setOf("known"))
    }

    @Test fun endpointRequiresHttpsAndNoEmbeddedCredentialsOrQuery() {
        for (endpoint in listOf("http://github.com", "https://name:secret@github.com", "https://github.com?key=hidden", "https://github.com#fragment")) {
            try { OnlineCoachSettings.normalizeEndpoint(endpoint); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("https://github.com/", OnlineCoachSettings.normalizeEndpoint("  https://github.com  "))
    }

    @Test fun configurationStringDoesNotExposeSecret() {
        val configuration = OnlineCoachSettings.Configuration("https://github.com/", "model", "test-secret")
        assertFalse(configuration.toString().contains("test-secret"))
        assertTrue(configuration.toString().contains("<redacted>"))
    }
}
