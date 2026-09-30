package com.example.myapplication.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Locates nodes in the accessibility trees of other apps' windows.
 *
 * Compared with a first-match DFS over `rootInActiveWindow`, this:
 * - searches every application window, top-most first, so a dialog/bottom sheet wins over the
 *   screen still present behind it, and the target is found even when the *active* window is
 *   ZeroUI's own floating dialog;
 * - never matches ZeroUI's own windows (its chat transcript repeats the very words being searched);
 * - only considers nodes that are visible on screen;
 * - ranks all candidates instead of taking the first substring hit.
 */
class NodeFinder(private val service: AccessibilityService) {

    companion object {
        private const val COMPOSE_TEST_TAG_KEY = "androidx.compose.ui.semantics.testTag"
        private const val MAX_DEPTH = 80
        private const val MAX_CLICK_ANCESTOR_DEPTH = 8
        /** A clickable ancestor bigger than this share of the screen is a container/scrim, not the button. */
        private const val MAX_CLICK_TARGET_SCREEN_SHARE = 0.6
        private val SEARCH_FIELD_TAGS = setOf("search_bar", "menu_search_bar")
        private val SEARCH_WORDS = listOf("search", "搜尋", "搜索", "搜寻")
    }

    /** Roots of the application windows to search, top-most first, excluding ZeroUI itself. */
    fun roots(packageName: String? = null): List<AccessibilityNodeInfo> {
        val own = service.packageName
        val result = mutableListOf<AccessibilityNodeInfo>()
        val windows = try { service.windows } catch (e: Exception) { emptyList<AccessibilityWindowInfo>() }
        for (window in windows.sortedByDescending { it.layer }) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (accepts(root, own, packageName)) result += root
        }
        if (result.isEmpty()) {
            service.rootInActiveWindow?.let { if (accepts(it, own, packageName)) result += it }
        }
        return result
    }

    private fun accepts(root: AccessibilityNodeInfo, own: String, packageName: String?): Boolean {
        val pkg = root.packageName?.toString()
        return pkg != own && (packageName == null || pkg == packageName)
    }

    /** All nodes under [root] in pre-order (document/reading order). */
    fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            out += node
            if (depth >= MAX_DEPTH) return
            for (i in 0 until node.childCount) {
                visit(node.getChild(i) ?: continue, depth + 1)
            }
        }
        visit(root, 0)
        return out
    }

    /**
     * Best node for [selector] in the top-most window that has any match.
     * With [includeOffscreen], nodes that exist in the tree but aren't visible are also accepted.
     */
    fun find(
        selector: Selector,
        packageName: String? = null,
        includeOffscreen: Boolean = false
    ): AccessibilityNodeInfo? {
        for (root in roots(packageName)) {
            val nodes = flatten(root).filter { isCandidate(it, selector, includeOffscreen) }
            val match = findById(nodes, selector)
                ?: selector.contentDescription?.let { target ->
                    bestTextMatch(nodes, selector.exact) { TextMatch.score(it.contentDescription, target) }
                }
                ?: selector.text?.let { target ->
                    bestTextMatch(nodes, selector.exact) {
                        maxOf(TextMatch.score(it.text, target), TextMatch.score(it.contentDescription, target))
                    }
                }
                ?: findSearchField(nodes, selector)
            if (match != null) return match
        }
        return null
    }

    private fun isCandidate(node: AccessibilityNodeInfo, selector: Selector, includeOffscreen: Boolean): Boolean {
        val cls = node.className?.toString()
        if (selector.classNameExclude != null && cls?.contains(selector.classNameExclude) == true) return false
        if (selector.className != null && cls?.contains(selector.className) != true) return false
        if (includeOffscreen) return true
        return node.isVisibleToUser && !boundsOf(node).isEmpty
    }

    private fun findById(nodes: List<AccessibilityNodeInfo>, selector: Selector): AccessibilityNodeInfo? {
        val id = selector.testTag ?: selector.resourceId ?: return null
        // Exact or ":id/<id>" only. A substring match made "search_bar" hit "menu_search_bar".
        nodes.firstOrNull {
            val actual = it.viewIdResourceName
            actual != null && (actual == id || actual.endsWith(":id/$id"))
        }?.let { return it }
        return nodes.firstOrNull { composeTestTag(it) == id }
    }

    /** Compose only reports testTag as a resource id with `testTagsAsResourceId`; otherwise it is extra data. */
    private fun composeTestTag(node: AccessibilityNodeInfo): String? {
        node.extras?.getCharSequence(COMPOSE_TEST_TAG_KEY)?.let { return it.toString() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            node.availableExtraData?.contains(COMPOSE_TEST_TAG_KEY) == true
        ) {
            node.refreshWithExtraData(COMPOSE_TEST_TAG_KEY, Bundle())
            return node.extras?.getCharSequence(COMPOSE_TEST_TAG_KEY)?.toString()
        }
        return null
    }

    private fun bestTextMatch(
        nodes: List<AccessibilityNodeInfo>,
        exact: Boolean,
        scoreOf: (AccessibilityNodeInfo) -> Int
    ): AccessibilityNodeInfo? {
        val minScore = if (exact) TextMatch.EXACT_LOOSE else TextMatch.CONTAINS_LOOSE
        val scored = nodes
            .map { it to scoreOf(it) }
            .filter { (node, score) -> score >= minScore && !isNearlyFullScreen(node) }
        val top = scored.maxOfOrNull { it.second } ?: return null
        val best = scored.filter { it.second == top }.map { it.first }
        // Among equally good matches prefer one that can actually be clicked; otherwise reading order.
        return best.firstOrNull { clickTarget(it) != null } ?: best.first()
    }

    private fun findSearchField(nodes: List<AccessibilityNodeInfo>, selector: Selector): AccessibilityNodeInfo? {
        if (selector.testTag !in SEARCH_FIELD_TAGS) return null
        val editable = nodes.filter { it.isEditable && it.className?.contains("EditText") == true }
        return editable.firstOrNull { field ->
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) field.hintText else null
            listOf(field.text, hint, field.contentDescription, field.viewIdResourceName)
                .any { it?.contains("Search", ignoreCase = true) == true } ||
                flatten(field).any { it.text?.contains("Search", ignoreCase = true) == true }
        }
    }

    /**
     * The field to type into for [selector]: the matched node (or its editable descendant), else
     * the field that currently has input focus. The focus fallback matters once a search bar has
     * been tapped: its placeholder ("Search for…") disappears or the tap opens a separate search
     * field, so the selector alone may no longer match anything editable.
     */
    fun findEditable(selector: Selector, packageName: String? = null): AccessibilityNodeInfo? {
        find(selector, packageName)?.let { editableTarget(it) }?.takeIf { it.isEditable }?.let { return it }
        return focusedEditable(packageName)
    }

    /** Visible editable fields of the top-most window that has any, search boxes first. */
    fun textFields(packageName: String? = null): List<AccessibilityNodeInfo> {
        for (root in roots(packageName)) {
            val fields = flatten(root).filter { it.isEditable && it.isVisibleToUser && !boundsOf(it).isEmpty }
            if (fields.isNotEmpty()) return fields.sortedByDescending { looksLikeSearch(it) }
        }
        return emptyList()
    }

    /** True when [field] or its placeholder/label says "search" (English or Chinese). */
    fun looksLikeSearch(field: AccessibilityNodeInfo): Boolean =
        mentionsSearch(field) || flatten(field).any { mentionsSearch(it) }

    /**
     * A tappable way into search: a search icon, button or the non-editable "fake" search bar
     * many real apps (Foodpanda, Uber Eats…) show on their home screen, which opens a separate
     * search screen with the real text field.
     */
    fun searchEntry(packageName: String? = null): AccessibilityNodeInfo? {
        for (root in roots(packageName)) {
            flatten(root)
                .firstOrNull { it.isVisibleToUser && !boundsOf(it).isEmpty && !isNearlyFullScreen(it) && mentionsSearch(it) }
                ?.let { return it }
        }
        return null
    }

    private fun mentionsSearch(node: AccessibilityNodeInfo): Boolean {
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText else null
        return listOf(node.text, hint, node.contentDescription, node.viewIdResourceName).any { value ->
            value != null && SEARCH_WORDS.any { value.contains(it, ignoreCase = true) }
        }
    }

    /** The editable field holding input focus in [packageName]'s windows, if any. */
    fun focusedEditable(packageName: String? = null): AccessibilityNodeInfo? =
        roots(packageName).firstNotNullOfOrNull { root ->
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        }

    /**
     * The node that should receive ACTION_CLICK for [node]: itself if clickable, else the nearest
     * clickable ancestor that is still plausibly "this button". Returns null when the only clickable
     * ancestor is a list, a full-screen container or a dialog scrim — clicking those hits the wrong
     * item, so the caller taps the node's own bounds instead.
     */
    fun clickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        val nodeBounds = boundsOf(node)
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < MAX_CLICK_ANCESTOR_DEPTH) {
            // Crossing a scrollable container means we've left the list item.
            if (parent.isScrollable) return null
            if (parent.isClickable) {
                val b = boundsOf(parent)
                val tooBig = b.width().toLong() * b.height() > screenArea() * MAX_CLICK_TARGET_SCREEN_SHARE
                return if (!tooBig && b.contains(nodeBounds.centerX(), nodeBounds.centerY())) parent else null
            }
            parent = parent.parent
            depth++
        }
        return null
    }

    /** The editable field for [node]: itself, or an editable descendant (Compose puts testTags on wrappers). */
    fun editableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        if (node.isEditable) node else flatten(node).firstOrNull { it.isEditable } ?: node

    /** Centre of the part of [node] that is inside its window, or null if none of it is. */
    fun visibleCenter(node: AccessibilityNodeInfo): Point? {
        val bounds = boundsOf(node)
        val windowBounds = Rect()
        node.window?.getBoundsInScreen(windowBounds)
        if (!windowBounds.isEmpty && !bounds.intersect(windowBounds)) return null
        if (bounds.isEmpty) return null
        return Point(bounds.centerX(), bounds.centerY())
    }

    /** The main vertical list of the top-most window that has one. */
    fun scrollContainer(packageName: String? = null): AccessibilityNodeInfo? {
        val minHeight = service.resources.displayMetrics.heightPixels * 0.25
        for (root in roots(packageName)) {
            val scrollables = flatten(root).filter { it.isScrollable && it.isVisibleToUser }
            if (scrollables.isEmpty()) continue
            // Horizontal carousels are short; the main list is tall. Take the biggest tall one.
            val tall = scrollables.filter { boundsOf(it).height() >= minHeight }
            return (tall.ifEmpty { scrollables }).maxByOrNull { boundsOf(it).let { b -> b.width().toLong() * b.height() } }
        }
        return null
    }

    fun boundsOf(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    private fun screenArea(): Long {
        val m = service.resources.displayMetrics
        return m.widthPixels.toLong() * m.heightPixels
    }

    private fun isNearlyFullScreen(node: AccessibilityNodeInfo): Boolean {
        val b = boundsOf(node)
        val m = service.resources.displayMetrics
        return b.width() > m.widthPixels * 0.9 && b.height() > m.heightPixels * 0.9
    }
}
