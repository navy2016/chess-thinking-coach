package com.sanqi.coach.engine

import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class EngineProcessTest {
    private fun process(script: String): EngineProcess = EngineProcess(
        File("/bin/sh"), File(System.getProperty("java.io.tmpdir")), listOf("-c", script))

    @Test fun protocolLinesAreNotTruncatedAt64KiB() {
        process("s=x; i=0; while [ \$i -lt 17 ]; do s=\$s\$s; i=\$((i+1)); done; printf '%s\\n' \"\$s\"").use { engine ->
            val line = engine.read(System.nanoTime() + 10_000_000_000L) {}
            assertEquals(131072, line.length)
        }
    }

    @Test fun closeTerminatesABlockedEngine() {
        val engine = process("read line")
        engine.close()
        try {
            engine.read(System.nanoTime() + 2_000_000_000L) {}
            fail("Closed process must not return a protocol line")
        } catch (error: EngineException) {
            assertEquals(EngineProblem.PROTOCOL, error.problem)
        }
    }

    @Test fun cancellationPropagatesWithoutWaitingForEngineOutput() {
        process("read line").use { engine ->
            try {
                engine.read(System.nanoTime() + 10_000_000_000L) { throw CancellationException() }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertEquals(true, engine.isAlive)
            }
        }
    }
}
