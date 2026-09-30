package com.example.myapplication.nlp.ml

/**
 * Deterministic bag-of-words embedder for tests: identical strings embed identically
 * (cosine 1.0); strings sharing no words have cosine 0. This exercises the parsers'
 * matching/threshold mechanics without a real model.
 */
class FakeEmbedder(override val isReady: Boolean = true) : TextEmbedder {
    override fun embed(text: String): FloatArray {
        val v = FloatArray(64)
        for (word in text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            v[(word.hashCode() and 0x7fffffff) % 64] += 1f
        }
        return v
    }
}
