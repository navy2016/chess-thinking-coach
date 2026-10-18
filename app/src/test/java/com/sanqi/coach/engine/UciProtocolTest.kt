package com.sanqi.coach.engine

import org.junit.Assert.*
import org.junit.Test

class UciProtocolTest {
    @Test fun readMultiPvScoreAndCompleteLine() {
        val line = UciProtocol.parse("info depth 12 seldepth 20 multipv 2 score cp -34 nodes 98765 pv e2e4 e7e5 g1f3")!!
        assertEquals(12, line.depth)
        assertEquals(2, line.rank)
        assertEquals(-34, line.score)
        assertEquals(98765, line.nodes)
        assertEquals(listOf("e2e4", "e7e5", "g1f3"), line.pv)
    }
    @Test fun mateIsNotParsedAsPawnScore() {
        val win = UciProtocol.parse("info depth 10 score mate 3 nodes 100 pv h5f7")!!
        val loss = UciProtocol.parse("info depth 10 score mate -2 nodes 200 pv e8f8")!!
        assertEquals(3, win.mate)
        assertEquals(-2, loss.mate)
        assertTrue(win.score > 90000)
        assertTrue(loss.score < -90000)
    }
    @Test fun ignoreBoundsAndNonPrincipalMessages() {
        assertNull(UciProtocol.parse("info depth 5 score cp 80 lowerbound pv e2e4"))
        assertNull(UciProtocol.parse("info string NNUE network loaded"))
        assertNull(UciProtocol.parse("bestmove e2e4"))
        assertNull(UciProtocol.parse("info depth 5 nodes 12"))
    }
    @Test fun largeNodeCountsDoNotOverflow() {
        val line = UciProtocol.parse("info depth 40 score cp 10 nodes 9876543210 pv e2e4")!!
        assertEquals(Int.MAX_VALUE, line.nodes)
    }
}
