package com.example.myapplication.automation

/**
 * Describes the node to find. Fields are tried as fallbacks in priority order
 * (testTag/resourceId, then contentDescription, then text) — the first tier that matches
 * anything on screen wins, and within a tier the best-scoring node wins (see [TextMatch]).
 */
data class Selector(
    val testTag: String? = null,
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,
    val className: String? = null,
    val classNameExclude: String? = null,
    val index: Int? = null,
    /** Only accept whole-string matches (ignoring case/spacing/punctuation), never substrings. */
    val exact: Boolean = false
) {
    /** Human-readable name for status messages, e.g. “Add to Cart” or “search bar”. */
    fun label(): String =
        text ?: contentDescription ?: (testTag ?: resourceId)?.replace('_', ' ') ?: "element"

    companion object {
        fun fromString(selector: String): Selector {
            return when {
                selector.startsWith("testTag:") -> Selector(testTag = selector.substringAfter("testTag:"))
                selector.startsWith("description:") -> Selector(contentDescription = selector.substringAfter("description:"))
                selector.startsWith("id:") -> Selector(resourceId = selector.substringAfter("id:"))
                selector.startsWith("text:") -> Selector(text = selector.substringAfter("text:"))
                else -> Selector(text = selector) // Default to text
            }
        }
    }
}
