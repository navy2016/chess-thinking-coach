package com.sanqi.coach.engine

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class ResumableModelDownloader(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {
    enum class Phase { CONNECTING, DOWNLOADING, RETRYING, RESTARTING, VERIFYING }
    class InvalidDownload(message: String) : IOException(message)
    companion object {
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        fun partial(target: File, sha256: String): File = File(target.parentFile, "${target.name}.${sha256.take(16)}.part")
    }
    @Volatile private var active: HttpURLConnection? = null

    fun cancel() { active?.disconnect() }

    @Synchronized
    fun download(url: String, target: File, bytes: Long, sha256: String, cancelled: () -> Unit,
                 progress: (Long, Phase, Int) -> Unit) {
        require(bytes > 0 && sha256.matches(Regex("[a-f0-9]{64}")))
        require(URL(url).protocol == "https")
        val lock = locks.getOrPut(target.canonicalPath) { ReentrantLock() }
        while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) cancelled()
        try {
            cancelled()
            if (target.isFile && target.length() == bytes) {
                progress(bytes, Phase.VERIFYING, 0)
                if (matches(target, bytes, sha256, cancelled)) return
            }
            val part = partial(target, sha256)
            if (part.length() > bytes) check(part.delete())
            if (target.parentFile!!.usableSpace < bytes - part.length() + 32L * 1024 * 1024)
                throw InvalidDownload("INSUFFICIENT_SPACE")
            var failures = 0
            var restarted = false
            while (part.length() < bytes) {
                cancelled()
                try {
                    progress(part.length(), Phase.CONNECTING, failures)
                    var address = URL(url)
                    var source: HttpURLConnection? = null
                    for (redirect in 0..5) {
                        cancelled()
                        if (address.protocol != "https") throw InvalidDownload("INSECURE_REDIRECT")
                        val connection = connect(address)
                        active = connection
                        connection.connectTimeout = 15000
                        connection.readTimeout = 15000
                        connection.instanceFollowRedirects = false
                        connection.setRequestProperty("Accept-Encoding", "identity")
                        if (part.length() > 0) connection.setRequestProperty("Range", "bytes=${part.length()}-")
                        val status = connection.responseCode
                        if (status in listOf(301, 302, 303, 307, 308)) {
                            val location = connection.getHeaderField("Location") ?: throw InvalidDownload("REDIRECT_WITHOUT_LOCATION")
                            address = URL(address, location)
                            connection.disconnect()
                            if (redirect == 5) throw InvalidDownload("TOO_MANY_REDIRECTS")
                        } else {
                            source = connection
                            break
                        }
                    }
                    val response = source ?: throw InvalidDownload("NO_RESPONSE")
                    var offset = part.length()
                    val status = response.responseCode
                    if (status == 416 && !restarted) {
                        progress(0, Phase.RESTARTING, failures)
                        if (part.exists() && !part.delete()) throw InvalidDownload("PARTIAL_RESET_FAILED")
                        restarted = true
                        continue
                    }
                    if (status == 408 || status == 429 || status in 500..599) throw IOException("TEMPORARY_HTTP_$status")
                    if (status != 200 && status != 206) throw InvalidDownload("HTTP_$status")
                    val encoding = response.getHeaderField("Content-Encoding")
                    if (encoding != null && !encoding.equals("identity", true)) throw InvalidDownload("CONTENT_ENCODING")
                    val expected = if (status == 206) {
                        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.getHeaderField("Content-Range").orEmpty())
                            ?: throw InvalidDownload("CONTENT_RANGE_MISSING")
                        val start = range.groupValues[1].toLongOrNull()
                        val end = range.groupValues[2].toLongOrNull()
                        val total = range.groupValues[3].toLongOrNull()
                        if (start != offset || total != bytes || end == null || end < offset || end >= bytes)
                            throw InvalidDownload("CONTENT_RANGE_MISMATCH")
                        end - offset + 1
                    } else {
                        if (offset > 0) progress(0, Phase.RESTARTING, failures)
                        offset = 0
                        bytes
                    }
                    val declared = response.getHeaderField("Content-Length")?.toLongOrNull()
                    if (declared != null && declared != expected) throw InvalidDownload("CONTENT_LENGTH_MISMATCH")
                    var received = 0L
                    response.inputStream.use { input ->
                        FileOutputStream(part, offset > 0).use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                cancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (received + count > expected) throw InvalidDownload("MODEL_SIZE_MISMATCH")
                                output.write(buffer, 0, count)
                                received += count
                                progress(offset + received, Phase.DOWNLOADING, failures)
                            }
                            output.fd.sync()
                        }
                    }
                    if (received != expected) throw IOException("INCOMPLETE_RESPONSE")
                } catch (error: IOException) {
                    cancelled()
                    if (error is InvalidDownload || failures >= 2) throw error
                    failures++
                    progress(part.length(), Phase.RETRYING, failures)
                    repeat(if (failures == 1) 10 else 30) { cancelled(); sleep(100) }
                } finally {
                    active?.disconnect()
                    active = null
                }
            }
            cancelled()
            progress(bytes, Phase.VERIFYING, 0)
            if (!matches(part, bytes, sha256, cancelled)) {
                part.delete()
                throw InvalidDownload("MODEL_CHECKSUM_MISMATCH")
            }
            cancelled()
            if (!part.renameTo(target)) throw InvalidDownload("MODEL_INSTALL_FAILED")
        } finally {
            active?.disconnect()
            active = null
            lock.unlock()
        }
    }

    private fun matches(file: File, bytes: Long, sha256: String, cancelled: () -> Unit): Boolean {
        if (!file.isFile || file.length() != bytes) return false
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                cancelled()
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return file.length() == bytes && hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == sha256
    }
}
