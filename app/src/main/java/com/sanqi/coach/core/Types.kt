package com.sanqi.coach.core

/**
 * 对局状态。applyMove 返回的 status 描述"走完这一步之后"的局面状态。
 */
enum class GameStatus { ONGOING, CHECK, CHECKMATE, STALEMATE, DRAW }

/**
 * 统一走法。from/to 为引擎自定义的格子/交叉点索引。
 * 约定：停着一律用 Move(-1, -1, 0) 表示（围棋 PASS）。
 * promo 为附加信息（国象升变：2后 3车 4象 5马，与棋子编码一致）。
 */
data class Move(val from: Int, val to: Int, val promo: Int = 0)

/**
 * 搜索结果。score 一律为"当前行棋方"视角（negamax 风格），分越高对当前行棋方越有利。
 * 将死对分取 ±100000，逼和/困毙取 0。
 */
data class SearchResult(val bestMove: Move?, val score: Int, val pv: List<Move> = emptyList())

/**
 * applyMove 的结果。applyMove 假定走法已通过 legalMoves 校验，不再做合法性检查。
 */
data class AppliedMove<P>(
    val position: P,
    val notation: String,
    val captured: Int,
    val check: Boolean,
    val status: GameStatus
)

/**
 * 三种棋共用的引擎接口。
 *
 * 关键约定：
 * 1. sideToMove 返回 0（先手：围棋黑 / 象棋红 / 国象白）或 1（后手）。
 * 2. evaluate / searchBest 的分数一律从"当前行棋方"视角给出，
 *    100 分约等于一个兵/卒的价值。
 * 3. legalMoves 返回的每一步都必须真正合法（含"走完后自己不被将军"类约束）。
 * 4. applyMove 不可修改传入的 pos，必须返回全新对象。
 * 5. CHECKMATE：走完这步后对方被将军且无合法着法（本步方赢）；
 *    STALEMATE：走完这步后对方未被将军但无合法着法
 *    （国象为逼和即和棋；象棋为困毙，困毙方输，由上层按棋种判定）。
 */
interface GameEngine<P : Any> {
    fun initialPosition(): P
    fun sideToMove(pos: P): Int
    fun sideName(side: Int): String
    fun legalMoves(pos: P): List<Move>
    fun applyMove(pos: P, move: Move): AppliedMove<P>
    fun evaluate(pos: P): Int
    fun searchBest(pos: P, depth: Int): SearchResult
    fun squareDesc(pos: P, square: Int): String
    fun copyPosition(pos: P): P
}
