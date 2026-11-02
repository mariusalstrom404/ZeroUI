package com.example.myapplication.nlp

/** A detail a command can't run without, which the assistant asks the user for. */
enum class FollowUpSlot { AMOUNT, RECIPIENT, ITEM, RESTAURANT, APP, QUERY, TEXT, CONTENT, DESTINATION, MODE }

/** The details [this] still needs. Checked here as well as by the LLM, so a slip can't skip one. */
fun AppIntent.missingSlots(): List<FollowUpSlot> = when (this) {
    is AppIntent.Transfer -> listOfNotNull(
        FollowUpSlot.AMOUNT.takeIf { amount == null },
        FollowUpSlot.RECIPIENT.takeIf { recipient.isNullOrBlank() }
    )
    is AppIntent.FoodSearch -> listOfNotNull(FollowUpSlot.QUERY.takeIf { query.isBlank() })
    is AppIntent.FoodOrder -> listOfNotNull(FollowUpSlot.ITEM.takeIf { itemId == null && query.isNullOrBlank() })
    is AppIntent.FoodMenu -> listOfNotNull(FollowUpSlot.RESTAURANT.takeIf { restaurant.isBlank() })
    is AppIntent.ThroatsPost -> listOfNotNull(FollowUpSlot.CONTENT.takeIf { content.isBlank() })
    is AppIntent.ThroatsComment -> listOfNotNull(FollowUpSlot.CONTENT.takeIf { content.isBlank() })
    is AppIntent.Navigate -> listOfNotNull(
        FollowUpSlot.DESTINATION.takeIf { destination.isNullOrBlank() },
        FollowUpSlot.MODE.takeIf { mode == null }
    )
    is AppIntent.OpenApp -> listOfNotNull(FollowUpSlot.APP.takeIf { name.isBlank() })
    is AppIntent.AppOrder -> listOfNotNull(
        FollowUpSlot.ITEM.takeIf { item.isBlank() },
        FollowUpSlot.RESTAURANT.takeIf { restaurant.isNullOrBlank() },
        FollowUpSlot.APP.takeIf { app.isBlank() }
    )
    is AppIntent.TypeText -> listOfNotNull(FollowUpSlot.TEXT.takeIf { text.isBlank() })
    is AppIntent.HotelSearch -> listOfNotNull(
        FollowUpSlot.DESTINATION.takeIf { !book && destination.isBlank() && hotel.isNullOrBlank() }
    )
    else -> emptyList()
}

/** [this] with [slot] set to [value], or null when [value] can't fill it (an amount that isn't a number). */
fun AppIntent.fill(slot: FollowUpSlot, value: String): AppIntent? = when (this) {
    is AppIntent.Transfer -> when (slot) {
        FollowUpSlot.AMOUNT -> value.toIntOrNull()?.takeIf { it > 0 }?.let { copy(amount = it) }
        FollowUpSlot.RECIPIENT -> copy(recipient = value)
        else -> null
    }
    is AppIntent.FoodSearch -> copy(query = value)
    is AppIntent.FoodOrder -> copy(query = value)
    is AppIntent.FoodMenu -> copy(restaurant = value)
    is AppIntent.ThroatsPost -> copy(content = value)
    is AppIntent.ThroatsComment -> copy(content = value)
    is AppIntent.Navigate -> when (slot) {
        FollowUpSlot.DESTINATION -> copy(destination = value)
        FollowUpSlot.MODE -> FollowUpRules.travelMode(value)?.let { copy(mode = it) }
        else -> null
    }
    is AppIntent.OpenApp -> copy(name = value)
    is AppIntent.AppOrder -> when (slot) {
        FollowUpSlot.ITEM -> copy(item = value)
        FollowUpSlot.RESTAURANT -> copy(restaurant = value)
        FollowUpSlot.APP -> copy(app = value)
        else -> null
    }
    is AppIntent.TypeText -> copy(text = value, fullText = value)
    is AppIntent.HotelSearch -> copy(destination = value)
    else -> null
}

