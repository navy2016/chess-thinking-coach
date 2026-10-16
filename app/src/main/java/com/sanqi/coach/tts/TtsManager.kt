package com.sanqi.coach.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 中文 TTS 封装：朗读讲解文本。初始化异步，未就绪时静默跳过。
 */
class TtsManager(context: Context) {

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    fun speak(text: String, rate: Float) {
        if (!ready) return
        val t = tts ?: return
        t.setSpeechRate(rate)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "coach-utt")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
