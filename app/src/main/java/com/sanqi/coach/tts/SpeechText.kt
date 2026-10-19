package com.sanqi.coach.tts

object SpeechText {
    fun chunks(text: String, limit: Int = 600): List<String> {
        require(limit >= 2)
        val result = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            var end = minOf(offset + limit, text.length)
            if (end < text.length) {
                if (Character.isHighSurrogate(text[end - 1])) end--
                val sentence = (end - 1 downTo offset + (end - offset) / 2)
                    .firstOrNull { text[it] in "。！？；.!?;\n" }
                if (sentence != null) end = sentence + 1
            }
            text.substring(offset, end).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            offset = end
        }
        return result
    }

    fun xml(text: String): String = buildString {
        for (char in text) when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (char >= ' ' || char in "\n\r\t") append(char)
        }
    }
}
