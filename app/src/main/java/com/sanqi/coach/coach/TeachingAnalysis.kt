package com.sanqi.coach.coach

import com.sanqi.coach.core.GameEngine
import com.sanqi.coach.core.Move

data class TeachingPoint(
    val id: String,
    val feature: TeachingFeature,
    val before: Int?,
    val after: Int,
    val branch: Int = 0,
    val step: Int = 0,
    val alternative: Boolean = false,
    val risk: Boolean = false
)

data class TeachingAnalysis(val kind: String, val side: Int, val points: List<TeachingPoint>) {
    val mainPoint: TeachingPoint? get() = points.firstOrNull { it.risk } ?: points.firstOrNull()

    companion object {
        fun <P : Any> create(engine: GameEngine<P>, source: P, move: Move,
                             replies: List<CoachSession.Reply>, alternative: List<CoachSession.EvidenceStep>,
                             cancelled: () -> Unit): TeachingAnalysis {
            val side = engine.sideToMove(source)
            val after = engine.applyMove(source, move).position
            val beforeFeatures = PositionFeatures.read(source, side, move.from)
            val afterFeatures = PositionFeatures.read(after, side, move.to)
            val points = mutableListOf<TeachingPoint>()
            points.addAll(changes("played", beforeFeatures, afterFeatures, 0, 0, false))
            for ((branch, reply) in replies.withIndex()) {
                cancelled()
                val first = reply.moves.firstOrNull() ?: continue
                val position = engine.applyMove(after, first).position
                points.addAll(changes("reply$branch", afterFeatures, PositionFeatures.read(position, side, move.to), branch, 1, false))
            }
            alternative.firstOrNull()?.let {
                cancelled()
                val position = engine.applyMove(source, it.move).position
                points.addAll(changes("alternative", afterFeatures, PositionFeatures.read(position, side, it.move.to), 0, 1, true))
            }
            return TeachingAnalysis(beforeFeatures.kind, side, points)
        }

        private fun changes(prefix: String, before: FeatureSnapshot, after: FeatureSnapshot,
                            branch: Int, step: Int, alternative: Boolean): List<TeachingPoint> {
            val changed = after.values.filter { (feature, value) -> before.values[feature] != value }
            val selected = if (changed.isEmpty() && prefix == "played")
                after.values.filterKeys { it == TeachingFeature.GO_FOCUS_LIBERTIES || it == TeachingFeature.KING_ATTACKED }
            else changed
            return selected.map { (feature, value) ->
                val old = before.values[feature]
                val risk = when (feature) {
                    TeachingFeature.KING_ATTACKED, TeachingFeature.KING_ZONE_ATTACKS,
                    TeachingFeature.BLOCKED_HORSE_LEGS, TeachingFeature.GO_ATARI_GROUPS,
                    TeachingFeature.GO_CAPTURABLE_STONES -> value > (old ?: 0)
                    TeachingFeature.PIECES, TeachingFeature.GO_FOCUS_LIBERTIES -> old != null && value < old
                    else -> false
                }
                TeachingPoint("${prefix}_${feature.name.lowercase()}", feature, old, value, branch, step, alternative, risk)
            }.sortedBy { point ->
                when {
                    point.risk -> 0
                    point.feature == TeachingFeature.GO_FOCUS_LIBERTIES -> 1
                    point.feature == TeachingFeature.CENTER_CONTROL || point.feature == TeachingFeature.ROOK_CANNON_MOVES -> 2
                    else -> 3
                }
            }.take(2)
        }
    }
}
