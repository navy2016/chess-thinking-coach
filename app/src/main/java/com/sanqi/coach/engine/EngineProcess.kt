package com.sanqi.coach.engine

import java.io.Closeable
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class EngineProcess(executable: File, directory: File, arguments: List<String> = emptyList()) : Closeable {
    private val process: Process
    private val output = ArrayBlockingQueue<String>(256)
    @Volatile private var ended = false
    val isAlive: Boolean get() = !ended
    private val input: java.io.BufferedWriter

    init {
        if (!executable.isFile || !executable.canExecute()) throw EngineException(EngineProblem.NOT_PACKAGED)
        process = ProcessBuilder(listOf(executable.absolutePath) + arguments).directory(directory).start()
        input = process.outputStream.bufferedWriter()
        Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!output.offer(line)) { process.destroy(); break }
                    }
                }
            } catch (_: java.io.IOException) {
                process.destroy()
            } finally { ended = true }
        }.apply { isDaemon = true; name = "engine-output"; start() }
        Thread {
            try {
                process.errorStream.bufferedReader().useLines { lines -> lines.forEach { _ -> Unit } }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; name = "engine-errors"; start() }
    }

    @Synchronized fun send(command: String) {
        require(!command.contains('\n') && !command.contains('\r'))
        input.write(command)
        input.newLine()
        input.flush()
    }

    fun read(deadlineNanos: Long, cancelled: () -> Unit): String {
        while (true) {
            cancelled()
            if (System.nanoTime() >= deadlineNanos) throw EngineException(EngineProblem.TIMEOUT)
            val line = output.poll(100, TimeUnit.MILLISECONDS)
            if (line != null) {
                if (line.contains("CRITICAL ERROR")) throw EngineException(EngineProblem.PROTOCOL)
                return line
            }
            if (ended) throw EngineException(EngineProblem.PROTOCOL)
        }
    }

    fun await(token: String, seconds: Int, cancelled: () -> Unit) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (read(deadline, cancelled) != token) Unit
    }

    override fun close() {
        ended = true
        process.destroy()
        try { input.close() } catch (_: Exception) { }
    }
}
