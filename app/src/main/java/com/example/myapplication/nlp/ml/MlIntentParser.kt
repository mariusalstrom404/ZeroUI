package com.example.myapplication.nlp.ml

import com.example.myapplication.nlp.AppIntent
import com.example.myapplication.nlp.IntentLabel
import com.example.myapplication.nlp.IntentParser
import com.example.myapplication.nlp.IntentSlots
import kotlin.math.sqrt

/**
 * Classifies an utterance by embedding it and finding the nearest labelled example
 * (cosine similarity) in [IntentExamples]. Slot filling is then delegated to
 * [IntentSlots], so the only thing ML decides is the intent *category*.
 *
 * Example embeddings are computed lazily on first use and cached. If the embedder isn't
 * ready, [bestMatch] returns null so the caller can fall back to the rule-based parser.
 */
class MlIntentParser(
    private val embedder: TextEmbedder,
    examples: Map<IntentLabel, List<String>> = IntentExamples.DEFAULT,
    /** Minimum cosine similarity for [parse] to accept a match before giving up. */
    private val acceptThreshold: Float = 0.6f
) : IntentParser {

    private val flatExamples: List<Pair<IntentLabel, String>> =
        examples.flatMap { (label, phrases) -> phrases.map { label to it } }

    private val exampleEmbeddings: List<Pair<IntentLabel, FloatArray>> by lazy {
        flatExamples.map { (label, phrase) -> label to embedder.embed(phrase) }
    }

    data class Match(val label: IntentLabel, val score: Float)

    /** Best-scoring label for [utterance], or null if the embedder isn't ready. */
    fun bestMatch(utterance: String): Match? {
        if (!embedder.isReady) return null
        val query = embedder.embed(utterance)
        var best: Match? = null
        for ((label, emb) in exampleEmbeddings) {
            val score = cosineSimilarity(query, emb)
            if (best == null || score > best.score) best = Match(label, score)
        }
        return best
    }

    override fun parse(utterance: String): AppIntent {
        val match = bestMatch(utterance)
        return if (match != null && match.score >= acceptThreshold) {
            IntentSlots.build(match.label, utterance.trim())
        } else {
            AppIntent.Unknown
        }
    }

    companion object {
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.isEmpty() || a.size != b.size) return 0f
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            val denom = sqrt(na) * sqrt(nb)
            return if (denom == 0f) 0f else dot / denom
        }
    }
}
