package com.example.myapplication.speech

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Process-wide text-to-speech shared by the conversational UI and the accessibility service.
 *
 * It lives outside MainActivity so the service can read errors and questions aloud while another
 * app is in front, or after the activity has been destroyed. Uses the most natural voice the
 * device's TTS engine has installed for US English.
 */
object Speaker : TextToSpeech.OnInitListener {

    private const val TAG = "Speaker"
    private const val MAX_SPEECH_WAIT_MS = 30000L

    private var tts: TextToSpeech? = null
    private var ready = false
    /** Spoken as soon as the engine is ready, if [speak] was called before that. */
    private var pending: String? = null

    /** Starts the engine if it isn't running yet. Safe to call from every component. */
    fun init(context: Context) {
        if (tts == null) tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Text-to-speech failed to initialise: $status")
            tts = null
            return
        }
        engine.language = Locale.US
        bestVoice(engine)?.let { engine.voice = it }
        ready = true
        pending?.let { speak(it) }
        pending = null
    }

    /** Highest-quality installed US English voice that works offline, or null to keep the default. */
    private fun bestVoice(engine: TextToSpeech) = try {
        engine.voices
            ?.filter {
                it.locale.language == "en" && it.locale.country == "US" &&
                    !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
            }
            ?.maxByOrNull { it.quality }
    } catch (e: Exception) {
        null // some engines throw when asked for their voices
    }

    /** Reads [text] aloud. With [interrupt] it cuts off whatever is being said; otherwise it waits its turn. */
    fun speak(text: String, interrupt: Boolean = true) {
        if (!ready) {
            pending = text
            return
        }
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, null, text.hashCode().toString())
    }

    /** Queues [text] and returns once it has been said, so the mic doesn't pick up our own voice. */
    suspend fun speakAndWait(text: String) {
        val engine = tts
        if (!ready || engine == null) return
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

    /**
     * Returns once nothing is being said or waiting to be said, plus a moment for the speaker's
     * echo to die down, so a mic opened afterwards doesn't hear ZeroUI's own voice.
     */
    suspend fun awaitSilence() {
        withTimeoutOrNull(MAX_SPEECH_WAIT_MS) {
            delay(200) // a reply queued just now may not have started yet
            while (pending != null || tts?.isSpeaking == true) delay(100)
        }
        delay(300)
    }

    fun stop() {
        pending = null
        tts?.stop()
    }
}
