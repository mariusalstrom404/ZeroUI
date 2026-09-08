package com.example.myapplication.automation

data class Selector(
    val testTag: String? = null,
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,
    val className: String? = null,
    val classNameExclude: String? = null,
    val index: Int? = null
) {
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
