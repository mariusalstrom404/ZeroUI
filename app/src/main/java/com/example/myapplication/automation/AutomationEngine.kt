package com.example.myapplication.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AutomationEngine(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "AutomationEngine"
        private const val DEFAULT_TIMEOUT = 10000L
        private const val SCROLL_MAX_RETRIES = 5
    }

    private var hasDumpedTree = false

    suspend fun execute(steps: List<AutomationStep>): Boolean {
        hasDumpedTree = false
        for ((index, step) in steps.withIndex()) {
            Log.d(TAG, "Executing step ${index + 1}/${steps.size}: ${step.type} - ${step.description ?: ""}")
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
                Log.e("ZeroUIAutomation", "Automation exception in step ${step.description}", e)
                false
            }

            if (!success && !step.optional) {
                Log.e(TAG, "Step failed: ${step.description}. Aborting workflow.")
                return false
            }
            Log.d(TAG, "Step success: ${step.description}")
            delay(500) // Small stability delay between steps
        }
        return true
    }

    private fun openApp(packageName: String): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            true
        } else {
            false
        }
    }

    suspend fun waitForElement(selector: Selector?, timeoutMs: Long = DEFAULT_TIMEOUT, scrollIfNotFound: Boolean = false): AccessibilityNodeInfo? {
        if (selector == null) return null
        val startTime = System.currentTimeMillis()
        var scrollCount = 0
        
        Log.d(TAG, "[FoodGorilla] Finding element: $selector")
        Log.e("ZeroUIAutomation", "[FIND] target=${selector.testTag ?: selector.resourceId ?: selector.text ?: selector.contentDescription}")
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = service.rootInActiveWindow
            Log.e("ZeroUIAutomation", "root=${if (root == null) "NULL" else "OK"}, package=${root?.packageName}")
            
            if (root == null) {
                Log.w(TAG, "[FoodGorilla] rootInActiveWindow is NULL")
                delay(1000)
                continue
            }
            
            // Log tree for debugging only once after app open or when package changes
            if (!hasDumpedTree && root.packageName?.contains("foodgorilla") == true) {
                Log.d(TAG, "[FoodGorilla] --- UI TREE START ---")
                logNodeTree(root, 0)
                Log.d(TAG, "[FoodGorilla] --- UI TREE END ---")
                hasDumpedTree = true
            }

            val node = findNodeRecursive(root, selector)
            if (node != null) {
                Log.e("ZeroUIAutomation", "Found element by $selector, package=${node.packageName}")
                Log.d(TAG, "[FoodGorilla] Found element by $selector")
                return node
            }
            
            if (scrollIfNotFound && scrollCount < SCROLL_MAX_RETRIES) {
                Log.d(TAG, "[FoodGorilla] Element not found, scrolling... ($scrollCount)")
                if (scroll(true)) {
                    scrollCount++
                    hasDumpedTree = false // Allow dump after scroll
                    delay(1000)
                    continue
                }
            }
            
            delay(500)
        }
        Log.e("ZeroUIAutomation", "[FIND] TIMEOUT")
        val root = service.rootInActiveWindow
        if (root != null) {
            Log.e("ZeroUIAutomation", "[FAILED] dumping tree for debug:")
            logNodeTree(root, 0)
        }
        Log.e(TAG, "[FoodGorilla] FAILED: element not accessible: $selector")
        return null
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
        info.append("focusable=${node.isFocusable} ")
        info.append("editable=${node.isEditable} ")
        info.append("scrollable=${node.isScrollable} ")
        info.append("childCount=${node.childCount} ")
        info.append("bounds=${bounds.toShortString()}")
        
        Log.e("ZeroUIAutomation", info.toString())

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            logNodeTree(child, depth + 1)
        }
    }

    fun findNode(selector: Selector): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findNodeRecursive(root, selector)
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo, selector: Selector): AccessibilityNodeInfo? {
        if (matchNode(node, selector)) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeRecursive(child, selector)
            if (found != null) return found
        }
        return null
    }

    private fun matchNode(node: AccessibilityNodeInfo, selector: Selector): Boolean {
        // Class exclusion check
        if (selector.classNameExclude != null && node.className?.contains(selector.classNameExclude) == true) {
            return false
        }

        // Priority 1: resourceId / testTag
        val targetId = selector.testTag ?: selector.resourceId
        if (targetId != null) {
            val actualId = node.viewIdResourceName
            if (actualId != null && (actualId == targetId || actualId.endsWith(":id/$targetId") || actualId.contains(targetId))) {
                Log.e("ZeroUIAutomation", "[FIND] ${selector.testTag ?: "element"} byId = FOUND")
                return true
            }
            // Compose testTag from extras
            val testTag = node.extras?.getString("androidx.compose.ui.semantics.testTag")
            if (testTag != null && testTag.contains(targetId)) {
                Log.e("ZeroUIAutomation", "[FIND] ${selector.testTag ?: "element"} byId = FOUND (extras)")
                return true
            }
        }

        // Priority 2: contentDescription
        if (selector.contentDescription != null) {
            val desc = node.contentDescription?.toString()
            if (desc != null && desc.contains(selector.contentDescription, ignoreCase = true)) {
                Log.e("ZeroUIAutomation", "[FIND] ${selector.testTag ?: "element"} byDescription=FOUND")
                return true
            }
        }

        // Priority 3: text
        if (selector.text != null) {
            val nodeText = node.text?.toString() ?: node.contentDescription?.toString()
            if (nodeText != null && nodeText.contains(selector.text, ignoreCase = true)) {
                // Safety check: if this node is nearly full screen, it's likely a container, not the target text node.
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val metrics = service.resources.displayMetrics
                val isTooBig = (bounds.width() > metrics.widthPixels * 0.9 && bounds.height() > metrics.heightPixels * 0.9)
                
                if (!isTooBig) {
                    Log.e("ZeroUIAutomation", "[TEXT_MATCH] target=${selector.text}")
                    Log.e("ZeroUIAutomation", "[TEXT_MATCH] exact node: class=${node.className} text=$nodeText bounds=${bounds.toShortString()}")
                    return true
                }
            }
        }

        // Priority 4: Semantic Fallback for Search Bar
        if (selector.testTag == "search_bar" || selector.testTag == "menu_search_bar") {
            if (node.className?.contains("EditText") == true && node.isEditable) {
                if (hasChildWithText(node, "Search for food or restaurants") || 
                    hasChildWithText(node, "Search") ||
                    node.text?.toString()?.contains("Search", ignoreCase = true) == true) {
                    Log.e("ZeroUIAutomation", "[FIND] search_bar byEditable = FOUND")
                    return true
                }
            }
        }
        
        return false
    }

    private fun hasChildWithText(node: AccessibilityNodeInfo, targetText: String): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val text = child.text?.toString() ?: child.contentDescription?.toString() ?: ""
            if (text.contains(targetText, ignoreCase = true)) return true
            if (hasChildWithText(child, targetText)) return true
        }
        return false
    }

    suspend fun clickElement(selector: Selector?, timeoutMs: Long = DEFAULT_TIMEOUT): Boolean {
        val node = waitForElement(selector, timeoutMs, scrollIfNotFound = true) ?: run {
            Log.e(TAG, "Failed to find element to click: $selector")
            return false
        }
        return performClick(node)
    }

    private suspend fun performClick(node: AccessibilityNodeInfo): Boolean {
        // Find clickable ancestor starting from the node itself
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        var clickableAncestor: AccessibilityNodeInfo? = null
        
        Log.e("ZeroUIAutomation", "[PARENT_CHAIN] --- START ---")
        while (current != null) {
            val bounds = Rect()
            current.getBoundsInScreen(bounds)
            Log.e("ZeroUIAutomation", "[PARENT_CHAIN] depth=$depth class=${current.className} text=${current.text} clickable=${current.isClickable} focusable=${current.isFocusable} bounds=${bounds.toShortString()} children=${current.childCount}")
            
            if (clickableAncestor == null && current.isClickable) {
                clickableAncestor = current
                Log.e("ZeroUIAutomation", "[TEXT_MATCH] parent $depth: class=${current.className} clickable=TRUE")
            } else if (clickableAncestor == null) {
                Log.e("ZeroUIAutomation", "[TEXT_MATCH] parent $depth: class=${current.className} clickable=false")
            }
            
            val nextParent = current.parent
            current = nextParent
            depth++
        }
        Log.e("ZeroUIAutomation", "[PARENT_CHAIN] --- END ---")

        if (clickableAncestor != null) {
            Log.e("ZeroUIAutomation", "[TEXT_MATCH] clickable ancestor FOUND: class=${clickableAncestor.className}")
            val success = clickableAncestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (success) Log.e("ZeroUIAutomation", "[CLICK] ancestor ACTION_CLICK dispatched")
            return success
        }
        
        // Fallback: use gesture on the ORIGINAL node bounds
        Log.e("ZeroUIAutomation", "[CLICK] no clickable ancestor found, using gesture fallback")
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        Log.e("ZeroUIAutomation", "[CLICK] final target bounds=${bounds.toShortString()} center=(${bounds.centerX()}, ${bounds.centerY()})")
        
        val success = clickAt(bounds.centerX(), bounds.centerY())
        if (success) Log.e("ZeroUIAutomation", "[CLICK] gesture DISPATCHED")
        return success
    }

    suspend fun inputText(selector: Selector?, text: String, timeoutMs: Long = DEFAULT_TIMEOUT): Boolean {
        val node = waitForElement(selector, timeoutMs) ?: return false
        
        Log.e("ZeroUIAutomation", "[INPUT] target = ${node.className}")
        Log.e("ZeroUIAutomation", "[INPUT] value = $text")
        
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(200)
        
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val actionResult = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.e("ZeroUIAutomation", "[INPUT] ACTION_SET_TEXT result=$actionResult")
        
        if (actionResult) {
            // Wait for UI to update
            delay(800)
            
            // Reacquire root and find fresh node for verification
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 3000) {
                Log.e("ZeroUIAutomation", "[INPUT] reacquiring root...")
                val freshRoot = service.rootInActiveWindow
                if (freshRoot != null) {
                    val freshNode = findNodeRecursive(freshRoot, selector!!)
                    val freshText = freshNode?.text?.toString() ?: ""
                    Log.e("ZeroUIAutomation", "[INPUT] freshText=$freshText")
                    
                    if (freshText.contains(text, ignoreCase = true)) {
                        Log.e("ZeroUIAutomation", "[INPUT] VERIFIED SUCCESS")
                        return true
                    }
                    
                    // Second success condition: Check if the text appears elsewhere (e.g. search results)
                    if (findNodeByTextRecursive(freshRoot, text, excludeEditText = true) != null) {
                        Log.e("ZeroUIAutomation", "[INPUT] VERIFIED SUCCESS (Result found on screen)")
                        return true
                    }
                }
                delay(300)
            }
            Log.e("ZeroUIAutomation", "[INPUT] VERIFICATION TIMEOUT")
            return true // Still return true if action succeeded, to avoid blocking workflow
        }
        
        Log.e("ZeroUIAutomation", "[INPUT] FAILED")
        return false
    }

    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo, text: String, excludeEditText: Boolean): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if (nodeText.contains(text, ignoreCase = true)) {
            if (!excludeEditText || node.className?.contains("EditText") != true) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTextRecursive(child, text, excludeEditText)
            if (found != null) return found
        }
        return null
    }

    suspend fun scroll(down: Boolean): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val scrollableNode = findScrollableNode(root)
        
        if (scrollableNode != null) {
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollableNode.performAction(action)) {
                delay(1000)
                return true
            }
        }
        
        // Fallback to gesture
        return dispatchScrollGesture(down)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    private suspend fun dispatchScrollGesture(down: Boolean): Boolean {
        val displayMetrics = service.resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        val startX = width / 2
        val startY = if (down) (height * 0.8).toInt() else (height * 0.2).toInt()
        val endY = if (down) (height * 0.2).toInt() else (height * 0.8).toInt()

        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())
        path.lineTo(startX.toFloat(), endY.toFloat())

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()

        return suspendCoroutine { continuation ->
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    continuation.resume(false)
                }
            }, null)
        }
    }

    private suspend fun clickAt(x: Int, y: Int): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
            
        return suspendCoroutine { continuation ->
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    continuation.resume(false)
                }
            }, null)
        }
    }
}
