package com.sanqi.coach.engine

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.CancellationException

class ResumableModelDownloaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val address = "https://github.com"
    private val content = "abcdef".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }

    private class Response(code: Int, private val body: ByteArray, private val headers: Map<String, String> = emptyMap()) : HttpURLConnection(URL("https://github.com")) {
        private val status = code
        var closed = false
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy(): Boolean = false
    }

    @Test fun truncatedResponseResumesFromBytesAlreadyWritten() {
        val target = folder.newFolder().resolve("net")
        val first = Response(200, "abc".toByteArray(), mapOf("Content-Length" to "6"))
        val second = Response(206, "def".toByteArray(), mapOf("Content-Range" to "bytes 3-5/6", "Content-Length" to "3"))
        val queue = ArrayDeque(listOf(first, second))
        val downloader = ResumableModelDownloader({ queue.removeFirst() }, {})
        downloader.download(address, target, 6, hash, {}) { _, _, _ -> }
        assertArrayEquals(content, target.readBytes())
        assertEquals("bytes=3-", second.getRequestProperty("Range"))
        assertTrue(first.closed && second.closed)
        assertFalse(ResumableModelDownloader.partial(target, hash).exists())
    }

    @Test fun ignoredRangeRestartsInsteadOfAppending() {
        val target = folder.newFolder().resolve("net")
        ResumableModelDownloader.partial(target, hash).writeText("xx")
        val response = Response(200, content, mapOf("Content-Length" to "6"))
        val phases = mutableListOf<ResumableModelDownloader.Phase>()
        ResumableModelDownloader({ response }, {}).download(address, target, 6, hash, {}) { _, phase, _ -> phases.add(phase) }
        assertArrayEquals(content, target.readBytes())
        assertTrue(ResumableModelDownloader.Phase.RESTARTING in phases)
    }

    @Test fun invalidRangeNeverInstallsOrAppends() {
        val target = folder.newFolder().resolve("net")
        val part = ResumableModelDownloader.partial(target, hash)
        part.writeText("ab")
        val response = Response(206, "def".toByteArray(), mapOf("Content-Range" to "bytes 3-5/6"))
        try {
            ResumableModelDownloader({ response }, {}).download(address, target, 6, hash, {}) { _, _, _ -> }
            fail("Expected invalid range")
        } catch (_: ResumableModelDownloader.InvalidDownload) {
            assertFalse(target.exists())
            assertEquals("ab", part.readText())
        }
    }

    @Test fun range416CanRestartOnlyOnce() {
        val target = folder.newFolder().resolve("net")
        ResumableModelDownloader.partial(target, hash).writeText("ab")
        val queue = ArrayDeque(listOf(Response(416, byteArrayOf()), Response(200, content)))
        ResumableModelDownloader({ queue.removeFirst() }, {}).download(address, target, 6, hash, {}) { _, _, _ -> }
        assertArrayEquals(content, target.readBytes())
        assertTrue(queue.isEmpty())
    }

    @Test fun cancelledDownloadCanBeResumedByAnotherInstance() {
        val target = folder.newFolder().resolve("net")
        var cancelled = false
        try {
            ResumableModelDownloader({ Response(200, "abc".toByteArray()) }, {}).download(address, target, 6, hash,
                { if (cancelled) throw CancellationException() }) { _, phase, _ ->
                if (phase == ResumableModelDownloader.Phase.DOWNLOADING) cancelled = true
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals("abc", ResumableModelDownloader.partial(target, hash).readText())
            assertFalse(target.exists())
        }
        val response = Response(206, "def".toByteArray(), mapOf("Content-Range" to "bytes 3-5/6"))
        ResumableModelDownloader({ response }, {}).download(address, target, 6, hash, {}) { _, _, _ -> }
        assertArrayEquals(content, target.readBytes())
    }

    @Test fun checksumFailurePreservesExistingInstalledModel() {
        val target = folder.newFolder().resolve("net")
        target.writeText("existing")
        try {
            ResumableModelDownloader({ Response(200, "ABCDEF".toByteArray()) }, {}).download(address, target, 6, hash, {}) { _, _, _ -> }
            fail("Expected hash mismatch")
        } catch (_: ResumableModelDownloader.InvalidDownload) {
            assertEquals("existing", target.readText())
            assertFalse(ResumableModelDownloader.partial(target, hash).exists())
        }
    }

    @Test fun retriesAreBoundedAndLeavePartialData() {
        val target = folder.newFolder().resolve("net")
        val part = ResumableModelDownloader.partial(target, hash)
        part.writeText("abc")
        var connections = 0
        try {
            ResumableModelDownloader({ connections++; Response(503, byteArrayOf()) }, {}).download(address, target, 6, hash, {}) { _, _, _ -> }
            fail("Expected failure")
        } catch (_: IOException) {
            assertEquals(3, connections)
            assertEquals("abc", part.readText())
        }
    }

    @Test fun secondWriterCanCancelWhileWaitingForTheSameTarget() {
        val target = folder.newFolder().resolve("net")
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val first = executor.submit {
            ResumableModelDownloader({
                started.countDown()
                release.await(3, java.util.concurrent.TimeUnit.SECONDS)
                Response(200, content)
            }, {}).download(address, target, 6, hash, {}) { _, _, _ -> }
        }
        try {
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            try {
                ResumableModelDownloader({ throw AssertionError("Second writer connected") }, {}).download(address, target, 6, hash,
                    { throw CancellationException() }) { _, _, _ -> }
                fail("Expected waiting writer cancellation")
            } catch (_: CancellationException) { }
        } finally {
            release.countDown()
            first.get(4, java.util.concurrent.TimeUnit.SECONDS)
            executor.shutdownNow()
        }
        assertArrayEquals(content, target.readBytes())
    }

    @Test fun installedTargetIsRecheckedUnderTheTargetLock() {
        val target = folder.newFolder().resolve("net")
        target.writeBytes(content)
        ResumableModelDownloader({ throw AssertionError("Already verified target must not download again") }, {})
            .download(address, target, 6, hash, {}) { _, _, _ -> }
        assertArrayEquals(content, target.readBytes())
    }

    @Test fun completePartialIsVerifiedWithoutConnecting() {
        val target = folder.newFolder().resolve("net")
        ResumableModelDownloader.partial(target, hash).writeBytes(content)
        ResumableModelDownloader({ throw AssertionError("Unexpected network") }, {}).download(address, target, 6, hash, {}) { _, _, _ -> }
        assertArrayEquals(content, target.readBytes())
    }
}
