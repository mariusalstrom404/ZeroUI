package com.example.myapplication.nlp.ml

import com.example.myapplication.nlp.AppIntent
import com.example.myapplication.nlp.IntentLabel
import com.example.myapplication.nlp.RuleBasedParser
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeParserTest {

    // "glow stick" has no rule keyword (rules -> ClickText) but matches the ML example.
    private val mlExamples = mapOf(IntentLabel.FLASHLIGHT to listOf("glow stick"))

    @Test
    fun confidentMl_overridesRules() {
        val parser = CompositeParser(MlIntentParser(FakeEmbedder(), mlExamples), RuleBasedParser())
        assertTrue(parser.parse("glow stick") is AppIntent.Flashlight)
    }

    @Test
    fun lowConfidenceMl_fallsBackToRules() {
        val parser = CompositeParser(MlIntentParser(FakeEmbedder(), mlExamples), RuleBasedParser())
        // Shares no words with the ML example -> below threshold -> rules handle it.
        assertTrue(parser.parse("what's my battery level") is AppIntent.Battery)
    }

    @Test
    fun noEmbedder_usesRules() {
        val parser = CompositeParser(ml = null, rules = RuleBasedParser())
        assertTrue(parser.parse("turn on the flashlight") is AppIntent.Flashlight)
        assertTrue(parser.parse("frobnicate") is AppIntent.ClickText)
    }

    @Test
    fun notReadyEmbedder_usesRules() {
        val parser = CompositeParser(MlIntentParser(FakeEmbedder(isReady = false), mlExamples), RuleBasedParser())
        assertTrue(parser.parse("what's my battery level") is AppIntent.Battery)
    }
}
