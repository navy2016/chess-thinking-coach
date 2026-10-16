package com.sanqi.coach.coach

import com.sanqi.coach.core.AppliedMove
import com.sanqi.coach.core.GameEngine
import com.sanqi.coach.core.GameStatus
import com.sanqi.coach.core.Move
import com.sanqi.coach.core.SearchResult

enum class Grade(val label: String) {
    BEST("最佳"),
    GOOD("良好"),
    INACCURATE("可疑"),
    MISTAKE("失误"),
    BLUNDER("严重失误");

    companion object {
        fun fromLoss(loss: Int): Grade = when {
            loss <= 30 -> BEST
            loss <= 80 -> GOOD
            loss <= 180 -> INACCURATE
            loss <= 350 -> MISTAKE
            else -> BLUNDER
        }
    }
}

data class MoveRating(
    val notation: String,
    val loss: Int,
    val grade: Grade,
    val bestNotation: String?,
    val explanation: String
)

/**
 * 教练会话：负责「暂选分析 → 确认落子 → 评分讲解 → AI 回手」的核心闭环。
 * 所有耗时搜索都必须由上层放到后台线程调用。
 */
class CoachSession<P : Any>(
    private val engine: GameEngine<P>,
    val mode: Mode,
    private val aiDepth: Int,
    private val stalemateIsDraw: Boolean,
    start: P? = null
) {
    enum class Mode { TWO_PLAYER, VS_AI, REVIEW }

    data class Entry<P : Any>(
        val posBefore: P,
        val move: Move,
        val applied: AppliedMove<P>,
        val rating: MoveRating?
    )

    data class Preview(
        val notation: String,
        val captured: Int,
        val check: Boolean,
        val status: GameStatus,
        val loss: Int,
        val grade: Grade,
        val bestNotation: String?,
        val oppReply: String?,
        val ourFollow: String?,
        val treeText: String,
        val explanation: String
    )

    var current: P = start ?: engine.initialPosition()
        private set
    val history = mutableListOf<Entry<P>>()
    var gameOverText: String? = null
        private set

    fun sideToMove(): Int = engine.sideToMove(current)
    fun sideName(side: Int): String = engine.sideName(side)
    fun lastEntry(): Entry<P>? = history.lastOrNull()

    fun legalMovesFrom(idx: Int): List<Move> = engine.legalMoves(current).filter { it.from == idx }

    fun isMoveLegal(move: Move): Boolean = engine.legalMoves(current).any { it == move }

    private fun notationOf(pos: P, move: Move): String =
        engine.applyMove(engine.copyPosition(pos), move).notation

    /**
     * 「替对方推演」：分析用户暂选的一步。
     * 返回损失、评级、对方最强应对、我方后续推荐和变化树文本。
     */
    fun analyze(depth: Int, move: Move): Preview {
        val s0 = engine.searchBest(current, depth)
        val applied = engine.applyMove(current, move)
        val s1 = engine.searchBest(applied.position, depth)
        val after = -s1.score
        val loss = (s0.score - after).coerceAtLeast(0)
        val grade = Grade.fromLoss(loss)
        val bestNotation = if (s0.bestMove != null && s0.bestMove != move) {
            notationOf(current, s0.bestMove!!)
        } else null
        val oppReply = if (s1.bestMove != null) notationOf(applied.position, s1.bestMove!!) else null
        var ourFollow: String? = null
        if (s1.bestMove != null) {
            val pos2 = engine.applyMove(applied.position, s1.bestMove!!).position
            val s2 = engine.searchBest(pos2, depth)
            if (s2.bestMove != null) ourFollow = notationOf(pos2, s2.bestMove!!)
        }
        val treeText = buildTree(applied.notation, grade, loss, oppReply, ourFollow)
        val explanation = Explainer.explain(
            applied.notation, grade, loss, bestNotation,
            applied.captured, applied.check, applied.status, oppReply
        )
        return Preview(
            applied.notation, applied.captured, applied.check, applied.status,
            loss, grade, bestNotation, oppReply, ourFollow, treeText, explanation
        )
    }

    private fun buildTree(notation: String, grade: Grade, loss: Int, oppReply: String?, ourFollow: String?): String {
        val sb = StringBuilder()
        sb.append("你的暂选：").append(notation)
        sb.append("（").append(grade.label).append("，损失 ").append(loss).append("）\n")
        if (oppReply != null) {
            sb.append("对方最强：").append(oppReply).append("\n")
            if (ourFollow != null) sb.append("  └─ 推荐：").append(ourFollow)
        } else {
            sb.append("对方暂无有效应对")
        }
        return sb.toString().trim()
    }

    private fun push(move: Move, rating: MoveRating?): Entry<P> {
        val applied = engine.applyMove(current, move)
        val entry = Entry(current, move, applied, rating)
        current = applied.position
        history.add(entry)
        gameOverText = endText(applied, entry)
        return entry
    }

    /** 确认落子。preview 传 analyze 的结果可避免重复搜索；为 null 时现场分析。 */
    fun commit(move: Move, preview: Preview?): Entry<P> {
        val rating = if (preview != null) {
            MoveRating(preview.notation, preview.loss, preview.grade, preview.bestNotation, preview.explanation)
        } else {
            val p = analyze(aiDepth, move)
            MoveRating(p.notation, p.loss, p.grade, p.bestNotation, p.explanation)
        }
        return push(move, rating)
    }

    /** 围棋停一手。 */
    fun commitPass(): Entry<P> = push(Move(-1, -1, 0), null)

    /** 复盘/打谱：直接落子并挂上已有评分，不重新分析。 */
    fun commitReview(move: Move, rating: MoveRating?): Entry<P> = push(move, rating)

    /** AI 回手并同样给出评分讲解。 */
    fun aiMove(): Entry<P>? {
        if (gameOverText != null) return null
        val s: SearchResult = engine.searchBest(current, aiDepth)
        val move = s.bestMove ?: return null
        val applied = engine.applyMove(current, move)
        val s1 = engine.searchBest(applied.position, aiDepth)
        val after = -s1.score
        val loss = (s.score - after).coerceAtLeast(0)
        val grade = Grade.fromLoss(loss)
        val oppReply = if (s1.bestMove != null) notationOf(applied.position, s1.bestMove!!) else null
        val explanation = Explainer.explain(
            applied.notation, grade, loss, null,
            applied.captured, applied.check, applied.status, oppReply
        )
        val entry = Entry(current, move, applied, MoveRating(applied.notation, loss, grade, null, explanation))
        current = applied.position
        history.add(entry)
        gameOverText = endText(applied, entry)
        return entry
    }

    /** 悔棋：人机模式一次退两手（AI + 用户），双人模式退一手。 */
    fun undo() {
        if (history.isEmpty()) return
        var n = 1
        if (mode == Mode.VS_AI && history.size >= 2) n = 2
        repeat(n) {
            if (history.isNotEmpty()) history.removeAt(history.size - 1)
        }
        current = if (history.isEmpty()) engine.initialPosition() else history.last().applied.position
        gameOverText = null
    }

    private fun endText(applied: AppliedMove<P>, entry: Entry<P>): String? {
        val mover = engine.sideToMove(entry.posBefore)
        return when (applied.status) {
            GameStatus.CHECKMATE -> engine.sideName(mover) + "胜（将死）"
            GameStatus.STALEMATE ->
                if (stalemateIsDraw) "和棋（逼和）" else engine.sideName(1 - mover) + "胜（困毙）"
            GameStatus.DRAW -> "和棋"
            else -> null
        }
    }
}
