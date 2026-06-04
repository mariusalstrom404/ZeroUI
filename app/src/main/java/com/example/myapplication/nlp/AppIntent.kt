package com.example.myapplication.nlp

/**
 * Structured representation of a single user command, produced by an [IntentParser].
 *
 * This is the single source of truth shared by the conversational UI
 * (which decides how to respond / prompt) and the accessibility service
 * (which executes the action). Slot filling (amounts, recipients, app names,
 * UUIDs) is done by [EntityExtractors] and carried on these intents.
 *
 * [requiresConfirmation] marks high-risk actions (money movement, checkout) that
 * the UI must verbally confirm before dispatching for execution.
 */
sealed class AppIntent(val requiresConfirmation: Boolean = false) {

    // --- System ---
    data object Battery : AppIntent()
    data class Flashlight(val enable: Boolean) : AppIntent()
    data class Volume(val change: Change, val level: Int? = null) : AppIntent()
    data class Brightness(val change: Change, val level: Int? = null) : AppIntent()

    // --- Navigation / apps ---
    data class Navigate(val destination: String?, val mode: String? = null) : AppIntent()
    data class OpenApp(val name: String) : AppIntent()

    // --- MockFinance ---
    data object CheckBalance : AppIntent()
    data class Transfer(val amount: Int?, val recipient: String?) : AppIntent(requiresConfirmation = true)
    data object TransactionHistory : AppIntent()
    data object TopUp : AppIntent()

    // --- FoodGorilla ---
    data class FoodSearch(val query: String) : AppIntent()
    data class FoodOrder(val itemId: Int?, val query: String?) : AppIntent(requiresConfirmation = true)
    data object FoodCart : AppIntent()
    data object FoodCheckout : AppIntent(requiresConfirmation = true)
    data class FoodMenu(val restaurant: String) : AppIntent()

    // --- Throats ---
    data class ThroatsPost(val content: String) : AppIntent()
    data class ThroatsRepost(val postId: String?) : AppIntent()
    data class ThroatsComment(val postId: String?, val content: String) : AppIntent()

    // --- Fallbacks ---
    /** No specific intent matched; [text] is the raw utterance for a best-effort click. */
    data class ClickText(val text: String) : AppIntent()
    data object Unknown : AppIntent()

    enum class Change { UP, DOWN, SET }
}
