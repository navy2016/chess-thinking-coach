package com.sanqi.coach.coach

import com.sanqi.coach.core.GameStatus

/**
 * 模板化中文讲解：全部基于引擎给出的局面事实（记谱、吃子数、将军、状态、
 * 对方最强应对），不编造棋理。上层可接 TTS 朗读。
 */
object Explainer {

    fun explain(
        notation: String,
        grade: Grade,
        loss: Int,
        bestNotation: String?,
        captured: Int,
        check: Boolean,
        status: GameStatus,
        oppReply: String?
    ): String {
        val sb = StringBuilder()
        sb.append("这步 ").append(notation).append(" 评级：").append(grade.label)
        if (loss > 0) sb.append("（损失约 ").append(loss).append("）")
        sb.append("。")
        if (captured > 0) sb.append("这步吃掉了对方 ").append(captured).append(" 个子。")
        if (check) sb.append("这步形成将军。")
        when (grade) {
            Grade.BEST -> sb.append("这正是当前局面的最强选择，继续保持。")
            Grade.GOOD -> sb.append("稳健的一手，没有给对方明显的机会。")
            Grade.INACCURATE -> if (bestNotation != null) {
                sb.append("更好的是 ").append(bestNotation).append("，这手给了对方一点主动权。")
            } else {
                sb.append("这手给了对方一点主动权。")
            }
            Grade.MISTAKE -> if (bestNotation != null) {
                sb.append("局面因此恶化，更推荐 ").append(bestNotation).append("。")
            } else {
                sb.append("局面因此恶化。")
            }
            Grade.BLUNDER -> if (bestNotation != null) {
                sb.append("严重失误！必须先看 ").append(bestNotation).append("。")
            } else {
                sb.append("严重失误！")
            }
        }
        if (grade == Grade.BLUNDER && oppReply != null) {
            sb.append("对方最强的回应是 ").append(oppReply).append("。")
        }
        when (status) {
            GameStatus.CHECKMATE -> sb.append("将死，棋局结束！")
            GameStatus.STALEMATE -> sb.append("棋局结束（对方无子可动）。")
            GameStatus.DRAW -> sb.append("棋局结束，和棋。")
            else -> {}
        }
        return sb.toString()
    }
}
