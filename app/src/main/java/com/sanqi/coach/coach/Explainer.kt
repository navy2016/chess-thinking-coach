package com.sanqi.coach.coach

import android.content.Context
import com.sanqi.coach.R
import com.sanqi.coach.core.GameStatus

object Explainer {
    data class Part(val text: String, val branch: Int? = null, val step: Int = 0, val alternative: Boolean = false, val id: String = "")

    fun gradeLabel(context: Context, grade: Grade): String = context.getString(when (grade) {
        Grade.BEST -> R.string.grade_best
        Grade.GOOD -> R.string.grade_good
        Grade.INACCURATE -> R.string.grade_inaccurate
        Grade.MISTAKE -> R.string.grade_mistake
        Grade.BLUNDER -> R.string.grade_blunder
    })

    fun score(context: Context, kind: String, line: CandidateLine): String = when {
        line.mateIn != null -> context.getString(R.string.engine_mate, line.mateIn)
        kind == "go" && line.winrate != null -> context.getString(R.string.engine_points, line.score / 100.0, line.winrate * 100)
        kind == "go" -> context.getString(R.string.score_points_only, line.score / 100.0)
        kind == "xiangqi" -> context.getString(R.string.score_xiangqi, line.score)
        else -> context.getString(R.string.engine_pawns, line.score / 100.0)
    }

    fun assessment(context: Context, value: MoveAssessment): String {
        val loss = value.loss ?: return context.getString(if (value.played.mateIn != null || value.reference.mateIn != null)
            R.string.mate_no_numeric_grade else R.string.rating_unavailable)
        return when (value.kind) {
            "go" -> context.getString(R.string.assessment_go, loss / 100.0)
            "xiangqi" -> context.getString(R.string.assessment_xiangqi, loss)
            "chess" -> value.grade?.let { context.getString(R.string.assessment_chess, gradeLabel(context, it), loss / 100.0) }
                ?: context.getString(R.string.rating_unavailable)
            else -> context.getString(R.string.rating_unavailable)
        }
    }

    fun source(context: Context, value: SearchEvidence?): String {
        if (value == null) return context.getString(R.string.analysis_unknown)
        val quality = context.getString(when {
            !value.finished -> R.string.search_unfinished
            !value.targetReached -> R.string.search_partial
            else -> R.string.search_target_reached
        })
        val extent = if (value.kind == "go") context.getString(R.string.search_visits, value.work, value.targetVisits)
            else context.getString(R.string.search_depth_nodes, value.depth, value.targetDepth, value.work)
        return context.getString(R.string.source_summary, value.engineId, value.engineVersion, quality, extent,
            value.elapsedMillis / 1000.0) +
            (if (value.restricted) "\n" + context.getString(R.string.search_restricted) else "") +
            (if (value.cached) "\n" + context.getString(R.string.search_cached) else "")
    }

    fun fact(context: Context, step: CoachSession.EvidenceStep): String {
        val facts = mutableListOf<String>()
        if (step.captured > 0) facts.add(context.getString(R.string.evidence_capture, step.captured))
        if (step.check) facts.add(context.getString(R.string.evidence_check))
        when (step.status) {
            GameStatus.CHECKMATE -> facts.add(context.getString(R.string.end_checkmate))
            GameStatus.STALEMATE -> facts.add(context.getString(R.string.end_no_moves))
            GameStatus.DRAW -> facts.add(context.getString(R.string.end_draw))
            else -> Unit
        }
        return facts.joinToString(context.getString(R.string.fact_separator)).ifEmpty { context.getString(R.string.evidence_quiet) }
    }

    fun parts(context: Context, preview: CoachSession.Preview): List<Part> {
        val parts = mutableListOf(Part(assessment(context, preview.assessment)))
        preview.teaching?.points?.forEach { parts.add(teachingPart(context, it)) }
        parts.add(Part(context.getString(R.string.evidence_played, preview.notation, fact(context, preview.playedStep)), 0))
        parts.add(Part(context.getString(R.string.evidence_conditional)))
        for ((branch, reply) in preview.replies.withIndex()) {
            val steps = reply.steps
            val targets = (listOf(0) + steps.indices.filter { steps[it].captured > 0 || steps[it].check ||
                steps[it].status in listOf(GameStatus.CHECKMATE, GameStatus.STALEMATE, GameStatus.DRAW) }).distinct().take(3)
            for (index in targets) {
                val step = steps.getOrNull(index) ?: continue
                parts.add(Part(context.getString(R.string.evidence_reply, branch + 1, index + 1,
                    step.notation, fact(context, step)), branch, index + 1))
            }
        }
        if (preview.replies.isEmpty() && preview.status !in listOf(GameStatus.CHECKMATE, GameStatus.STALEMATE, GameStatus.DRAW))
            parts.add(Part(context.getString(R.string.evidence_insufficient)))
        if (preview.alternative.isNotEmpty()) {
            parts.add(Part(context.getString(R.string.evidence_alternative), alternative = true))
            for ((index, step) in preview.alternative.withIndex()) {
                parts.add(Part(context.getString(R.string.evidence_alternative_step, index + 1,
                    step.notation, fact(context, step)), step = index + 1, alternative = true))
            }
        }
        return parts.mapIndexed { index, part -> if (part.id.isEmpty()) part.copy(id = "fact_$index") else part }
    }

