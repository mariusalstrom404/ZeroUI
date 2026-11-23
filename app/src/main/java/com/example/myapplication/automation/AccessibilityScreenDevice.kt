package com.example.myapplication.automation

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.example.myapplication.debug.Timing
import kotlinx.coroutines.delay

/**
 * [ScreenDevice] over the accessibility tree of the top-most app window (never ZeroUI's own).
 * Only text from the tree is read; password fields' contents are left out.
 *
 * Enriches each [ScreenNode] with semantic state (checked, selected, role, region, hint,
 * scroll directions), popup-overlay detection, scroll boundary summaries, and automatic
 * stale-node recovery when a view recycles between LLM decision and execution.
 */
class AccessibilityScreenDevice(private val engine: AutomationEngine) : ScreenDevice {

    private companion object {
        const val MAX_NODES = 180
        const val MAX_SCAN_NODES = 650
        const val MAX_LABEL = 200
        const val PROGRESS_BAR = "android.widget.ProgressBar"
        val LOADING_LABEL = Regex("^(loading|please wait|載入中|加載中|正在載入|正在加载)", RegexOption.IGNORE_CASE)
        /** How often the wait checks for quiet; cheap, since it only compares timestamps. */
        const val POLL_MS = 45L
        /** No accessibility event for this long after the last action means the app is done. */
        const val QUIET_MS = 250L
        /**
         * How often the tree's text is compared while events keep coming. An animated banner never
         * stops sending events, but its text doesn't change, so two equal reads also count as done.
         */
        const val TEXT_CHECK_MS = 250L
        const val SETTLE_TIMEOUT_MS = 3500L
        /** How much longer an unchanged screen is given to start reacting to the action. */
        const val UNCHANGED_GRACE_MS = 450L
        const val PAUSE_MS = 1500L
    }

    private data class SnapshotResult(val screen: Screen, val loading: Boolean)

    private var live: Map<Int, AccessibilityNodeInfo> = emptyMap()
    private var liveMeta: Map<Int, ScreenNode> = emptyMap()
    private var lastAppPackage: String? = null

    /** When the last tap, typing, scroll or back finished, so a read waits for its effect. */
    private var lastActionAt = 0L

    /**
     * Waits until the app has gone quiet, then returns the settled [Screen] without re-walking
     * the tree unnecessarily.
     */
    override suspend fun read(previous: Screen?): Screen {
        val start = Timing.now()
        var (settled, snap) = waitForQuiet(start)
        var screen = snap?.screen ?: snapshot().screen
        var waitedLonger = false
        if (previous != null && screen.fingerprint == previous.fingerprint) {
            // A slow app may not have started reacting yet. Give it a moment to send an event.
            val mark = engine.lastScreenChangeAt
            val graceEnd = Timing.now() + UNCHANGED_GRACE_MS
            while (Timing.now() < graceEnd && engine.lastScreenChangeAt == mark) {
                engine.awaitScreenChangeOrDelay(POLL_MS)
            }
            if (engine.lastScreenChangeAt != mark) {
                waitedLonger = true
                val second = waitForQuiet(Timing.now())
                settled = second.first
                screen = second.second?.screen ?: snapshot().screen
            }
        }
        Timing.log(
            "Screen read: ${Timing.now() - start} ms, ${screen.nodes.size} elements" +
                (if (waitedLonger) ", app reacted late" else "") +
                if (settled) "" else " (still changing at the time limit)"
        )
        return screen
    }

    /** Returns `(settled, latestSnapshot)`. Reuses the snapshot taken during stability checking. */
    private suspend fun waitForQuiet(start: Long): Pair<Boolean, SnapshotResult?> {
        val deadline = start + SETTLE_TIMEOUT_MS
        var lastText: String? = null
        var lastTextAt = 0L
        var lastSnap: SnapshotResult? = null
        while (true) {
            val now = Timing.now()
            if (now >= deadline) return false to lastSnap
            val quietFor = now - maxOf(lastActionAt, engine.lastScreenChangeAt)
            var quiet = quietFor >= QUIET_MS
            var freshSnap: SnapshotResult? = null
            if (!quiet && now - lastActionAt >= TEXT_CHECK_MS && now - lastTextAt >= TEXT_CHECK_MS) {
                val snap = snapshot()
                freshSnap = snap
                val text = snap.screen.takeIf { it.nodes.isNotEmpty() }?.fingerprint
                quiet = text != null && text == lastText
                lastText = text
                lastTextAt = now
                lastSnap = snap
            }
            if (quiet) {
                val snap = freshSnap ?: snapshot().also { lastSnap = it }
                if (!snap.loading) return true to snap
            }
            engine.awaitScreenChangeOrDelay(POLL_MS)
        }
    }

