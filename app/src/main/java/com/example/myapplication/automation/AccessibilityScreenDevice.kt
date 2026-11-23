package com.example.myapplication.automation

import android.view.accessibility.AccessibilityNodeInfo
import com.example.myapplication.debug.Timing
import kotlinx.coroutines.delay

/**
 * [ScreenDevice] over the accessibility tree of the top-most app window (never ZeroUI's own).
 * Only text from the tree is read; password fields' contents are left out.
 *
 * Optimised to walk the accessibility tree once per snapshot, computing bounds, click targets,
 * tap labels, and loading-spinner presence in a single pass, and reusing the settled snapshot
 * directly instead of reading the tree twice.
 */
class AccessibilityScreenDevice(private val engine: AutomationEngine) : ScreenDevice {

    private companion object {
        const val MAX_NODES = 250
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
        val root = finder.roots().firstOrNull()
        val nodes = mutableListOf<ScreenNode>()
        val map = mutableMapOf<Int, AccessibilityNodeInfo>()
        val labelled = mutableListOf<Pair<String, android.graphics.Rect>>()
        var loading = false
        if (root != null) {
            for (node in finder.flatten(root)) {
                if (!node.isVisibleToUser) continue
                // Check loading state in the same single pass.
                if (!loading) {
                    val isSpinner = node.className?.toString() == PROGRESS_BAR && node.rangeInfo == null
                    val rawLabel = (node.text ?: node.contentDescription)?.toString()?.trim()
                    if (isSpinner || (rawLabel != null && LOADING_LABEL.containsMatchIn(rawLabel))) {
                        loading = true
                    }
                }
                if (nodes.size >= MAX_NODES) continue
                val bounds = finder.boundsOf(node)
                if (bounds.isEmpty) continue
                val label = labelOf(node)
                val interactive = node.isClickable || node.isEditable || node.isScrollable
                if (label == null && !interactive) continue
                // A button's text child repeats the button's label; listing it again only uses up
                // the node budget, which used to cut off whatever came last (often the bottom tabs).
                if (!interactive && labelled.any { (l, b) -> l == label && b.contains(bounds) }) continue
                if (interactive && label != null) labelled += label to bounds
                val clickTarget = if (node.isClickable) node else finder.clickTarget(node)
                val id = nodes.size
                nodes += ScreenNode(
                    id = id,
                    label = label,
                    viewId = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { label == null },
                    tappable = clickTarget != null,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    enabled = node.isEnabled,
                    password = node.isPassword,
                    tapLabels = finder.tapLabels(node, clickTarget)
                )
                map[id] = node
            }
        }
        live = map
        return SnapshotResult(Screen(root?.packageName?.toString(), nodes), loading)
    }

    private fun labelOf(node: AccessibilityNodeInfo): String? {
        val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) node.hintText else null
        // A password field's text is never read, only what it is for.
        val text = if (node.isPassword) null else node.text
        return listOf(text, node.contentDescription, hint)
            .firstOrNull { !it.isNullOrBlank() }
            ?.toString()?.trim()?.take(MAX_LABEL)
    }

    // Actions skip the engine's fixed waits: read() waits for exactly as long as the app needs.

    override suspend fun tap(id: Int): Boolean {
        val node = live[id]?.takeIf { it.refresh() } ?: return false
        return engine.click(node).also { acted() }
    }

    override suspend fun type(id: Int, text: String, submit: Boolean): Boolean {
        val node = live[id]?.takeIf { it.refresh() } ?: return false
        return engine.typeIntoNode(node, text, submit, settle = false).also { acted() }
    }

    override suspend fun scroll(down: Boolean): Boolean =
        engine.scroll(down, packageName = null, settle = false).also { acted() }

    override suspend fun back() {
        engine.back(settle = false)
        acted()
    }

    override suspend fun pause() = delay(PAUSE_MS)
}
