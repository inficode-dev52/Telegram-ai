package com.aditya.ai.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.aditya.ai.data.V2Store
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

data class TtsVoiceInfo(val name: String, val locale: String, val needsNetwork: Boolean)

/**
 * Text-to-speech and one-shot speech recognition through the standard Android services.
 * Nothing is assumed to exist: every failure becomes a readable [status] message, and the microphone is only used
 * after an explicit tap (no always-on listening).
 */
class VoiceIO(private val ctx: Context, private val v2: V2Store) {
    private val main = Handler(Looper.getMainLooper())
    val speaking = MutableStateFlow(false)
    val listening = MutableStateFlow(false)
    val level = MutableStateFlow(0f)
    val status = MutableStateFlow<String?>(null)
    val ttsReady = MutableStateFlow(false)
    val voices = MutableStateFlow<List<TtsVoiceInfo>>(emptyList())
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var pendingSpeak: String? = null

    private fun locale(): Locale { val tag = v2.str("lang", ""); return if (tag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(tag) }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                ttsReady.value = true; applyLanguage(); refreshVoices()
                pendingSpeak?.let { doSpeak(it); pendingSpeak = null }
            } else { status.value = "Text-to-speech is not available on this device."; tts = null }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking.value = true }
            override fun onDone(utteranceId: String?) { speaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { speaking.value = false }
        })
    }

    private fun applyLanguage() {
        val e = tts ?: return
        val loc = locale()
        val r = e.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) status.value = "Voice data for ${loc.displayName} is not installed on this device."
        val name = v2.str("tts_voice", "")
        if (name.isNotBlank()) runCatching { e.voices?.firstOrNull { it.name == name }?.let { v -> e.setVoice(v) } }
    }

    private fun refreshVoices() {
        voices.value = runCatching { tts?.voices?.map { TtsVoiceInfo(it.name, it.locale.displayName, it.isNetworkConnectionRequired) }?.sortedBy { it.locale + it.name } ?: emptyList() }.getOrDefault(emptyList())
    }

    /** Called when settings open so the voice list is available. */
    fun prepare() { main.post { ensureTts(); if (ttsReady.value) refreshVoices() } }

    fun speak(text: String) {
        val clean = text.replace(Regex("[*_`#>]"), "").trim().take(1500)
        if (clean.isEmpty()) return
        main.post {
            ensureTts()
            if (ttsReady.value) doSpeak(clean) else pendingSpeak = clean
        }
    }

    private fun doSpeak(t: String) {
        val e = tts ?: return
        applyLanguage()
        e.setSpeechRate(v2.float("tts_rate", 1f))
        val b = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, v2.float("tts_volume", 1f)) }
        e.speak(t, TextToSpeech.QUEUE_FLUSH, b, "aditya-${System.nanoTime()}")
    }

    fun stopSpeaking() { main.post { tts?.stop(); speaking.value = false } }

    fun recognitionAvailable() = SpeechRecognizer.isRecognitionAvailable(ctx)
    fun hasMicPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Tap-to-talk. Delivers the final transcript once; [onFinal] is not called for empty or failed recognition. */
    fun startListening(onFinal: (String) -> Unit) {
        if (!recognitionAvailable()) { status.value = "Speech recognition isn't available on this device."; return }
        if (!hasMicPermission()) { status.value = "Microphone permission is needed for voice input."; return }
        main.post {
            tts?.stop(); speaking.value = false
            recognizer?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(ctx).also { recognizer = it }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listening.value = false }
                override fun onError(error: Int) {
                    listening.value = false; level.value = 0f
                    status.value = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything. Tap the microphone to try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for voice input."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service needs a network connection."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech service is busy. Try again in a moment."
                        else -> "Speech recognition failed (code $error)."
                    }
                }
                override fun onResults(results: Bundle?) {
                    listening.value = false; level.value = 0f
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (t.isEmpty()) status.value = "I didn't catch that." else onFinal(t)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale().toLanguageTag()).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            status.value = null; listening.value = true
            runCatching { r.startListening(i) }.onFailure { listening.value = false; status.value = "Couldn't start speech recognition." }
        }
    }

    fun stopListening() { main.post { recognizer?.stopListening(); listening.value = false; level.value = 0f } }

    fun release() { main.post { recognizer?.destroy(); recognizer = null; tts?.shutdown(); tts = null; ttsReady.value = false } }
}
