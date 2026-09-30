package com.example.myapplication.automation

/**
 * Scores how well a node's text/description matches a target string.
 *
 * The old matcher accepted the first node whose text merely *contained* the target, so
 * "Cart" could hit "Add to Cart" and "Burger" could hit "Cheese Burger Combo" depending on
 * tree order. Scoring lets the finder pick the best candidate on screen instead, and the
 * punctuation-insensitive tiers keep "MidOnalds" (speech) matching "MidOnald’s" (UI).
 */
object TextMatch {
    const val NONE = 0
    /** Target appears inside the candidate once spaces/punctuation are removed. */
    const val CONTAINS_LOOSE = 20
    /** Target appears inside the candidate as whole word(s). */
    const val CONTAINS_WORDS = 40
    /** Candidate starts with the target as whole word(s), e.g. "Cart (2)" for "Cart". */
    const val PREFIX = 60
    /** Equal once spaces/punctuation are removed, e.g. "Top-up" vs "top up". */
    const val EXACT_LOOSE = 80
    const val EXACT = 100

    /** Loose substring matching on very short targets ("hi" in "shipping") is almost always wrong. */
    private const val MIN_LOOSE_CONTAINS_LENGTH = 4

    fun normalize(s: String): String = s.lowercase()
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace(' ', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun loose(s: String): String = s.filter { it.isLetterOrDigit() }

    fun score(candidate: CharSequence?, target: String): Int {
        if (candidate.isNullOrBlank() || target.isBlank()) return NONE
        val c = normalize(candidate.toString())
        val t = normalize(target)
        if (c == t) return EXACT

        val cl = loose(c)
        val tl = loose(t)
        if (tl.isEmpty()) return NONE
        if (cl == tl) return EXACT_LOOSE

        val wholeWords = Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(t)}($|[^\\p{L}\\p{N}])")
        if (wholeWords.containsMatchIn(c)) {
            return if (c.startsWith(t)) PREFIX else CONTAINS_WORDS
        }
        if (tl.length >= MIN_LOOSE_CONTAINS_LENGTH && cl.contains(tl)) return CONTAINS_LOOSE
        return NONE
    }
}
