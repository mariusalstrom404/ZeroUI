package com.example.myapplication.speech

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.myapplication.ui.UserPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Process-wide text-to-speech shared by the conversational UI and the accessibility service.
 *
 * Respects [UserPreferences.ttsMuted] and [UserPreferences.speechRate], and automatically selects
 * the highest-quality offline voice matching the spoken text's language (English or Chinese).
 */
object Speaker : TextToSpeech.OnInitListener {

    private const val TAG = "Speaker"
    private const val MAX_SPEECH_WAIT_MS = 30000L

    private var tts: TextToSpeech? = null
    private var ready = false
    /** Spoken as soon as the engine is ready, if [speak] was called before that. */
    private var pending: String? = null
    private var currentVoiceLocale: String? = null
    private val cachedVoices = mutableMapOf<String, Voice?>()

    /** Starts the engine if it isn't running yet. Safe to call from every component. */
    fun init(context: Context) {
        UserPreferences.init(context)
        if (tts == null) tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Text-to-speech failed to initialise: $status")
            tts = null
            return
        }
        ready = true
        applyLocaleAndRate(engine, Locale.US)
        pending?.let { speak(it) }
        pending = null
    }

    private fun containsCjk(text: String): Boolean =
        text.any { ch ->
            val block = Character.UnicodeBlock.of(ch)
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.BOPOMOFO
        }

    private fun localeFor(text: String): Locale = when {
        containsCjk(text) -> Locale.TRADITIONAL_CHINESE
        UserPreferences.speechLocaleTag.value == UserPreferences.LOCALE_ZH_TW && containsCjk(text) -> Locale.TRADITIONAL_CHINESE
        else -> Locale.US
    }

    private fun applyLocaleAndRate(engine: TextToSpeech, locale: Locale) {
        engine.setSpeechRate(UserPreferences.speechRate.value)
        val key = locale.toLanguageTag()
        if (currentVoiceLocale == key) return
        currentVoiceLocale = key

        val voice = cachedVoices.getOrPut(key) { bestVoice(engine, locale) }
        if (voice != null) {
            engine.voice = voice
        } else {
            val res = engine.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (locale.language == "zh") engine.setLanguage(Locale.CHINESE)
                else engine.setLanguage(Locale.US)
            }
        }
    }

    /** Highest-quality installed offline voice for [target], or null to keep the default. */
    private fun bestVoice(engine: TextToSpeech, target: Locale): Voice? = try {
        val installed = engine.voices?.filter {
            !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        }.orEmpty()
        installed
            .filter { it.locale.language == target.language && it.locale.country == target.country }
            .maxByOrNull { it.quality }
            ?: installed
                .filter { it.locale.language == target.language }
                .maxByOrNull { it.quality }
    } catch (e: Exception) {
        null
    }

    /** Reads [text] aloud unless muted. With [interrupt] it cuts off whatever is being said. */
    fun speak(text: String, interrupt: Boolean = true) {
        if (UserPreferences.ttsMuted.value) return
        if (!ready) {
            pending = text
            return
        }
        val engine = tts ?: return
        applyLocaleAndRate(engine, localeFor(text))
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, text.hashCode().toString())
    }

    /** Queues [text] and returns once it has been said (returns immediately when muted). */
    suspend fun speakAndWait(text: String) {
        if (UserPreferences.ttsMuted.value) return
        val engine = tts
        if (!ready || engine == null) return
        applyLocaleAndRate(engine, localeFor(text))
        val id = "wait-${SystemClock.uptimeMillis()}"
        val finished = CompletableDeferred<Unit>()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) finished.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) finished.complete(Unit) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id) finished.complete(Unit) }
        })
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        withTimeoutOrNull(MAX_SPEECH_WAIT_MS) { finished.await() }
    }

    fun stop() {
        pending = null
        tts?.stop()
    }
}
