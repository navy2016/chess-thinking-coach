package com.sanqi.coach.tts

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class EdgeTtsClient {
    companion object {
        val VOICES = listOf("zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural")
        private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).build()
        private fun id() = UUID.randomUUID().toString().replace("-", "")
    }

    suspend fun synthesize(text: String, voice: String, rate: Float): ByteArray = withTimeout(25_000) {
        require(voice in VOICES)
        val escaped = SpeechText.xml(text)
        require(escaped.toByteArray(Charsets.UTF_8).size <= 4096)
        val token = EdgeGec.token()
        val url = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=${EdgeGec.CLIENT}&ConnectionId=${id()}&Sec-MS-GEC=$token&Sec-MS-GEC-Version=${EdgeGec.VERSION}"
        val request = Request.Builder().url(url)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
            .header("Pragma", "no-cache").header("Cache-Control", "no-cache")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${id().uppercase(Locale.ROOT)};").build()
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val audio = ByteArrayOutputStream()
            val socket = http.newWebSocket(request, object : WebSocketListener() {
                private fun fail(socket: WebSocket, message: String) {
                    if (finished.compareAndSet(false, true)) {
                        socket.cancel()
                        if (continuation.isActive) continuation.resumeWithException(IOException(message))
                    }
                }
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!continuation.isActive) { webSocket.cancel(); return }
                    val time = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
                    val config = "X-Timestamp:$time\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                    val percent = ((rate.coerceIn(0.5f, 2f) - 1f) * 100).toInt()
                    val speed = (if (percent >= 0) "+" else "") + percent + "%"
                    val fullVoice = "Microsoft Server Speech Text to Speech Voice (zh-CN, ${voice.removePrefix("zh-CN-")})"
                    val ssml = "X-RequestId:${id()}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${time}Z\r\nPath:ssml\r\n\r\n" +
                        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='$fullVoice'>" +
                        "<prosody pitch='+0Hz' rate='$speed' volume='+0%'>$escaped</prosody></voice></speak>"
                    if (!webSocket.send(config) || !webSocket.send(ssml)) fail(webSocket, "Speech request not sent")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val path = text.substringBefore("\r\n\r\n").lineSequence()
                        .firstOrNull { it.startsWith("Path:") }?.substringAfter(':')?.trim()
                    if (path == "turn.end") {
                        if (audio.size() == 0) { fail(webSocket, "Speech returned no audio"); return }
                        if (finished.compareAndSet(false, true)) {
                            webSocket.close(1000, null)
                            if (continuation.isActive) continuation.resume(audio.toByteArray())
                        }
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (finished.get()) return
                    val chunk = try { EdgeAudioFrame.decode(bytes.toByteArray()) }
                        catch (_: IllegalArgumentException) { fail(webSocket, "Invalid speech frame"); return }
                    if (audio.size() + chunk.size > 4 * 1024 * 1024) { fail(webSocket, "Speech audio limit exceeded"); return }
                    audio.write(chunk)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    fail(webSocket, "Online speech connection failed")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!finished.get()) fail(webSocket, "Speech connection closed before completion")
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (!finished.get()) fail(webSocket, "Speech connection closed before completion")
                    else webSocket.close(code, null)
                }
            })
            continuation.invokeOnCancellation { finished.set(true); socket.cancel() }
        }
    }
}

internal object EdgeAudioFrame {
    fun decode(frame: ByteArray): ByteArray {
        require(frame.size >= 2)
        val length = ((frame[0].toInt() and 255) shl 8) or (frame[1].toInt() and 255)
        require(length <= frame.size - 2)
        val headers = String(frame, 2, length, Charsets.UTF_8).lineSequence().filter { ':' in it }
            .associate { it.substringBefore(':').trim().lowercase(Locale.ROOT) to it.substringAfter(':').trim() }
        require(headers["path"] == "audio")
        val body = frame.copyOfRange(2 + length, frame.size)
        if (body.isEmpty() && headers["content-type"] == null) return body
        require(headers["content-type"] == "audio/mpeg")
        return body
    }
}
