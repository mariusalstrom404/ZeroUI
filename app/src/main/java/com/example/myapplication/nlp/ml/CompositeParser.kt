package com.example.myapplication.nlp.ml

import com.example.myapplication.nlp.AppIntent
import com.example.myapplication.nlp.IntentParser
import com.example.myapplication.nlp.IntentSlots
import com.example.myapplication.nlp.RuleBasedParser

/**
 * Prefers ML classification and falls back to the rule-based parser.
 *
 * If the ML parser produces a match at or above [threshold], its label is used;
 * otherwise (low confidence, or no embedder available) the deterministic
 * [RuleBasedParser] handles the utterance. This guarantees the app behaves at least as
 * well as the rules even when no model is bundled — [ml] may be null.
 */
class CompositeParser(
    private val ml: MlIntentParser?,
    private val rules: RuleBasedParser = RuleBasedParser(),
    private val threshold: Float = 0.6f
) : IntentParser {

    override fun parse(utterance: String): AppIntent {
        val match = ml?.bestMatch(utterance)
        if (match != null && match.score >= threshold) {
            return IntentSlots.build(match.label, utterance.trim())
        }
        return rules.parse(utterance)
    }
}
