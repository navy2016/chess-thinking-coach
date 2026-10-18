package com.sanqi.coach.coach

import android.content.Context
import com.sanqi.coach.R
import com.sanqi.coach.core.GameStatus

object Explainer {
    fun gradeLabel(context: Context, grade: Grade): String = context.getString(when (grade) {
        Grade.BEST -> R.string.grade_best
        Grade.GOOD -> R.string.grade_good
        Grade.INACCURATE -> R.string.grade_inaccurate
        Grade.MISTAKE -> R.string.grade_mistake
        Grade.BLUNDER -> R.string.grade_blunder
    })

    fun explain(context: Context, preview: CoachSession.Preview): String {
        val lines = mutableListOf(if (preview.analysis.comparable) context.getString(R.string.explain_rating,
            preview.notation, gradeLabel(context, preview.grade), preview.loss) else context.getString(R.string.rating_unavailable))
        if (preview.captured > 0) lines.add(context.getString(R.string.explain_capture, preview.captured))
        if (preview.check) lines.add(context.getString(R.string.explain_check))
        preview.bestNotation?.let { lines.add(context.getString(R.string.explain_alternative, it)) }
        if (preview.analysis.comparable) lines.add(context.getString(if (preview.loss <= 30) R.string.explain_close else R.string.explain_compare))
        when (preview.status) {
            GameStatus.CHECKMATE -> lines.add(context.getString(R.string.end_checkmate))
            GameStatus.STALEMATE -> lines.add(context.getString(R.string.end_no_moves))
            GameStatus.DRAW -> lines.add(context.getString(R.string.end_draw))
            else -> Unit
        }
        if (preview.analysis.heuristic) lines.add(context.getString(R.string.heuristic_notice))
        return lines.joinToString("\n")
    }
}
