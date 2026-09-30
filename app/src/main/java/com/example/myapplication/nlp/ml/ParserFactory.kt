package com.example.myapplication.nlp.ml

import android.content.Context
import com.example.myapplication.nlp.IntentParser
import com.example.myapplication.nlp.RuleBasedParser

/**
 * Single place that constructs the [IntentParser] used by the app.
 *
 * Today it returns a [CompositeParser] with no ML embedder, so it behaves exactly like
 * the deterministic [RuleBasedParser]. The [CompositeParser]/[MlIntentParser]/
 * [TextEmbedder] seam is in place so the on-device model is a drop-in upgrade.
 *
 * ## Enabling on-device ML NLU
 * 1. Add the dependency (resolves from Maven Central):
 *    `implementation("org.tensorflow:tensorflow-lite-task-text:0.4.4")`
 * 2. Bundle a Universal Sentence Encoder Lite model at
 *    `app/src/main/assets/universal_sentence_encoder.tflite`.
 * 3. Implement [TextEmbedder] with the Task Library, e.g.:
 *    ```
 *    class TfLiteTextEmbedder(context: Context) : TextEmbedder {
 *        private val embedder = runCatching {
 *            org.tensorflow.lite.task.text.textembedder.TextEmbedder
 *                .createFromFile(context, "universal_sentence_encoder.tflite")
 *        }.getOrNull()
 *        override val isReady get() = embedder != null
 *        override fun embed(text: String): FloatArray =
 *            embedder!!.embed(text).embeddings[0].featureVector.values
 *    }
 *    ```
 * 4. Return `CompositeParser(MlIntentParser(TfLiteTextEmbedder(context)))` below.
 *
 * Because [MlIntentParser.bestMatch] returns null when the embedder isn't ready, and
 * [CompositeParser] falls back to rules below its confidence threshold, the app degrades
 * gracefully if the model is missing or unsure.
 */
object ParserFactory {

    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): IntentParser {
        // No model bundled yet -> deterministic rules. See the KDoc above to enable ML.
        return CompositeParser(ml = null, rules = RuleBasedParser())
    }
}
