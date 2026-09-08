package com.example.myapplication.nlp.ml

import com.example.myapplication.nlp.AppIntent
import com.example.myapplication.nlp.IntentLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val testExamples = mapOf(
    IntentLabel.BATTERY to listOf("battery level"),
    IntentLabel.FLASHLIGHT to listOf("glow stick")
)

class MlIntentParserTest {

    @Test
    fun exactMatch_scoresHighAndBuildsIntent() {
        val parser = MlIntentParser(FakeEmbedder(), testExamples)
        val match = parser.bestMatch("glow stick")
        assertEquals(IntentLabel.FLASHLIGHT, match!!.label)
        assertTrue(match.score > 0.99f)
        assertTrue(parser.parse("glow stick") is AppIntent.Flashlight)
    }

    @Test
    fun unrelatedQuery_belowThreshold_returnsUnknown() {
        val parser = MlIntentParser(FakeEmbedder(), testExamples)
        assertEquals(AppIntent.Unknown, parser.parse("completely unrelated phrasing here"))
    }

    @Test
    fun notReadyEmbedder_returnsNoMatch() {
        val parser = MlIntentParser(FakeEmbedder(isReady = false), testExamples)
        assertNull(parser.bestMatch("battery level"))
    }

    @Test
    fun cosineSimilarity_orthogonalIsZero_identicalIsOne() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0f, MlIntentParser.cosineSimilarity(a, b), 1e-6f)
        assertEquals(1f, MlIntentParser.cosineSimilarity(a, a), 1e-6f)
    }
}
