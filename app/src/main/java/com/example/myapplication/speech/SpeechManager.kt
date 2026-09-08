package com.example.myapplication.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Wraps [TextToSpeech] and [SpeechRecognizer] so the UI doesn't have to manage their
 * lifecycles or listener boilerplate (previously inline in MainActivity).
 *
 * Recognition results and errors are delivered via [resultListener]/[errorListener];
 * [isListening] is exposed as a [StateFlow] for the UI to reflect mic state. Unlike the
 * previous implementation, recognition errors are surfaced rather than silently dropped.
 */
class SpeechManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(appContext))
            SpeechRecognizer.createSpeechRecognizer(appContext) else null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    /** Invoked with the best recognition match. */
    var resultListener: ((String) -> Unit)? = null

    /** Invoked with a [SpeechRecognizer] error code when recognition fails. */
    var errorListener: ((Int) -> Unit)? = null

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
    }

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _isListening.value = false }
            override fun onError(error: Int) {
                _isListening.value = false
                this@SpeechManager.errorListener?.invoke(error)
            }
            override fun onResults(results: Bundle?) {
                _isListening.value = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) this@SpeechManager.resultListener?.invoke(matches[0])
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    val isRecognitionAvailable: Boolean get() = recognizer != null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setPitch(1.05f)
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    fun startListening() {
        val r = recognizer
        if (r == null) {
            errorListener?.invoke(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        _isListening.value = true
        r.startListening(recognizerIntent)
    }

    fun destroy() {
        tts?.shutdown()
        tts = null
        recognizer?.destroy()
    }
}
