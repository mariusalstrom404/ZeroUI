package com.example.myapplication.nlp

/** A detail a command can't run without, which the assistant asks the user for. */
enum class FollowUpSlot { AMOUNT, RECIPIENT, ITEM, RESTAURANT, APP, QUERY, TEXT, CONTENT, DESTINATION, MODE, ALARM_TIME, DURATION }

/** The details [this] still needs. Checked here as well as by the LLM, so a slip can't skip one. */
fun AppIntent.missingSlots(): List<FollowUpSlot> = when (this) {
    is AppIntent.Transfer -> listOfNotNull(
        FollowUpSlot.AMOUNT.takeIf { amount == null },
        FollowUpSlot.RECIPIENT.takeIf { recipient.isNullOrBlank() }
    )
    is AppIntent.FoodSearch -> listOfNotNull(FollowUpSlot.QUERY.takeIf { query.isBlank() })
    is AppIntent.FoodOrder -> when {
        itemId != null -> emptyList()
        query.isNullOrBlank() -> listOf(FollowUpSlot.ITEM)
        // The service reads the query as "item from restaurant".
        !query.contains(" from ", ignoreCase = true) -> listOf(FollowUpSlot.RESTAURANT)
        else -> emptyList()
    }
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
    is AppIntent.SetAlarm -> listOfNotNull(
        FollowUpSlot.ALARM_TIME.takeIf { hour == null }
    )
    is AppIntent.SetTimer -> listOfNotNull(
        FollowUpSlot.DURATION.takeIf { durationSeconds == null || durationSeconds <= 0 }
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
    is AppIntent.FoodOrder -> when (slot) {
        FollowUpSlot.RESTAURANT -> copy(query = "$query from $value")
        else -> copy(query = value)
    }
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
    is AppIntent.SetAlarm -> when (slot) {
        FollowUpSlot.ALARM_TIME -> FollowUpRules.parseAlarmTime(value)?.let { (h, m) -> copy(hour = h, minute = m) }
        else -> null
    }
    is AppIntent.SetTimer -> when (slot) {
        FollowUpSlot.DURATION -> FollowUpRules.parseDurationSeconds(value)?.let { copy(durationSeconds = it) }
        else -> null
    }
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
    FollowUpSlot.ALARM_TIME -> "What time should I set the alarm for? (e.g. 7:30 AM or 早上7點半)"
    FollowUpSlot.DURATION -> "How long should I set the timer for? (e.g. 5 minutes or 10分鐘)"
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
        val text = answer.trim().trimEnd('.', '!', '?', '。', '！', '？')
        if (text.isEmpty()) return Reading.Unclear
        if (CANCEL.containsMatchIn(text)) return Reading.Cancel
        return when (slot) {
            // Only exactly one number counts: "50 or 60" needs a closer read.
            FollowUpSlot.AMOUNT -> DIGITS.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
                ?.let { Reading.Value(it.toString()) } ?: Reading.Unclear
            FollowUpSlot.MODE -> travelMode(text)?.let { Reading.Value(it) } ?: Reading.Unclear
            FollowUpSlot.ALARM_TIME -> parseAlarmTime(text)?.let { (h, m) ->
                Reading.Value(String.format(java.util.Locale.ROOT, "%02d:%02d", h, m))
            } ?: Reading.Unclear
            FollowUpSlot.DURATION -> parseDurationSeconds(text)?.let { Reading.Value("${it}s") } ?: Reading.Unclear
            // Free text is kept word for word.
            FollowUpSlot.QUERY, FollowUpSlot.TEXT, FollowUpSlot.CONTENT -> Reading.Value(text)
            else -> name(text)
        }
    }

    /**
     * Parses a clock time in Chinese or English into 24-hour `(hour, minute)`.
     * Examples: "7:30", "7:30 pm", "8am", "早上7點半", "下午三點十五分", "晚上8點", "20點30分".
     */
    fun parseAlarmTime(raw: String): Pair<Int, Int>? {
        val normalized = normalizeChineseNumbers(raw).lowercase(java.util.Locale.ROOT)
        val hasPm = Regex("\\b(?:pm|p\\.m\\.)\\b|下午|傍晚|晚上|晚間|今晚").containsMatchIn(normalized)
        val hasAm = Regex("\\b(?:am|a\\.m\\.)\\b|早上|上午|清晨|凌晨|明早").containsMatchIn(normalized)
        val isMidnight = normalized.contains("凌晨")

        // 1) HH:MM (optional am/pm)
        val colonMatch = Regex("(?<!\\d)(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})(?!\\d)").find(normalized)
        if (colonMatch != null) {
            val h = colonMatch.groupValues[1].toIntOrNull() ?: return null
            val m = colonMatch.groupValues[2].toIntOrNull() ?: return null
            return adjustAmPm(h, m, hasAm, hasPm, isMidnight)
        }

        // 2) Chinese: "7點半", "7點15分", "7點", "8時30分"
        val zhMatch = Regex("(\\d{1,2})\\s*[點点時时]\\s*(半|(\\d{1,2})\\s*分?)?").find(normalized)
        if (zhMatch != null) {
            val h = zhMatch.groupValues[1].toIntOrNull() ?: return null
            val tail = zhMatch.groupValues[2]
            val m = when {
                tail == "半" -> 30
                zhMatch.groupValues[3].isNotEmpty() -> zhMatch.groupValues[3].toIntOrNull() ?: 0
                else -> 0
            }
            return adjustAmPm(h, m, hasAm, hasPm, isMidnight)
        }

        // 3) English hour with am/pm or "o'clock": "7 am", "8pm", "7 o'clock"
        val enMatch = Regex("(?<!\\d)(\\d{1,2})\\s*(?:o'?clock|(?:a\\.?m\\.?|p\\.?m\\.?))\\b").find(normalized)
        if (enMatch != null) {
            val h = enMatch.groupValues[1].toIntOrNull() ?: return null
            return adjustAmPm(h, 0, hasAm, hasPm, isMidnight)
        }

        return null
    }

    private fun adjustAmPm(hour: Int, minute: Int, hasAm: Boolean, hasPm: Boolean, isMidnight: Boolean): Pair<Int, Int>? {
        if (minute !in 0..59) return null
        val h24 = when {
            hasPm && hour in 1..11 -> hour + 12
            hasAm && hour == 12 -> 0
            isMidnight && hour == 12 -> 0
            else -> hour
        }
        return if (h24 in 0..23) h24 to minute else null
    }

    /**
     * Parses a countdown duration in Chinese or English into total seconds.
     * Examples: "5分鐘", "30秒", "1小時20分", "半小時", "5 minutes", "1 hour 30 mins", "90s".
     */
    fun parseDurationSeconds(raw: String): Int? {
        val normalized = normalizeChineseNumbers(raw).lowercase(java.util.Locale.ROOT)
        var total = 0
        var matched = false

        if (Regex("半(?:個)?小時|半小时|half\\s+an?\\s+hour").containsMatchIn(normalized)) {
            total += 1800
            matched = true
        }
        if (Regex("半分鐘|半分钟|half\\s+a\\s+minute").containsMatchIn(normalized)) {
            total += 30
            matched = true
        }

        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:小時|小时|個小時|个小时|hours?|hrs?|hr|h\\b)").findAll(normalized).forEach { m ->
            m.groupValues[1].toDoubleOrNull()?.let {
                total += (it * 3600).toInt()
                matched = true
            }
        }
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:分鐘|分钟|分|minutes?|mins?|min|m\\b)").findAll(normalized).forEach { m ->
            m.groupValues[1].toDoubleOrNull()?.let {
                total += (it * 60).toInt()
                matched = true
            }
        }
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:秒鐘|秒钟|秒|seconds?|secs?|sec|s\\b)").findAll(normalized).forEach { m ->
            m.groupValues[1].toDoubleOrNull()?.let {
                total += it.toInt()
                matched = true
            }
        }

        // Handle "1小時半" / "5分半"
        if (Regex("\\d+\\s*(?:個)?(?:小時|小时)半").containsMatchIn(normalized)) {
            total += 1800
            matched = true
        }
        if (Regex("\\d+\\s*分(?:鐘|钟)?半").containsMatchIn(normalized)) {
            total += 30
            matched = true
        }

        return if (matched && total in 1..86400) total else null
    }

    /** Converts simple Chinese numerals (0..99) such as "七點半", "十五分鐘", "兩小時" into Arabic digits. */
    private fun normalizeChineseNumbers(input: String): String {
        val digits = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '两' to 2,
            '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        return Regex("[零〇一二兩两三四五六七八九十]+").replace(input) { match ->
            val token = match.value
            val num = when {
                token == "十" -> 10
                token.startsWith("十") && token.length == 2 -> 10 + (digits[token[1]] ?: 0)
                token.endsWith("十") && token.length == 2 -> (digits[token[0]] ?: 0) * 10
                token.length == 3 && token[1] == '十' -> (digits[token[0]] ?: 0) * 10 + (digits[token[2]] ?: 0)
                token.length == 1 -> digits[token[0]]
                else -> null
            }
            num?.toString() ?: token
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
