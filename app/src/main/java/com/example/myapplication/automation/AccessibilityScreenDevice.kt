package com.example.myapplication.automation

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * [ScreenDevice] over the accessibility tree of the top-most app window (never ZeroUI's own).
 * Only text from the tree is read; password fields' contents are left out.
 */
class AccessibilityScreenDevice(private val engine: AutomationEngine) : ScreenDevice {

    private companion object {
        const val MAX_NODES = 250
        const val MAX_LABEL = 200
        const val SETTLE_POLL_MS = 500L
        const val SETTLE_TIMEOUT_MS = 4000L
        const val PAUSE_MS = 2000L
    }

    private var live: Map<Int, AccessibilityNodeInfo> = emptyMap()

    override suspend fun read(): Screen {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        var screen = snapshot()
        while (System.currentTimeMillis() < deadline) {
            delay(SETTLE_POLL_MS)
            val next = snapshot()
            val settled = next.nodes.isNotEmpty() && next.fingerprint == screen.fingerprint
            screen = next
            if (settled) break
        }
        return screen
    }

    private fun snapshot(): Screen {
        val finder = engine.finder
        val root = finder.roots().firstOrNull()
        val nodes = mutableListOf<ScreenNode>()
        val map = mutableMapOf<Int, AccessibilityNodeInfo>()
        val labelled = mutableListOf<Pair<String, android.graphics.Rect>>()
        if (root != null) {
            for (node in finder.flatten(root)) {
                if (nodes.size >= MAX_NODES) break
                if (!node.isVisibleToUser || finder.boundsOf(node).isEmpty) continue
                val label = labelOf(node)
                val interactive = node.isClickable || node.isEditable || node.isScrollable
                if (label == null && !interactive) continue
                // A button's text child repeats the button's label; listing it again only uses up
                // the node budget, which used to cut off whatever came last (often the bottom tabs).
                val bounds = finder.boundsOf(node)
                if (!interactive && labelled.any { (l, b) -> l == label && b.contains(bounds) }) continue
                if (interactive && label != null) labelled += label to bounds
                val id = nodes.size
                nodes += ScreenNode(
                    id = id,
                    label = label,
                    viewId = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { label == null },
                    tappable = node.isClickable || finder.clickTarget(node) != null,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    enabled = node.isEnabled,
                    password = node.isPassword,
                    tapLabels = finder.tapLabels(node)
                )
                map[id] = node
            }
        }
        live = map
        return Screen(root?.packageName?.toString(), nodes)
    }

    private fun labelOf(node: AccessibilityNodeInfo): String? {
        val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) node.hintText else null
        // A password field's text is never read, only what it is for.
        val text = if (node.isPassword) null else node.text
        return listOf(text, node.contentDescription, hint)
            .firstOrNull { !it.isNullOrBlank() }
            ?.toString()?.trim()?.take(MAX_LABEL)
    }

    override suspend fun tap(id: Int): Boolean {
        val node = live[id]?.takeIf { it.refresh() } ?: return false
        return engine.click(node)
    }

    override suspend fun type(id: Int, text: String, submit: Boolean): Boolean {
        val node = live[id]?.takeIf { it.refresh() } ?: return false
        return engine.typeIntoNode(node, text, submit)
    }

    override suspend fun scroll(down: Boolean): Boolean = engine.scroll(down, packageName = null)

    override suspend fun back() = engine.back()

    override suspend fun pause() = delay(PAUSE_MS)
}