    private fun acted() {
        lastActionAt = Timing.now()
    }

    private fun snapshot(): SnapshotResult {
        val finder = engine.finder
        val roots = finder.roots()
        val root = roots.firstOrNull()
        val appPkg = root?.packageName?.toString()
        // Multiple application windows belonging to the same app usually means a Dialog or BottomSheet is on top.
        val isPopupOverlay = appPkg != null && roots.count { it.packageName?.toString() == appPkg } > 1
        val dm = finder.service.resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1)
        val screenH = dm.heightPixels.coerceAtLeast(1)

        val candidates = mutableListOf<ScreenNodePruner.Candidate<AccessibilityNodeInfo>>()
        val labelled = mutableListOf<Pair<String, Rect>>()
        var loading = false
        var mainScrollArea = 0L
        var mainScrollDirs: List<String> = emptyList()
        var scanned = 0

        if (root != null) {
            for (node in finder.flatten(root)) {
                if (!node.isVisibleToUser) continue
                if (!loading) {
                    val isSpinner = node.className?.toString() == PROGRESS_BAR && node.rangeInfo == null
                    val rawLabel = (node.text ?: node.contentDescription)?.toString()?.trim()
                    if (isSpinner || (rawLabel != null && LOADING_LABEL.containsMatchIn(rawLabel))) {
                        loading = true
                    }
                }
                if (++scanned > MAX_SCAN_NODES) continue
                val bounds = finder.boundsOf(node)
                if (bounds.isEmpty) continue

                val dirs = if (node.isScrollable) scrollDirectionsOf(node) else emptyList()
                if (node.isScrollable) {
                    val area = bounds.width().toLong() * bounds.height()
                    if (area > mainScrollArea) {
                        mainScrollArea = area
                        mainScrollDirs = dirs
                    }
                }

                val label = labelOf(node)
                val hint = hintOf(node)
                val interactive = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
                if (label == null && !interactive) continue
                if (!interactive && labelled.any { (l, b) -> l == label && b.contains(bounds) }) continue
                if (interactive && label != null) labelled += label to bounds

                val clickTarget = if (node.isClickable) node else finder.clickTarget(node)
                val role = roleOf(node)
                val region = regionOf(bounds, screenW, screenH, iconOnly = label == null, role = role)
                val screenNode = ScreenNode(
                    id = candidates.size,
                    label = label,
                    viewId = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { label == null || node.isEditable },
                    tappable = clickTarget != null,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    enabled = node.isEnabled,
                    password = node.isPassword,
                    tapLabels = finder.tapLabels(node, clickTarget),
                    checked = if (node.isCheckable) node.isChecked else null,
                    selected = node.isSelected,
                    role = role,
                    region = region,
                    hint = if (node.isEditable) hint else null,
                    scrollDirs = dirs
                )
                candidates += ScreenNodePruner.Candidate(
                    order = candidates.size,
                    node = screenNode,
                    interactive = interactive || clickTarget != null,
                    payload = node
                )
            }
        }
        val selected = ScreenNodePruner.select(candidates, MAX_NODES)
        val nodes = ArrayList<ScreenNode>(selected.size)
        val map = HashMap<Int, AccessibilityNodeInfo>(selected.size)
        val metaMap = HashMap<Int, ScreenNode>(selected.size)
        selected.forEachIndexed { index, item ->
            val renumbered = item.node.copy(id = index)
            nodes += renumbered
            map[index] = item.payload
            metaMap[index] = renumbered
        }
        live = map
        liveMeta = metaMap
        lastAppPackage = appPkg

