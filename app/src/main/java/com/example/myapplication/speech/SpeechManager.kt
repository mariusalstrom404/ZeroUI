package com.example.myapplication.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.myapplication.debug.Timing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Wraps [SpeechRecognizer] so the UI doesn't have to manage its lifecycle or listener
 * boilerplate. Speaking is done by [Speaker].
 *
 * Recognition results and errors are delivered via [resultListener]/[errorListener]. For the UI,
 * [isListening] tells whether the mic is open, [partialText] what has been recognised so far and
 * [level] how loud the user is speaking.
 */
class SpeechManager(context: Context) {

    companion object {
        /** Passed to [errorListener] when the device has no speech recognition at all. */
        const val ERROR_NOT_AVAILABLE = -1

        /** The user said nothing, or nothing recognisable: worth simply asking again. */
        fun heardNothing(error: Int): Boolean =
            error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

        /** What to tell the user about [error], or null when it needs no message (e.g. we cancelled). */
        fun messageFor(error: Int): String? = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "I didn't catch that. Tap the mic and try again."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                "Speech recognition needs an internet connection. Check it, or type your request instead."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "I need microphone permission to hear you. You can type instead."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "The microphone is busy. Try again in a moment."
            SpeechRecognizer.ERROR_AUDIO ->
                "I couldn't use the microphone. Try again, or type instead."
            ERROR_NOT_AVAILABLE ->
                "Speech recognition isn't available on this device. Please type instead."
            SpeechRecognizer.ERROR_CLIENT -> null
            else -> "Something went wrong with speech recognition. Please try again."
        }

        /** Typical speech is about -2 to 10 dB here; mapped to 0..1. */
        private fun normalise(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    private val appContext = context.applicationContext
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(appContext))
            SpeechRecognizer.createSpeechRecognizer(appContext) else null

    /** When listening started and when the user stopped speaking, for the timing log. */
    private var listenStart = 0L
    private var speechEnd = 0L

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _partialText = MutableStateFlow("")
    /** The words recognised so far while the user speaks; empty when not listening. */
    val partialText: StateFlow<String> = _partialText

    private val _level = MutableStateFlow(0f)
    /** Loudness of the user's voice from 0 to 1, while listening. */
    val level: StateFlow<Float> = _level

    /** Invoked with the best recognition match. */
    var resultListener: ((String) -> Unit)? = null

    /** Invoked with a [SpeechRecognizer] error code (or [ERROR_NOT_AVAILABLE]) when recognition fails. */
    var errorListener: ((Int) -> Unit)? = null

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                if (_isListening.value) _level.value = normalise(rmsdB)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                speechEnd = Timing.now()
                stopped()
            }
            override fun onError(error: Int) {
                stopped()
                _partialText.value = ""
                this@SpeechManager.errorListener?.invoke(error)
            }
            override fun onResults(results: Bundle?) {
                stopped()
                _partialText.value = ""
                val now = Timing.now()
                val afterSpeech = if (speechEnd >= listenStart) ", ${now - speechEnd} ms of it after speech ended" else ""
                Timing.log("Speech recognition: ${now - listenStart} ms from the mic opening$afterSpeech")
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) this@SpeechManager.resultListener?.invoke(matches[0])
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) _partialText.value = text
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    val isRecognitionAvailable: Boolean get() = recognizer != null

    fun startListening() {
        val r = recognizer
        if (r == null) {
            errorListener?.invoke(ERROR_NOT_AVAILABLE)
            return
        }
        _isListening.value = true
        _partialText.value = ""
        _level.value = 0f
        listenStart = Timing.now()
        speechEnd = 0L
        r.startListening(recognizerIntent)
    }

    /** The user is done talking: recognise what was said so far. */
    fun stopListening() {
        recognizer?.stopListening()
        stopped()
    }

    /** Closes the mic and drops what was heard, without a result or an error. */
    fun cancel() {
        recognizer?.cancel()
        stopped()
        _partialText.value = ""
    }

    private fun stopped() {
        _isListening.value = false
        _level.value = 0f
    }

    fun destroy() {
        recognizer?.destroy()
    }
}
