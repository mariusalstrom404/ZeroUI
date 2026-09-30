package com.example.myapplication.nlp

import java.util.regex.Pattern

/**
 * Slot-filling helpers shared by the UI and the accessibility service.
 *
 * These were previously duplicated/split across MainActivity (`extractEntity`)
 * and MyAccessibilityService (`extractNumber`, `extractTargetAfter`, `extractUuid`).
 * They are pure functions so they can be unit-tested directly.
 */
object EntityExtractors {

    private val UUID_PATTERN: Pattern =
        Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private val NUMBER_PATTERN = Regex("\\d+")

    /** First run of digits in [text] as an Int, or null. */
    fun number(text: String): Int? = NUMBER_PATTERN.find(text)?.value?.toIntOrNull()

    /** First UUID found in [text], or null. */
    fun uuid(text: String): String? {
        val m = UUID_PATTERN.matcher(text)
        return if (m.find()) m.group() else null
    }

    /**
     * Returns the substring after the first matching keyword in [keywords],
     * trimmed and with leading "me to "/"to " stripped. Returns "" if no keyword
     * matches (so callers can detect "needs more info").
     */
    fun targetAfter(full: String, keywords: List<String>): String {
        val lower = full.lowercase()
        for (kw in keywords) {
            val idx = lower.indexOf(kw)
            if (idx != -1) {
                return full.substring(idx + kw.length).trim()
                    .removePrefix("me to ").removePrefix("to ").trim()
            }
        }
        return ""
    }
}
