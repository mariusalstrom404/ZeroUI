package com.example.myapplication.automation

/**
 * Decides from a node's strings whether it is a way into search, and how likely.
 *
 * The old check was "contains `search`", taken on the first node in tree order, so a "Recent
 * searches" header, a "No search results" message or the word "research" could be tapped instead
 * of the search bar. This scores whole-word cues only and rejects history/clear labels.
 */
object SearchCue {

    const val NONE = 0

    /** Longer labels are sentences or list items that merely mention searching. */
    private const val MAX_LABEL_LENGTH = 40

    private val WORD = Regex("(^|[^\\p{L}])(search|searching)([^\\p{L}]|$)", RegexOption.IGNORE_CASE)
    private val CJK = listOf("搜尋", "搜索", "搜寻", "查找", "尋找", "寻找")
    /** Labels that are a search cue only when they are the whole label, e.g. an icon described "Find". */
    private val WHOLE_LABEL = setOf("find", "look up", "搜", "查詢", "查询", "検索")
    /** ids like search_bar, searchView, action_search, search_src_text, query_input. */
    private val ID = Regex("search|(^|[_/:])query", RegexOption.IGNORE_CASE)
    private val NOT_ENTRY = Regex(
        "recent|history|clear|delete|remove|no (search )?results|try (a )?different|trending|popular|" +
            "最近|紀錄|记录|記錄|歷史|历史|清除|刪除|删除|熱門|热门",
        RegexOption.IGNORE_CASE
    )

    /** Score of a node's own strings as a search cue; [NONE] when it isn't one. */
    fun score(text: CharSequence?, description: CharSequence?, hint: CharSequence?, viewId: String?): Int {
        val labels = listOfNotNull(text, description, hint).map { it.toString().trim() }.filter { it.isNotEmpty() }
        if (labels.any { NOT_ENTRY.containsMatchIn(it) }) return NONE

        var best = NONE
        for (label in labels) {
            val s = when {
                label.lowercase() in setOf("search", "搜尋", "搜索", "搜寻") -> 30
                label.length > MAX_LABEL_LENGTH -> NONE
                // "Search", "Search in Drive", "Search for restaurants"
                label.startsWith("search", ignoreCase = true) && WORD.containsMatchIn(label) -> 25
                WORD.containsMatchIn(label) || CJK.any { label.contains(it) } -> 20
                label.lowercase() in WHOLE_LABEL -> 20
                else -> NONE
            }
            best = maxOf(best, s)
        }
        val id = viewId?.substringAfterLast('/')
        if (id != null && ID.containsMatchIn(id) && !NOT_ENTRY.containsMatchIn(id)) best = maxOf(best, 15) + 5
        return best
    }
}
