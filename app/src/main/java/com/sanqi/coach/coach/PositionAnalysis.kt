package com.sanqi.coach.coach

import com.sanqi.coach.core.Move

data class SearchEvidence(
    val batch: String,
    val kind: String,
    val engineId: String,
    val engineVersion: String,
    val models: List<String>,
    val unit: String,
    val side: Int,
    val budgetMillis: Int,
    val depth: Int,
    val targetDepth: Int,
    val work: Long,
    val targetVisits: Int,
    val elapsedMillis: Long,
    val finished: Boolean,
    val targetReached: Boolean,
    val restricted: Boolean,
    val settings: String,
    val cached: Boolean = false
) {
    fun comparableWith(other: SearchEvidence): Boolean =
        batch == other.batch && kind == other.kind && engineId == other.engineId &&
            engineVersion == other.engineVersion && models == other.models && unit == other.unit &&
            side == other.side && budgetMillis == other.budgetMillis && settings == other.settings &&
            depth == other.depth && targetDepth == other.targetDepth && targetVisits == other.targetVisits &&
            finished && other.finished && targetReached && other.targetReached && !restricted && !other.restricted
}

data class CandidateLine(
    val move: Move,
    val score: Int,
    val moves: List<Move>,
    val mateIn: Int? = null,
    val winrate: Double? = null,
    val evidence: SearchEvidence? = null
)

data class PositionAnalysis(
    val candidates: List<CandidateLine>,
    val score: Int,
    val completedDepth: Int,
    val requestedDepth: Int,
    val nodes: Int,
    val limited: Boolean,
    val heuristic: Boolean,
    val engine: String? = null,
    val scoreUnit: String = "cp",
    val comparable: Boolean = true,
    val searchMillis: Long = 0
) {
    fun cachedResult(): PositionAnalysis = copy(searchMillis = 0,
        candidates = candidates.map { it.copy(evidence = it.evidence?.copy(cached = true)) })
}

data class MoveAssessment(
    val kind: String,
    val reference: CandidateLine,
    val played: CandidateLine,
    val reply: CandidateLine?,
    val comparable: Boolean
) {
    val loss: Int? get() = if (comparable && reference.mateIn == null && played.mateIn == null)
        reference.score - played.score else null
    val grade: Grade? get() = loss?.takeIf { kind == "chess" }?.let { Grade.fromLoss(it.coerceAtLeast(0)) }

    companion object {
        fun from(analysis: PositionAnalysis, move: Move, reply: CandidateLine?): MoveAssessment {
            val reference = analysis.candidates.first()
            val played = analysis.candidates.first { it.move == move }
            val a = reference.evidence
            val b = played.evidence
            return MoveAssessment(a?.kind.orEmpty(), reference, played, reply,
                analysis.comparable && !analysis.heuristic && !analysis.limited &&
                    a != null && b != null && a.comparableWith(b))
        }
    }
}
