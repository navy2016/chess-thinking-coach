package com.sanqi.coach.tts

import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
    @Test fun escapesMarkupAndDropsInvalidControls() {
        assertEquals("&lt;voice&gt;&amp;&quot;", SpeechText.xml("<voice>&\""))
        assertEquals("abc\ndef", SpeechText.xml("abc\u0000\ndef"))
        assertEquals("&apos;", SpeechText.xml("'"))
    }

    @Test fun chunksRespectLimitAndPreserveSurrogatePairs() {
        val text = "a".repeat(899) + "\uD83D\uDE00" + "围棋".repeat(1200)
        val chunks = SpeechText.chunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 900 && !Character.isHighSurrogate(it.last()) })
    }

    @Test fun prefersSentenceBoundaryWithoutEmptyChunks() {
        val text = "棋".repeat(650) + "。" + "子".repeat(800)
        val chunks = SpeechText.chunks(text, 900)
        assertTrue(chunks.first().endsWith("。"))
        assertEquals(text, chunks.joinToString(""))
        assertTrue(SpeechText.chunks("  \n").isEmpty())
    }
}