    fun teachingPart(context: Context, point: TeachingPoint): Part {
        val name = context.getString(when (point.feature) {
            TeachingFeature.PIECES -> R.string.feature_pieces
            TeachingFeature.KING_ATTACKED -> R.string.feature_king_attacked
            TeachingFeature.KING_ZONE_ATTACKS -> R.string.feature_king_zone
            TeachingFeature.CENTER_OCCUPANCY -> R.string.feature_center_occupancy
            TeachingFeature.CENTER_CONTROL -> R.string.feature_center_control
            TeachingFeature.MINORS_OFF_HOME -> R.string.feature_minors
            TeachingFeature.ROOK_CANNON_MOVES -> R.string.feature_rook_cannon
            TeachingFeature.BLOCKED_HORSE_LEGS -> R.string.feature_horse_legs
            TeachingFeature.GO_GROUPS -> R.string.feature_go_groups
            TeachingFeature.GO_ATARI_GROUPS -> R.string.feature_go_atari
            TeachingFeature.GO_FOCUS_LIBERTIES -> R.string.feature_go_liberties
            TeachingFeature.GO_CAPTURABLE_STONES -> R.string.feature_go_capturable
        })
        val location = context.getString(when {
            point.alternative -> R.string.teaching_at_alternative
            point.step > 0 -> R.string.teaching_at_reply
            else -> R.string.teaching_at_move
        })
        val text = context.getString(R.string.teaching_delta, location, name,
            point.before?.toString() ?: context.getString(R.string.teaching_not_applicable), point.after) +
            "\n" + principle(context, point.feature)
        return Part(text, point.branch, point.step, point.alternative, point.id)
    }

    private fun principle(context: Context, feature: TeachingFeature): String = context.getString(when (feature) {
        TeachingFeature.PIECES -> R.string.principle_pieces
        TeachingFeature.KING_ATTACKED -> R.string.principle_king_attacked
        TeachingFeature.KING_ZONE_ATTACKS -> R.string.principle_king_zone
        TeachingFeature.CENTER_OCCUPANCY -> R.string.principle_center_occupancy
        TeachingFeature.CENTER_CONTROL -> R.string.principle_center_control
        TeachingFeature.MINORS_OFF_HOME -> R.string.principle_minors
        TeachingFeature.ROOK_CANNON_MOVES -> R.string.principle_rook_cannon
        TeachingFeature.BLOCKED_HORSE_LEGS -> R.string.principle_horse_legs
        TeachingFeature.GO_GROUPS -> R.string.principle_go_groups
        TeachingFeature.GO_ATARI_GROUPS -> R.string.principle_go_atari
        TeachingFeature.GO_FOCUS_LIBERTIES -> R.string.principle_go_liberties
        TeachingFeature.GO_CAPTURABLE_STONES -> R.string.principle_go_capturable
    })

    fun topic(context: Context, preview: CoachSession.Preview, topic: TeachingInteraction.Topic): List<Part> {
        val points = preview.teaching?.points.orEmpty()
        val selected = when (topic) {
            TeachingInteraction.Topic.WHY -> points.filter { !it.alternative && it.step == 0 }
            TeachingInteraction.Topic.RISK -> points.filter { !it.alternative && it.risk }
            TeachingInteraction.Topic.ALTERNATIVE -> points.filter { it.alternative }
            TeachingInteraction.Topic.PRINCIPLE -> listOfNotNull(preview.teaching?.mainPoint)
        }.take(2)
        if (selected.isNotEmpty()) return selected.map { teachingPart(context, it) }
        return listOf(Part(context.getString(R.string.teaching_no_evidence)))
    }

    fun brief(context: Context, preview: CoachSession.Preview): String =
        preview.teaching?.mainPoint?.let { teachingPart(context, it).text }
            ?: context.getString(R.string.evidence_played, preview.notation, fact(context, preview.playedStep))

    fun explain(context: Context, preview: CoachSession.Preview): String =
        parts(context, preview).joinToString("\n") { it.text }
}
