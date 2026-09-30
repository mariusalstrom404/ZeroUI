package com.example.myapplication.debug

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityTreeDumper {
    private const val TAG = "UI_TREE"

    /**
     * Dumps the entire accessibility tree starting from the root node.
     */
    fun dump(root: AccessibilityNodeInfo?) {
        if (root == null) {
            Log.d(TAG, "Root is null, cannot dump tree.")
            return
        }
        Log.d(TAG, "--- Accessibility Tree Dump Start ---")
        dumpNode(root, 0)
        Log.d(TAG, "--- Accessibility Tree Dump End ---")
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null) return

        val indent = "  ".repeat(depth)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val info = StringBuilder()
        info.append("${indent}depth=$depth\n")
        info.append("${indent}class=${node.className}\n")
        info.append("${indent}pkg=${node.packageName}\n")
        info.append("${indent}id=${node.viewIdResourceName}\n")
        info.append("${indent}text=${node.text}\n")
        info.append("${indent}desc=${node.contentDescription}\n")
        info.append("${indent}clickable=${node.isClickable}\n")
        info.append("${indent}longClickable=${node.isLongClickable}\n")
        info.append("${indent}focusable=${node.isFocusable}\n")
        info.append("${indent}focused=${node.isFocused}\n")
        info.append("${indent}editable=${node.isEditable}\n")
        info.append("${indent}scrollable=${node.isScrollable}\n")
        info.append("${indent}enabled=${node.isEnabled}\n")
        info.append("${indent}bounds=${bounds.toShortString()}\n")
        info.append("${indent}childCount=${node.childCount}\n")
        
        // Actions
        val actions = node.actionList.joinToString(", ") { actionToString(it.id) }
        info.append("${indent}actions=[$actions]")

        Log.d(TAG, info.toString())

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            dumpNode(child, depth + 1)
        }
    }

    private fun actionToString(action: Int): String {
        return when (action) {
            AccessibilityNodeInfo.ACTION_FOCUS -> "FOCUS"
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "CLEAR_FOCUS"
            AccessibilityNodeInfo.ACTION_SELECT -> "SELECT"
            AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "CLEAR_SELECTION"
            AccessibilityNodeInfo.ACTION_CLICK -> "CLICK"
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> "LONG_CLICK"
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> "ACCESSIBILITY_FOCUS"
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> "CLEAR_ACCESSIBILITY_FOCUS"
            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY -> "NEXT_MOVEMENT"
            AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY -> "PREV_MOVEMENT"
            AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT -> "NEXT_HTML"
            AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT -> "PREV_HTML"
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "SCROLL_FORWARD"
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "SCROLL_BACKWARD"
            AccessibilityNodeInfo.ACTION_COPY -> "COPY"
            AccessibilityNodeInfo.ACTION_PASTE -> "PASTE"
            AccessibilityNodeInfo.ACTION_CUT -> "CUT"
            AccessibilityNodeInfo.ACTION_SET_SELECTION -> "SET_SELECTION"
            AccessibilityNodeInfo.ACTION_EXPAND -> "EXPAND"
            AccessibilityNodeInfo.ACTION_COLLAPSE -> "COLLAPSE"
            AccessibilityNodeInfo.ACTION_DISMISS -> "DISMISS"
            AccessibilityNodeInfo.ACTION_SET_TEXT -> "SET_TEXT"
            else -> "UNKNOWN($action)"
        }
    }
}