        val scrollSummary = when {
            mainScrollArea == 0L -> null
            "down" in mainScrollDirs && "up" in mainScrollDirs -> "can_scroll_down, can_scroll_up"
            "down" in mainScrollDirs -> "can_scroll_down"
            "up" in mainScrollDirs -> "at_bottom, can_scroll_up"
            else -> null
        }
        return SnapshotResult(Screen(appPkg, nodes, isPopupOverlay = isPopupOverlay, scrollSummary = scrollSummary), loading)
    }

    private fun labelOf(node: AccessibilityNodeInfo): String? {
        val hint = hintOf(node)
        // A password field's text is never read, only what it is for.
        val text = if (node.isPassword) null else node.text
        return listOf(text, node.contentDescription, hint)
            .firstOrNull { !it.isNullOrBlank() }
            ?.toString()?.trim()?.take(MAX_LABEL)
    }

    private fun hintOf(node: AccessibilityNodeInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL)
        } else {
            null
        }

    private fun roleOf(node: AccessibilityNodeInfo): String? {
        val cls = node.className?.toString()?.lowercase() ?: return null
        return when {
            "switch" in cls || "togglebutton" in cls -> "switch"
            "checkbox" in cls -> "checkbox"
            "radiobutton" in cls -> "radio"
            "tab" in cls -> "tab"
            else -> null
        }
    }

    /** Coarse region hint emitted only for icon-only buttons or top/bottom navigation controls to save tokens. */
    private fun regionOf(bounds: Rect, screenW: Int, screenH: Int, iconOnly: Boolean, role: String?): String? {
        val cx = bounds.centerX().toFloat() / screenW
        val cy = bounds.centerY().toFloat() / screenH
        return when {
            cy <= 0.14f && cx <= 0.22f && iconOnly -> "top-left"
            cy <= 0.14f && cx >= 0.78f && iconOnly -> "top-right"
            cy >= 0.88f && (iconOnly || role == "tab") -> "bottom-bar"
            else -> null
        }
    }

    private fun scrollDirectionsOf(node: AccessibilityNodeInfo): List<String> {
        val ids = node.actionList.map { it.id }.toSet()
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in ids) out += "down"
            if (AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id in ids) out += "up"
            if (AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id in ids) out += "right"
            if (AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id in ids) out += "left"
        }
        if (out.isEmpty()) {
            if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids) out += "down"
            if (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids) out += "up"
        }
        return out
    }

    /**
     * Resolves a live node by [id]. If the view was recycled/recomposed during the LLM round-trip,
     * immediately re-locates it by its original [ScreenNode.viewId] or [ScreenNode.label].
     */
    private fun resolveNode(id: Int, editable: Boolean = false): AccessibilityNodeInfo? {
        live[id]?.takeIf { it.refresh() }?.let { return it }
        val meta = liveMeta[id] ?: return null
        if (meta.viewId == null && meta.label == null) return null
        val selector = Selector(resourceId = meta.viewId, text = meta.label, exact = meta.label != null)
        return if (editable) {
            engine.finder.findEditable(selector, lastAppPackage)
        } else {
            engine.finder.find(selector, lastAppPackage)
        }
    }

    // Actions skip the engine's fixed waits: read() waits for exactly as long as the app needs.

    override suspend fun tap(id: Int): Boolean {
        val node = resolveNode(id) ?: return false
        return engine.click(node).also { acted() }
    }

    override suspend fun longPress(id: Int): Boolean {
        val node = resolveNode(id) ?: return false
        return engine.longClick(node).also { acted() }
    }

    override suspend fun type(id: Int, text: String, submit: Boolean): Boolean {
        val node = resolveNode(id, editable = true) ?: return false
        return engine.typeIntoNode(node, text, submit, settle = false).also { acted() }
    }

    override suspend fun scroll(down: Boolean): Boolean =
        engine.scroll(down, packageName = null, settle = false).also { acted() }

    override suspend fun scroll(down: Boolean, nodeId: Int?): Boolean {
        val container = nodeId?.let { resolveNode(it) }
        return if (container != null) {
            engine.scroll(container, down, settle = false).also { acted() }
        } else {
            scroll(down)
        }
    }

    override suspend fun scrollHorizontal(left: Boolean, nodeId: Int?): Boolean {
        val container = nodeId?.let { resolveNode(it) }
        return engine.scrollHorizontal(left, container = container, packageName = null, settle = false).also { acted() }
    }

    override suspend fun back() {
        engine.back(settle = false)
        acted()
    }

    override suspend fun pause() = delay(PAUSE_MS)
}

/**
 * Interactive-first screen node selection that keeps all interactive controls (buttons, inputs,
 * switches, scrollables, bottom-bar tabs) before filling remaining capacity with unique static
 * text labels, while preserving original visual reading order.
 */
object ScreenNodePruner {
    data class Candidate<T>(
        val order: Int,
        val node: ScreenNode,
        val interactive: Boolean,
        val payload: T
    )

    fun <T> select(candidates: List<Candidate<T>>, maxNodes: Int): List<Candidate<T>> {
        if (candidates.isEmpty() || maxNodes <= 0) return emptyList()

        val interactive = mutableListOf<Candidate<T>>()
        val staticText = mutableListOf<Candidate<T>>()
        val seenStaticLabels = HashSet<String>()

        for (c in candidates) {
            if (c.interactive) {
                interactive += c
            } else {
                val norm = c.node.label?.trim()?.lowercase()
                if (norm != null && seenStaticLabels.add(norm)) {
                    staticText += c
                }
            }
        }

        val chosen = if (interactive.size >= maxNodes) {
            interactive.take(maxNodes)
        } else {
            val remaining = maxNodes - interactive.size
            (interactive + staticText.take(remaining)).sortedBy { it.order }
        }
        return chosen
    }
}
