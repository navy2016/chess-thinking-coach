package com.sanqi.coach.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.sanqi.coach.R
import java.util.Locale

class TtsManager(context: Context, private val offlineOnly: Boolean = false, private val onStatus: (String) -> Unit = {}) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var initialized = false
    private var failed = false
    private var closed = false
    private var generation = 0L
    private var serial = 0L
    private val pending = mutableListOf<Request>()
    private val queued = mutableSetOf<String>()
    private data class Request(val text: String, val rate: Float, val interrupt: Boolean)
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
    }

    private val online = app.getSharedPreferences("coach", Context.MODE_PRIVATE).let { prefs ->
        if (!offlineOnly && prefs.getBoolean("edgeTts", false) && prefs.getBoolean("edgeConsent", false))
            OnlineSpeech(app, onStatus) { text, rate, interrupt -> speakSystem(text, rate, interrupt) }
        else null
    }

    init {
        val engine = app.getSharedPreferences("coach", Context.MODE_PRIVATE).getString("ttsEngine", null)
        val listener = TextToSpeech.OnInitListener { result -> main.post {
            if (!closed) initialize(result)
        } }
        tts = if (engine.isNullOrEmpty()) TextToSpeech(app, listener) else TextToSpeech(app, listener, engine)
    }

    fun engines(): List<Pair<String, String>> = tts?.engines.orEmpty().map { it.name to it.label }

    private fun report(resource: Int, code: Int? = null) {
        val message = if (code == null) app.getString(resource) else app.getString(resource, code)
        Log.i("CoachTts", "state=$resource code=$code")
        if (online == null || online.usingSystem) onStatus(message)
    }

    private fun initialize(result: Int) {
        val engine = tts ?: return
        if (result != TextToSpeech.SUCCESS) {
            failed = true
            pending.clear()
            report(if (engine.engines.isEmpty()) R.string.tts_no_engine else R.string.tts_init_failed)
            return
        }
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { update(id) { report(R.string.tts_speaking) } }
            override fun onDone(id: String?) { update(id) {
                id?.let { queued.remove(it) }
                if (queued.isEmpty()) {
                    audio.abandonAudioFocus(focusListener)
                    report(R.string.tts_ready)
                }
            } }
            @Deprecated("Legacy callback")
            override fun onError(id: String?) { onError(id, TextToSpeech.ERROR) }
            override fun onError(id: String?, code: Int) { update(id) {
                generation++
                queued.clear()
                tts?.stop()
                audio.abandonAudioFocus(focusListener)
                report(R.string.tts_play_failed, code)
            } }
        })
        initialized = true
        report(R.string.tts_ready)
        val requests = pending.toList()
        pending.clear()
        requests.forEach(::play)
    }

    private fun update(id: String?, action: () -> Unit) {
        main.post { if (!closed && id?.substringBefore(':') == generation.toString()) action() }
    }

    fun speak(text: String, rate: Float, interrupt: Boolean = true) {
        if (online != null) online.speak(text, rate.coerceIn(0.5f, 2f), interrupt)
        else speakSystem(text, rate, interrupt)
    }

    private fun speakSystem(text: String, rate: Float, interrupt: Boolean) {
        main.post {
            if (closed || text.isBlank()) return@post
            if (failed) { report(R.string.tts_init_failed); return@post }
            val request = Request(text, rate.coerceIn(0.5f, 2f), interrupt)
            if (!initialized) {
                if (interrupt) pending.clear()
                pending.add(request)
                report(R.string.tts_initializing)
            } else play(request)
        }
    }

    private fun play(request: Request) {
        val engine = tts ?: return
        val language = if (request.text.any { it in '\u3400'..'\u9fff' }) Locale.SIMPLIFIED_CHINESE
            else app.resources.configuration.locales[0]
        if (offlineOnly) {
            val voice = engine.voices.orEmpty().filter {
                !it.isNetworkConnectionRequired && it.locale.language == language.language &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }.sortedBy { if (it.locale.country == language.country) 0 else 1 }.firstOrNull()
            if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
                report(R.string.offline_voice_needed)
                return
            }
        } else {
            val supported = engine.setLanguage(language)
            if (supported < TextToSpeech.LANG_AVAILABLE) {
                report(if (supported == TextToSpeech.LANG_MISSING_DATA) R.string.tts_missing_data else R.string.tts_language_unavailable)
                return
            }
        }
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            report(R.string.tts_volume_zero)
            return
        }
        @Suppress("DEPRECATION")
        val focus = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { report(R.string.tts_audio_busy); return }
        if (engine.setSpeechRate(request.rate) != TextToSpeech.SUCCESS) {
            if (queued.isEmpty()) audio.abandonAudioFocus(focusListener)
            report(R.string.tts_play_failed, TextToSpeech.ERROR)
            return
        }
        if (request.interrupt) { generation++; queued.clear() }
        val chunks = request.text.chunked(minOf(1800, TextToSpeech.getMaxSpeechInputLength()))
        for ((index, chunk) in chunks.withIndex()) {
            val mode = if (request.interrupt && index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = "$generation:${++serial}"
            queued.add(id)
            val result = engine.speak(chunk, mode, Bundle(), id)
            if (result != TextToSpeech.SUCCESS) {
                generation++
                queued.clear()
                engine.stop()
                audio.abandonAudioFocus(focusListener)
                report(R.string.tts_play_failed, result)
                return
            }
        }
    }

    fun stop() {
        online?.stop()
        main.post {
            generation++
            pending.clear()
            queued.clear()
            tts?.stop()
            audio.abandonAudioFocus(focusListener)
        }
    }

    fun shutdown() {
        online?.shutdown()
        closed = true
        generation++
        pending.clear()
        queued.clear()
        tts?.stop()
        tts?.shutdown()
        audio.abandonAudioFocus(focusListener)
        tts = null
    }
}
