package com.example.myapplication.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AutomationEngine(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "AutomationEngine"
        private const val LOG = "ZeroUIAutomation"
        private const val DEFAULT_TIMEOUT = 10000L
        private const val POLL_INTERVAL = 400L
        private const val SCROLL_MAX_RETRIES = 8
        /**
         * How long a screen gets to populate before we start scrolling to look for an element.
         * Scrolling immediately used to push the target off screen while the screen was still loading.
         */
        private const val SCROLL_SETTLE_MS = 2000L
    }

    val finder = NodeFinder(service)

    /** Package of the app the running workflow drives; searches default to its windows only. */
    var targetPackage: String? = null
        private set

    private var hasDumpedTree = false

    /** Receives short, user-facing descriptions of what the engine is doing (for the status overlay). */
    var onProgress: ((String) -> Unit)? = null

    /** "Step 4 of 12" while [execute] runs, so progress messages say where in the workflow we are. */
    private var stepLabel: String? = null

    private fun report(message: String) {
        onProgress?.invoke(stepLabel?.let { "$it · $message" } ?: message)
    }

    suspend fun execute(steps: List<AutomationStep>): Boolean {
        hasDumpedTree = false
        try {
            return runSteps(steps)
        } finally {
            stepLabel = null
        }
    }

    private suspend fun runSteps(steps: List<AutomationStep>): Boolean {
        for ((index, step) in steps.withIndex()) {
            Log.d(TAG, "Executing step ${index + 1}/${steps.size}: ${step.type} - ${step.description ?: ""}")
            stepLabel = "Step ${index + 1} of ${steps.size}"
            val success = try {
                when (step.type) {
                    StepType.OPEN_APP -> {
                        hasDumpedTree = false
                        openApp(step.value ?: "")
                    }
                    StepType.FIND -> waitForElement(step.selector, step.timeoutMs) != null
                    StepType.CLICK -> clickElement(step.selector, step.timeoutMs)
                    StepType.INPUT_TEXT -> inputText(step.selector, step.value ?: "", step.timeoutMs)
                    StepType.SCROLL_DOWN -> scroll(true)
                    StepType.SCROLL_UP -> scroll(false)
                    StepType.WAIT_FOR -> waitForElement(step.selector, step.timeoutMs) != null
                    StepType.BACK -> {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        delay(500)
                        true
                    }
                    StepType.CUSTOM -> step.action?.invoke() ?: true
                }
            } catch (e: Exception) {
                Log.e(LOG, "Automation exception in step ${step.description}", e)
                false
            }

            if (!success && !step.optional) {
                Log.e(TAG, "Step failed: ${step.description}. Aborting workflow.")
                report("Failed: ${step.description ?: step.type.name.lowercase()}")
                return false
            }
            Log.d(TAG, "Step success: ${step.description}")
            delay(500) // Small stability delay between steps
        }
        return true
    }

    private fun openApp(packageName: String): Boolean {
        report("Opening app")
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            targetPackage = packageName
            true
        } else {
            false
        }
    }

    /**
     * Polls until [selector] is visible in [packageName]'s windows (any app but ZeroUI when null).
     * With [scrollIfNotFound], once the screen has had time to settle it scrolls the main list
     * forward, and back up again if the end is reached first.
     */
    suspend fun waitForElement(
        selector: Selector?,
        timeoutMs: Long = DEFAULT_TIMEOUT,
        scrollIfNotFound: Boolean = false,
        packageName: String? = targetPackage
    ): AccessibilityNodeInfo? {
        if (selector == null) return null
        val startTime = System.currentTimeMillis()
        val settleMs = minOf(SCROLL_SETTLE_MS, timeoutMs / 3)
        var scrollCount = 0
        var scrollForward = true
        var triedShowOnScreen = false

        Log.e(LOG, "[FIND] target=$selector package=${packageName ?: "any"}")
        report("Looking for “${selector.label()}”")

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            dumpTreeOnce(packageName)

            val node = finder.find(selector, packageName)
            if (node != null) {
                Log.e(LOG, "[FIND] FOUND class=${node.className} text=${node.text} desc=${node.contentDescription} bounds=${finder.boundsOf(node).toShortString()}")
                return node
            }

            val settled = System.currentTimeMillis() - startTime >= settleMs
            if (settled && !triedShowOnScreen) {
                triedShowOnScreen = true
                // The node may exist but be clipped/off screen (e.g. a pre-laid-out list row).
                val offscreen = finder.find(selector, packageName, includeOffscreen = true)
                if (offscreen != null && showOnScreen(offscreen)) {
                    Log.e(LOG, "[FIND] element was off screen, asked app to show it")
                    delay(600)
                    continue
                }
            }

            if (scrollIfNotFound && settled && scrollCount < SCROLL_MAX_RETRIES) {
                report("Scrolling to find “${selector.label()}”")
                if (scroll(scrollForward, packageName)) {
                    scrollCount++
                    Log.d(TAG, "Element not found, scrolled ${if (scrollForward) "down" else "up"} ($scrollCount)")
                    continue
                }
                if (scrollForward) {
                    // Reached the end of the list; the target may be above where we started.
                    scrollForward = false
                    continue
                }
            }

            delay(POLL_INTERVAL)
        }

        Log.e(LOG, "[FIND] TIMEOUT target=$selector, dumping tree for debug:")
        finder.roots(packageName).forEach { logNodeTree(it, 0) }
        return null
    }

    private fun showOnScreen(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
    }

    private fun dumpTreeOnce(packageName: String?) {
        if (hasDumpedTree || packageName == null) return
        val roots = finder.roots(packageName)
        if (roots.isEmpty()) return
        Log.d(TAG, "--- UI TREE START ($packageName) ---")
        roots.forEach { logNodeTree(it, 0) }
        Log.d(TAG, "--- UI TREE END ---")
        hasDumpedTree = true
    }

    private fun logNodeTree(node: AccessibilityNodeInfo, depth: Int) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val info = StringBuilder("[TREE] ")
        info.append("depth=$depth ")
        info.append("class=${node.className} ")
        info.append("id=${node.viewIdResourceName} ")
        info.append("text=${node.text} ")
        info.append("desc=${node.contentDescription} ")
        info.append("clickable=${node.isClickable} ")
        info.append("enabled=${node.isEnabled} ")
        info.append("visible=${node.isVisibleToUser} ")
        info.append("editable=${node.isEditable} ")
        info.append("scrollable=${node.isScrollable} ")
        info.append("childCount=${node.childCount} ")
        info.append("bounds=${bounds.toShortString()}")

        Log.e(LOG, info.toString())

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            logNodeTree(child, depth + 1)
        }
    }

    fun findNode(selector: Selector, packageName: String? = targetPackage): AccessibilityNodeInfo? =
        finder.find(selector, packageName)

    /**
     * Finds [selector] and clicks it. If the element (or its button) is present but disabled —
     * e.g. a checkout button that enables once the cart loads — it keeps waiting instead of
     * clicking something that ignores the click.
     */
    suspend fun clickElement(
        selector: Selector?,
        timeoutMs: Long = DEFAULT_TIMEOUT,
        scrollIfNotFound: Boolean = true,
        packageName: String? = targetPackage
    ): Boolean {
        if (selector == null) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val node = waitForElement(selector, remaining, scrollIfNotFound, packageName) ?: break
            if (!node.refresh()) { // UI changed between find and click; look again
                delay(POLL_INTERVAL)
                continue
            }
            val target = finder.clickTarget(node)
            if (!(target ?: node).isEnabled) {
                Log.e(LOG, "[CLICK] $selector is disabled, waiting")
                report("Waiting for “${selector.label()}” to become available")
                delay(POLL_INTERVAL)
                continue
            }
            report("Tapping “${selector.label()}”")
            return performClick(node, target)
        }
        Log.e(TAG, "Failed to find element to click: $selector")
        report("Couldn't find “${selector.label()}”")
        return false
    }

    /** Clicks the first of [selectors] (in priority order) that appears within [timeoutMs]. No scrolling. */
    suspend fun clickAny(
        selectors: List<Selector>,
        timeoutMs: Long = DEFAULT_TIMEOUT,
        packageName: String? = targetPackage
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            for (selector in selectors) {
                val node = finder.find(selector, packageName) ?: continue
                val target = finder.clickTarget(node)
                if ((target ?: node).isEnabled) {
                    report("Tapping “${selector.label()}”")
                    return performClick(node, target)
                }
            }
            delay(POLL_INTERVAL)
        }
        Log.e(LOG, "[CLICK] none of $selectors found")
        report("Couldn't find “${selectors.firstOrNull()?.label() ?: "element"}”")
        return false
    }

    private suspend fun performClick(node: AccessibilityNodeInfo, target: AccessibilityNodeInfo?): Boolean {
        if (target != null) {
            Log.e(LOG, "[CLICK] target class=${target.className} bounds=${finder.boundsOf(target).toShortString()}")
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.e(LOG, "[CLICK] ACTION_CLICK dispatched")
                return true
            }
            Log.e(LOG, "[CLICK] ACTION_CLICK rejected, falling back to tap")
        } else {
            Log.e(LOG, "[CLICK] no safe clickable ancestor, tapping the element itself")
        }

        // Tap the element's own on-screen bounds rather than a far-away ancestor.
        val point = finder.visibleCenter(node) ?: run {
            Log.e(LOG, "[CLICK] element has no visible area")
            return false
        }
        Log.e(LOG, "[CLICK] tap at (${point.x}, ${point.y})")
        return clickAt(point.x, point.y)
    }

    suspend fun inputText(
        selector: Selector?,
        text: String,
        timeoutMs: Long = DEFAULT_TIMEOUT,
        packageName: String? = targetPackage
    ): Boolean {
        if (selector == null) return false
        val found = waitForElement(selector, timeoutMs, packageName = packageName) ?: return false
        val node = finder.editableTarget(found)
        report("Typing “$text”")

        Log.e(LOG, "[INPUT] target = ${node.className} editable=${node.isEditable}")
        Log.e(LOG, "[INPUT] value = $text")

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(200)

        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val actionResult = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.e(LOG, "[INPUT] ACTION_SET_TEXT result=$actionResult")

        if (actionResult) {
            // Wait for UI to update
            delay(800)

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 3000) {
                val freshNode = finder.find(selector, packageName)?.let { finder.editableTarget(it) }
                val freshText = freshNode?.text?.toString() ?: ""
                Log.e(LOG, "[INPUT] freshText=$freshText")

                if (freshText.contains(text, ignoreCase = true)) {
                    Log.e(LOG, "[INPUT] VERIFIED SUCCESS")
                    return true
                }

                // Second success condition: the text appears elsewhere (e.g. search results)
                if (finder.find(Selector(text = text, classNameExclude = "EditText"), packageName) != null) {
                    Log.e(LOG, "[INPUT] VERIFIED SUCCESS (Result found on screen)")
                    return true
                }
                delay(300)
            }
            Log.e(LOG, "[INPUT] VERIFICATION TIMEOUT")
            return true // Still return true if action succeeded, to avoid blocking workflow
        }

        Log.e(LOG, "[INPUT] FAILED")
        return false
    }

    /**
     * Scrolls the main list of the target app's top-most window.
     * Returns false when the list can't scroll further in that direction.
     */
    suspend fun scroll(down: Boolean, packageName: String? = targetPackage): Boolean {
        val container = finder.scrollContainer(packageName)
        if (container != null) {
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            // Lists only offer the action while they can still move that way.
            if (container.actionList.none { it.id == action }) return false
            if (container.performAction(action)) {
                delay(800)
                return true
            }
            return dispatchScrollGesture(down, finder.boundsOf(container))
        }

        // No scrollable node exposed: swipe inside the app's window (never over ZeroUI's).
        val root = finder.roots(packageName).firstOrNull() ?: return false
        return dispatchScrollGesture(down, finder.boundsOf(root))
    }

    private suspend fun dispatchScrollGesture(down: Boolean, area: Rect): Boolean {
        if (area.isEmpty) return false
        val x = area.centerX().toFloat()
        val top = area.top + area.height() * 0.25f
        val bottom = area.top + area.height() * 0.75f

        val path = Path()
        path.moveTo(x, if (down) bottom else top)
        path.lineTo(x, if (down) top else bottom)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()

        val dispatched = dispatch(gesture)
        if (dispatched) delay(800)
        return dispatched
    }

    private suspend fun clickAt(x: Int, y: Int): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatch(gesture)
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean = suspendCoroutine { continuation ->
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                continuation.resume(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                continuation.resume(false)
            }
        }, null)
        // If the system refuses the gesture no callback ever fires; don't hang the workflow.
        if (!accepted) continuation.resume(false)
    }
}
