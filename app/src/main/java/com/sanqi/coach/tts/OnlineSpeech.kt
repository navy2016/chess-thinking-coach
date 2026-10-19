package com.sanqi.coach.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import com.sanqi.coach.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class OnlineSpeech(
    private val app: Context,
    private val status: (String) -> Unit,
    private val system: (String, Float, Boolean) -> Unit
) {
    private data class Request(val text: String, val rate: Float)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val client = EdgeTtsClient()
    private val queue = java.util.ArrayDeque<Request>()
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var closed = false
    var usingSystem = false
        private set
    private val focus = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
    }

    init { main.post { if (!closed) status(app.getString(R.string.edge_ready)) } }

    fun speak(text: String, rate: Float, interrupt: Boolean) {
        main.post {
            if (closed || text.isBlank()) return@post
            if (usingSystem) { system(text, rate, interrupt); return@post }
            if (interrupt) cancelPlayback()
            queue.add(Request(text, rate))
            if (job?.isActive == true) return@post
            job = scope.launch {
                while (queue.isNotEmpty()) {
                    val request = queue.removeFirst()
                    val chunks = SpeechText.chunks(request.text)
                    for ((index, chunk) in chunks.withIndex()) {
                        ensureActive()
                        status(app.getString(R.string.edge_loading))
                        val voice = app.getSharedPreferences("coach", Context.MODE_PRIVATE)
                            .getString("edgeVoice", EdgeTtsClient.VOICES.first()) ?: EdgeTtsClient.VOICES.first()
                        val bytes = try {
                            client.synthesize(chunk, voice, request.rate)
                        } catch (_: Exception) {
                            ensureActive()
                            usingSystem = true
                            status(app.getString(R.string.edge_fallback))
                            system(chunks.drop(index).joinToString(" "), request.rate, true)
                            while (queue.isNotEmpty()) queue.removeFirst().let { system(it.text, it.rate, false) }
                            return@launch
                        }
                        var file: File? = null
                        try {
                            withContext(Dispatchers.IO) {
                                file = File.createTempFile("coach-speech-", ".mp3", app.cacheDir)
                                file!!.writeBytes(bytes)
                            }
                            ensureActive()
                            play(file!!)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            status(app.getString(R.string.tts_play_failed, MediaPlayer.MEDIA_ERROR_UNKNOWN))
                            queue.clear()
                            return@launch
                        } finally {
                            file?.delete()
                        }
                    }
                }
                status(app.getString(R.string.edge_ready))
            }
        }
    }

    private suspend fun play(file: File) {
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            status(app.getString(R.string.tts_volume_zero))
            queue.clear()
            throw CancellationException("Muted")
        }
        @Suppress("DEPRECATION")
        val granted = audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            status(app.getString(R.string.tts_audio_busy))
            queue.clear()
            throw CancellationException("Audio focus unavailable")
        }
        val media = MediaPlayer()
        player = media
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                media.setDataSource(file.absolutePath)
                media.setOnPreparedListener {
                    if (continuation.isActive) { status(app.getString(R.string.tts_speaking)); it.start() }
                }
                media.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
                media.setOnErrorListener { _, _, _ ->
                    if (continuation.isActive) continuation.resumeWithException(java.io.IOException("Audio playback failed"))
                    true
                }
                media.prepareAsync()
            }
        } finally {
            if (player === media) player = null
            media.release()
            audio.abandonAudioFocus(focus)
        }
    }

    private fun cancelPlayback() {
        queue.clear()
        job?.cancel()
        job = null
    }

    fun stop() { main.post { cancelPlayback() } }
    fun shutdown() {
        closed = true
        cancelPlayback()
        scope.cancel()
    }
}
