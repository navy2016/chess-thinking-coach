package com.sanqi.coach.core

/**
 * 把具体棋种的 GameEngine<P> 包装成 GameEngine<Any>，
 * 让 UI 层可以用统一类型持有三种引擎。运行时 P 已被擦除，转型安全由构造方保证。
 */
@Suppress("UNCHECKED_CAST")
class AnyEngine<P : Any>(private val inner: SearchSupport<P>) : SearchSupport<Any> {

    override val heuristic: Boolean get() = inner.heuristic
    override fun searchPosition(pos: Any, move: Move): Any = inner.searchPosition(pos as P, move)
    override fun searchMoves(pos: Any): List<Move> = inner.searchMoves(pos as P)
    override fun inCheck(pos: Any): Boolean = inner.inCheck(pos as P)
    override fun isTactical(pos: Any, move: Move): Boolean = inner.isTactical(pos as P, move)
    override fun terminalScore(pos: Any, moves: List<Move>, ply: Int): Int? = inner.terminalScore(pos as P, moves, ply)

    override fun initialPosition(): Any = inner.initialPosition()

    override fun sideToMove(pos: Any): Int = inner.sideToMove(pos as P)

    override fun sideName(side: Int): String = inner.sideName(side)

    override fun legalMoves(pos: Any): List<Move> = inner.legalMoves(pos as P)

    override fun applyMove(pos: Any, move: Move): AppliedMove<Any> {
        val r = inner.applyMove(pos as P, move)
        return AppliedMove(r.position, r.notation, r.captured, r.check, r.status)
    }

    override fun evaluate(pos: Any): Int = inner.evaluate(pos as P)

    override fun searchBest(pos: Any, depth: Int): SearchResult = inner.searchBest(pos as P, depth)

    override fun squareDesc(pos: Any, square: Int): String = inner.squareDesc(pos as P, square)

    override fun copyPosition(pos: Any): Any = inner.copyPosition(pos as P)
}
