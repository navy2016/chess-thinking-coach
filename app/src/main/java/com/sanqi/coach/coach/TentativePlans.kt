package com.sanqi.coach.coach

import com.sanqi.coach.core.Move

class TentativePlans {
    data class Plan(val move: Move, val preview: CoachSession.Preview? = null, val elapsed: Float = 0f,
                    val branch: Int = 0, val step: Int = 0, val alternative: Boolean = false)

    private val plans = arrayOfNulls<Plan>(2)
    var active: Int = 0
        private set

    fun get(index: Int): Plan? = plans[index]

    fun select(index: Int, revision: Long): Plan? {
        require(index in plans.indices)
        for (i in plans.indices) {
            if (plans[i]?.preview?.revision?.let { it != revision } == true) plans[i] = null
        }
        active = index
        return plans[index]
    }

    fun restore(index: Int, move: Move, branch: Int = 0, step: Int = 0, alternative: Boolean = false) {
        plans[index] = Plan(move, branch = branch, step = step, alternative = alternative)
    }

    fun stage(move: Move, resetPlayback: Boolean = true) {
        val previous = plans[active]?.takeIf { it.move == move && !resetPlayback }
        plans[active] = previous?.copy(preview = null, elapsed = 0f) ?: Plan(move)
    }

    fun put(preview: CoachSession.Preview, elapsed: Float) {
        val previous = plans[active]?.takeIf { it.move == preview.move } ?: Plan(preview.move)
        plans[active] = previous.copy(preview = preview, elapsed = elapsed)
        playback(previous.branch, previous.step, previous.alternative)
    }

    fun playback(branch: Int, step: Int, alternative: Boolean) {
        val plan = plans[active] ?: return
        val preview = plan.preview ?: return
        val selected = branch.coerceIn(0, maxOf(0, preview.replies.lastIndex))
        val useAlternative = alternative && preview.alternative.isNotEmpty()
        val length = if (useAlternative) preview.alternative.size else preview.replies.getOrNull(selected)?.moves?.size ?: 0
        val restoredStep = if (alternative && !useAlternative) 0 else step.coerceIn(0, length)
        plans[active] = plan.copy(branch = selected, step = restoredStep, alternative = useAlternative)
    }

    fun clear() {
        plans.fill(null)
        active = 0
    }

    fun comparable(): Boolean {
        val a = plans[0]?.preview?.assessment ?: return false
        val b = plans[1]?.preview?.assessment ?: return false
        val left = a.played.evidence ?: return false
        val right = b.played.evidence ?: return false
        return a.comparable && b.comparable && left.comparableWith(right)
    }
}
