package com.example.myapplication.automation

/** An app with a launcher icon: what the user sees on the home screen. */
data class InstalledApp(val label: String, val packageName: String)

/**
 * Picks the installed app the user meant by a spoken name.
 *
 * Spoken names rarely equal the label: "Google Drive" is labelled "Drive", "whats app" is
 * "WhatsApp", "Spotify app" has a filler word, speech mishears "spottify", and on a Chinese phone
 * Maps is labelled "地圖". So both sides are compared with and without brand prefixes and filler
 * words through [TextMatch], with the package name as a last resort, and the best score wins
 * instead of the first label that happens to contain the name.
 */
object AppMatcher {

    /** Below this the match is a guess; launching the wrong app is worse than saying "not found". */
    const val MIN_SCORE = TextMatch.CONTAINS_LOOSE

    /** A match found only after dropping a brand or filler word ranks just below a direct one. */
    private const val STRIPPED_PENALTY = 2

    /** "maps" in com.google.android.apps.maps: below a label match, above a loose guess. */
    private const val PACKAGE_SCORE = 50

    private val FILLERS = setOf("app", "apps", "application", "the", "my", "應用", "應用程式", "应用", "应用程序", "程式", "軟件", "软件")
    private val BRANDS = setOf("google", "microsoft", "samsung", "apple", "meta", "facebook", "amazon", "adobe", "huawei", "xiaomi", "mi")
    private val GENERIC_SEGMENTS = setOf("com", "org", "net", "android", "app", "apps", "mobile", "client", "main", "lite", "global", "www")

    fun best(name: String, apps: List<InstalledApp>): InstalledApp? {
        val queries = variants(name)
        if (queries.isEmpty()) return null
        return apps
            .map { it to score(it, queries) }
            .filter { it.second >= MIN_SCORE }
            // Ties go to the label with the fewest extra characters ("Drive" over "Drive Backup").
            .maxWithOrNull(compareBy<Pair<InstalledApp, Int>> { it.second }.thenByDescending { it.first.label.length })
            ?.first
    }

    internal fun score(app: InstalledApp, queries: List<Pair<String, Boolean>>): Int {
        val labels = variants(app.label)
        var best = TextMatch.NONE
        for ((query, queryStripped) in queries) {
            for ((label, labelStripped) in labels) {
                val s = TextMatch.score(label, query) - if (queryStripped || labelStripped) STRIPPED_PENALTY else 0
                best = maxOf(best, s)
            }
            best = maxOf(best, packageScore(app.packageName, query))
        }
        return best
    }

    /**
     * The name as given, then without filler words, then also without a leading brand.
     * Each is paired with whether something was removed.
     */
    internal fun variants(name: String): List<Pair<String, Boolean>> {
        val words = TextMatch.tokens(TextMatch.normalize(name).replace(".com", " com"))
        val original = TextMatch.normalize(name)
        if (original.isEmpty()) return emptyList()
        val out = linkedMapOf(original to false)
        val noFiller = words.filter { it !in FILLERS }
        if (noFiller.isNotEmpty()) out.putIfAbsent(noFiller.joinToString(" "), true)
        val noBrand = noFiller.dropWhile { it in BRANDS }
        if (noBrand.isNotEmpty()) out.putIfAbsent(noBrand.joinToString(" "), true)
        // Chinese/English brand prefixes spoken without a space, e.g. "谷歌地圖".
        for (prefix in listOf("谷歌", "google")) {
            val joined = noFiller.joinToString("")
            if (joined.startsWith(prefix) && joined.length > prefix.length) out.putIfAbsent(joined.removePrefix(prefix), true)
        }
        return out.map { it.key to it.value }
    }

    /** A package segment equal to (or starting with) the spoken name, ignoring generic segments. */
    private fun packageScore(packageName: String, query: String): Int {
        val loose = query.filter { it.isLetterOrDigit() }
        if (loose.length < 4 || loose.any { it.code > 0x7f }) return TextMatch.NONE
        val segments = packageName.lowercase().split('.', '_').filter { it !in GENERIC_SEGMENTS }
        return when {
            segments.any { it == loose } -> PACKAGE_SCORE
            segments.any { it.startsWith(loose) } -> PACKAGE_SCORE - 5
            else -> TextMatch.NONE
        }
    }
}
