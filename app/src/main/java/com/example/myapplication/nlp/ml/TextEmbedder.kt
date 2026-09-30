package com.example.myapplication.nlp.ml

/**
 * Produces a fixed-length semantic embedding for a piece of text.
 *
 * The concrete on-device implementation is a TensorFlow Lite Universal Sentence
 * Encoder (see notes in [com.example.myapplication.nlp.ml.ParserFactory]); abstracting
 * it behind this interface keeps [MlIntentParser] testable with a fake embedder and lets
 * the app run with the rule-based parser when no model is bundled.
 */
interface TextEmbedder {
    /** False until the underlying model is loaded; callers should fall back to rules. */
    val isReady: Boolean

    /** Returns the embedding vector for [text]. Only valid when [isReady] is true. */
    fun embed(text: String): FloatArray
}