/** Used when the LLM didn't word a question for [slot]. */
fun FollowUpSlot.defaultQuestion(): String = when (this) {
    FollowUpSlot.AMOUNT -> "How much should I transfer?"
    FollowUpSlot.RECIPIENT -> "Who should I send it to?"
    FollowUpSlot.ITEM -> "What would you like to order?"
    FollowUpSlot.RESTAURANT -> "Which restaurant?"
    FollowUpSlot.APP -> "Which app?"
    FollowUpSlot.QUERY -> "What should I search for?"
    FollowUpSlot.TEXT -> "What should I type?"
    FollowUpSlot.CONTENT -> "What should it say?"
    FollowUpSlot.DESTINATION -> "Where would you like to go?"
    FollowUpSlot.MODE -> "How would you like to get there? Bus, walking, or driving?"
}

/** The local rules that read a follow-up answer first; the LLM only sees what these can't. */
object FollowUpRules {

    sealed class Reading {
        data class Value(val value: String) : Reading()
        data object Cancel : Reading()
        data object Unclear : Reading()
    }

    enum class Confirmation { YES, NO, UNCLEAR }

    val TRAVEL_MODES = setOf("bus", "walking", "driving")

    private const val MAX_NAME_WORDS = 6

    private val CANCEL = Regex("^(?:cancel|never ?mind|forget it|stop)\\b|^(?:取消|算了)", RegexOption.IGNORE_CASE)
    // Checked before YES: "no, I'm not sure" must not pass because it contains "sure".
    private val NO = Regex(
        "\\b(?:no|nope|nah|not|don'?t|do not|cancel|stop|wait|never ?mind)\\b|不|取消|別|算了",
        RegexOption.IGNORE_CASE
    )
    private val YES = Regex(
        "\\b(?:yes|yeah|yep|yup|ok|okay|confirm|proceed|go ahead|do it|sure)\\b|確認|确认|好|是|對",
        RegexOption.IGNORE_CASE
    )
    private val UNSURE = Regex(
        "^(?:um+|uh+|hmm+|i don'?t know|dont know|no idea|not sure|whatever|anything|any|no|nope|what|huh)\\b",
        RegexOption.IGNORE_CASE
    )
    private val LEAD_IN = Regex(
        "^(?:(?:please\\s+)?(?:send it to|to|it'?s|it is|let'?s go with|go with|how about)\\s+)?(?:the\\s+)?",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_PLEASE = Regex("\\s+please$", RegexOption.IGNORE_CASE)
    private val DIGITS = Regex("^\\D*?(\\d+)\\D*$")

    fun confirmation(answer: String): Confirmation = when {
        NO.containsMatchIn(answer) -> Confirmation.NO
        YES.containsMatchIn(answer) -> Confirmation.YES
        else -> Confirmation.UNCLEAR
    }

    fun travelMode(answer: String): String? {
        val lower = answer.lowercase()
        return when {
            lower.contains("bus") || lower.contains("transit") || answer.contains("公車") -> "bus"
            lower.contains("walk") || answer.contains("走路") -> "walking"
            lower.contains("driv") || lower.contains("car") || answer.contains("開車") -> "driving"
            else -> null
        }
    }

    fun read(slot: FollowUpSlot, answer: String): Reading {
        val text = answer.trim().trimEnd('.', '!', '?')
        if (text.isEmpty()) return Reading.Unclear
        if (CANCEL.containsMatchIn(text)) return Reading.Cancel
        return when (slot) {
            // Only exactly one number counts: "50 or 60" needs a closer read.
            FollowUpSlot.AMOUNT -> DIGITS.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
                ?.let { Reading.Value(it.toString()) } ?: Reading.Unclear
            FollowUpSlot.MODE -> travelMode(text)?.let { Reading.Value(it) } ?: Reading.Unclear
            // Free text is kept word for word.
            FollowUpSlot.QUERY, FollowUpSlot.TEXT, FollowUpSlot.CONTENT -> Reading.Value(text)
            else -> name(text)
        }
    }

    /** A short answer is taken as the name; a long or hesitant one is left for the LLM. */
    private fun name(text: String): Reading {
        if (UNSURE.containsMatchIn(text)) return Reading.Unclear
        val cleaned = text.replace(LEAD_IN, "").replace(TRAILING_PLEASE, "").trim()
        return if (cleaned.isEmpty() || isLong(cleaned)) Reading.Unclear else Reading.Value(cleaned)
    }

    fun isLong(text: String): Boolean = text.trim().split(Regex("\\s+")).size > MAX_NAME_WORDS
}
