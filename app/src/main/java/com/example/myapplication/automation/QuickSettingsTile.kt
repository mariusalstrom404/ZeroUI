package com.example.myapplication.automation

/**
 * Reads Quick Settings tiles from their accessibility labels. Pure functions, so they're tested
 * without a device; [com.example.myapplication.system.SystemController] does the tapping.
 *
 * A tile reads like "Wi-Fi", "Wi-Fi, On" or "Bluetooth. Off." in its text or description, with
 * the state sometimes in a separate state description and, on most phones, as a checked switch.
 */
object QuickSettingsTile {

    private val ON_WORDS = setOf("on", "enabled", "active")
    private val OFF_WORDS = setOf("off", "disabled", "inactive")

    /** Lowercase words only: "Wi-Fi,On" → "wi fi on". */
    fun simplify(text: CharSequence?): String =
        text?.toString()?.lowercase()?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")?.trim() ?: ""

    /** The name in [names] that [label] starts with, or null when it isn't one of these tiles. */
    fun matchedName(label: CharSequence?, names: List<String>): String? {
        val simple = simplify(label)
        if (simple.isEmpty()) return null
        return names.firstOrNull { simple == it || simple.startsWith("$it ") }
    }

    /**
     * On (true), off (false) or unclear (null). A switch's [checked] state wins; otherwise the
     * words after the tile's [name] in [labels] decide, and conflicting words leave it unclear.
     */
    fun state(checked: Boolean?, name: String, vararg labels: CharSequence?): Boolean? {
        if (checked != null) return checked
        var on = false
        var off = false
        for (label in labels) {
            val words = simplify(label).removePrefix(name).trim().split(' ')
            if (words.any { it in ON_WORDS }) on = true
            if (words.any { it in OFF_WORDS }) off = true
        }
        return when {
            on && !off -> true
            off && !on -> false
            else -> null
        }
    }
}
