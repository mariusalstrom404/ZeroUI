package com.example.myapplication.nlp

/**
 * Turns a raw utterance into a structured [AppIntent].
 *
 * [RuleBasedParser] is the always-available baseline. [com.example.myapplication.nlp.ml.MlIntentParser]
 * adds paraphrase-tolerant classification, and [com.example.myapplication.nlp.ml.CompositeParser]
 * prefers ML and falls back to rules below a confidence threshold.
 */
interface IntentParser {
    fun parse(utterance: String): AppIntent
}

/**
 * Keyword/regex based parser. This is the single source of truth that replaced the
 * duplicated `when` blocks formerly in MainActivity and MyAccessibilityService.
 *
 * It only *classifies* the utterance into an [IntentLabel]; slot extraction is delegated
 * to [IntentSlots] so it is shared with the ML parser. Branch order is significant and
 * mirrors the original dispatch order so behavior is preserved.
 */
class RuleBasedParser : IntentParser {

    override fun parse(utterance: String): AppIntent {
        val command = utterance.trim()
        return IntentSlots.build(classify(command), command)
    }

    fun classify(utterance: String): IntentLabel {
        val lower = utterance.lowercase()

        fun isFoodGorilla() = lower.contains("foodgorilla") || lower.contains("food gorilla")

        return when {
            lower.contains("balance") -> IntentLabel.CHECK_BALANCE
            lower.contains("transfer") && lower.contains("to") -> IntentLabel.TRANSFER
            lower.contains("history") -> IntentLabel.HISTORY
            lower.contains("top up") || lower.contains("refill") || lower.contains("top-up") ->
                IntentLabel.TOP_UP

            isFoodGorilla() && (lower.contains("search") || lower.contains("find")) -> IntentLabel.FOOD_SEARCH
            isFoodGorilla() && (lower.contains("buy") || lower.contains("order")) -> IntentLabel.FOOD_ORDER
            isFoodGorilla() && lower.contains("cart") -> IntentLabel.FOOD_CART
            isFoodGorilla() && lower.contains("checkout") -> IntentLabel.FOOD_CHECKOUT
            isFoodGorilla() && lower.contains("menu") -> IntentLabel.FOOD_MENU

            lower.contains("post") && (lower.contains("throats") || lower.contains("throat")) ->
                IntentLabel.THROATS_POST
            lower.contains("repost") -> IntentLabel.THROATS_REPOST
            lower.contains("comment") || lower.contains("reply") -> IntentLabel.THROATS_COMMENT

            lower.contains("flashlight") || lower.contains("flash light") ||
                lower.contains("torch") || lower.contains("light") || lower.contains("dark") ->
                IntentLabel.FLASHLIGHT

            lower.contains("battery") || lower.contains("percentage") -> IntentLabel.BATTERY

            lower.contains("volume") || lower.contains("louder") || lower.contains("quieter") ->
                IntentLabel.VOLUME
            lower.contains("brightness") || lower.contains("brighter") ||
                lower.contains("dimmer") || lower.contains("dim") -> IntentLabel.BRIGHTNESS

            lower.contains("navigate") || lower.contains("take me to") -> IntentLabel.NAVIGATE
            lower.contains("open") || lower.contains("launch") -> IntentLabel.OPEN_APP

            else -> IntentLabel.CLICK_TEXT
        }
    }
}
