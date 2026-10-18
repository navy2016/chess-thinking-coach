package com.sanqi.coach.core

interface SearchSupport<P : Any> : GameEngine<P> {
    val heuristic: Boolean get() = false
    fun searchPosition(pos: P, move: Move): P
    fun searchMoves(pos: P): List<Move> = legalMoves(pos)
    fun inCheck(pos: P): Boolean
    fun isTactical(pos: P, move: Move): Boolean
    fun terminalScore(pos: P, moves: List<Move>, ply: Int): Int?
}
