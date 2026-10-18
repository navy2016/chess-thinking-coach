package com.sanqi.coach.engine

object UciProtocol {
    data class Line(val depth: Int, val rank: Int, val score: Int, val mate: Int?, val pv: List<String>, val nodes: Int)
    fun parse(line: String): Line? {
        val words = line.trim().split(Regex("\\s+"))
        if (words.firstOrNull() != "info" || "lowerbound" in words || "upperbound" in words) return null
        fun value(key: String): String? = words.indexOf(key).takeIf { it >= 0 }?.let { words.getOrNull(it + 1) }
        val pv = words.indexOf("pv")
        val depth = value("depth")?.toIntOrNull() ?: return null
        if (pv < 0 || pv + 1 >= words.size) return null
        val mate = value("mate")?.toIntOrNull()
        val score = if (mate != null) {
            if (mate > 0) 100000 - mate.coerceAtMost(999) else -100000 - mate.coerceAtLeast(-999)
        } else value("cp")?.toIntOrNull() ?: return null
        return Line(depth, value("multipv")?.toIntOrNull() ?: 1, score, mate, words.drop(pv + 1),
            (value("nodes")?.toLongOrNull() ?: 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}
