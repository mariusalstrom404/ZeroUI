package com.example.myapplication.automation

/**
 * Scores how well a node's text/description matches a target string.
 *
 * The old matcher accepted the first node whose text merely *contained* the target, so
 * "Cart" could hit "Add to Cart" and "Burger" could hit "Cheese Burger Combo" depending on
 * tree order. Scoring lets the finder pick the best candidate on screen instead, and the
 * punctuation-insensitive tiers keep "MidOnalds" (speech) matching "MidOnald’s" (UI).
 *
 * Real screens rarely say exactly what the user said, so below the phrase tiers there is a
 * graded word-level tier ([WORDS_MIN]..[WORDS_MAX]): every word the user said must appear in the
 * candidate, but other words may sit in between ("Hawaiian Personal Pizza Combo" for
 * "Hawaiian Pizza", "Add 1 to order" for "Add to order"), the order may differ, and single
 * words may be near-misses ("Pizzas", "Hawaii", a one-letter typo). Tighter matches score higher,
 * so an exact item on screen still beats a loose one.
 */
object TextMatch {
    const val NONE = 0
    /** Target appears inside the candidate once spaces/punctuation are removed. */
    const val CONTAINS_LOOSE = 20
    /** Lowest score of the word-level tier (every target word found, but loosely). */
    const val WORDS_MIN = 21
    /** Highest score of the word-level tier (all words exact, in order, nothing extra). */
    const val WORDS_MAX = 39
    /** Target appears inside the candidate as whole word(s). */
    const val CONTAINS_WORDS = 40
    /** Candidate starts with the target as whole word(s), e.g. "Cart (2)" for "Cart". */
    const val PREFIX = 60
    /** Equal once spaces/punctuation are removed, e.g. "Top-up" vs "top up". */
    const val EXACT_LOOSE = 80
    const val EXACT = 100

    /** Loose substring matching on very short targets ("hi" in "shipping") is almost always wrong. */
    private const val MIN_LOOSE_CONTAINS_LENGTH = 4

    /** Words shorter than this must match exactly (or as a plural): "bob" must never match "rob". */
    private const val MIN_FUZZY_WORD_LENGTH = 5

    /** Credit for a target word that only matched approximately (plural, prefix, typo). */
    private const val FUZZY_WORD_CREDIT = 0.75
    /** Penalty factor when the target words appear in a different order. */
    private const val OUT_OF_ORDER_FACTOR = 0.85

    fun normalize(s: String): String = s.lowercase()
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace(' ', ' ')
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
        val words = wordScore(tokens(c), tokens(t))
        if (words != NONE) return words
        if (tl.length >= MIN_LOOSE_CONTAINS_LENGTH && cl.contains(tl)) return CONTAINS_LOOSE
        return NONE
    }

    /**
     * Splits into words; apostrophes are dropped ("McDonald's" → "mcdonalds") and each CJK
     * character is its own token, since Chinese/Japanese text has no spaces between words.
     */
    internal fun tokens(s: String): List<String> {
        val out = mutableListOf<String>()
        val word = StringBuilder()
        fun flush() { if (word.isNotEmpty()) { out += word.toString(); word.clear() } }
        for (ch in s) {
            when {
                ch == '\'' -> {}
                isCjk(ch) -> { flush(); out += ch.toString() }
                ch.isLetterOrDigit() -> word.append(ch)
                else -> flush()
            }
        }
        flush()
        return out
    }

    private fun isCjk(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    /**
     * Word-level tier: every target word must be found in the candidate (exactly or approximately).
     * The score then rises with how exact the words were, how few extra words the candidate has,
     * and whether the order was kept.
     */
    private fun wordScore(cWords: List<String>, tWords: List<String>): Int {
        if (tWords.isEmpty() || cWords.isEmpty()) return NONE
        // A single short or CJK word is too ambiguous to match partially ("買" in "購買").
        if (tWords.size == 1 && (tWords[0].length < MIN_FUZZY_WORD_LENGTH || isCjk(tWords[0][0]))) return NONE

        val used = BooleanArray(cWords.size)
        val positions = IntArray(tWords.size)
        var credit = 0.0
        for ((i, tw) in tWords.withIndex()) {
            // Prefer an exact word, then the best approximate one, never reusing a candidate word.
            val exact = cWords.indices.firstOrNull { !used[it] && cWords[it] == tw }
            val index = exact ?: cWords.indices.firstOrNull { !used[it] && wordsSimilar(cWords[it], tw) }
                ?: return NONE
            used[index] = true
            positions[i] = index
            credit += if (exact != null) 1.0 else FUZZY_WORD_CREDIT
        }

        val quality = credit / tWords.size
        val precision = tWords.size.toDouble() / cWords.size
        val inOrder = positions.toList().zipWithNext().all { (a, b) -> a < b }
        val combined = quality * (0.3 + 0.7 * precision) * (if (inOrder) 1.0 else OUT_OF_ORDER_FACTOR)
        return WORDS_MIN + ((WORDS_MAX - WORDS_MIN) * combined).toInt()
    }

    /** Plurals, a shared stem ("hawaii"/"hawaiian") or a small typo on words long enough to be distinctive. */
    internal fun wordsSimilar(a: String, b: String): Boolean {
        if (a == b) return true
        if (isCjk(a[0]) || isCjk(b[0])) return false
        if (singular(a) == singular(b)) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (shorter === a) b else a
        if (shorter.length < MIN_FUZZY_WORD_LENGTH) return false
        if (longer.startsWith(shorter) && shorter.length * 10 >= longer.length * 7) return true
        val allowed = if (shorter.length >= 8) 2 else 1
        return editDistance(a, b, allowed) <= allowed
    }

    private fun singular(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes")) -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    /** Levenshtein distance, giving up early (returns [limit] + 1) once it must exceed [limit]. */
    private fun editDistance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > limit) return limit + 1
            prev = cur
        }
        return prev[b.length]
    }
}
