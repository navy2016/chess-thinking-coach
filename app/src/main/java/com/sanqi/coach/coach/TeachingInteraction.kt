package com.sanqi.coach.coach

class TeachingInteraction {
    enum class Topic { WHY, RISK, ALTERNATIVE, PRINCIPLE }
    private val spoken = LinkedHashSet<String>()
    var lastText: String = ""
        private set

    fun narration(key: String, text: String, automatic: Boolean): String? {
        if (text.isBlank()) return null
        if (automatic && !spoken.add(key)) return null
        if (spoken.size > 80) spoken.remove(spoken.first())
        lastText = text
        return text
    }

    fun clear() { spoken.clear(); lastText = "" }
}
