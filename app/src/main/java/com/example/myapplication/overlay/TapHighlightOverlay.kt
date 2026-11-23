package com.example.myapplication.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Non-touchable accessibility overlay that briefly flashes a glowing rounded outline around the
 * target element ZeroUI is tapping or long-pressing. Gives instant visual feedback without
 * delaying or intercepting any touch gesture.
 */
class TapHighlightOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private var ringView: View? = null
    private var attached = false
    private var generation = 0

    private val params = WindowManager.LayoutParams(
        0,
        0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    /** Flashes a highlight frame around [bounds] in screen coordinates; safe to call from any thread. */
    fun flash(bounds: Rect, longPress: Boolean = false) {
        if (bounds.isEmpty) return
        val copy = Rect(bounds)
        onMain {
            val pad = dp(4)
            val minSize = dp(28)
            val width = (copy.width() + pad * 2).coerceAtLeast(minSize)
            val height = (copy.height() + pad * 2).coerceAtLeast(minSize)
            val x = (copy.centerX() - width / 2).coerceAtLeast(0)
            val y = (copy.centerY() - height / 2).coerceAtLeast(0)

            val view = ensureAttached() ?: return@onMain
            val token = ++generation
            params.x = x
            params.y = y
            params.width = width
            params.height = height
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
                return@onMain
            }

            val strokeColor = if (longPress) 0xFFF59E0B.toInt() else 0xFF60A5FA.toInt()
            val fillColor = if (longPress) 0x33F59E0B else 0x3360A5FA
            (view.background as GradientDrawable).apply {
                setColor(fillColor)
                setStroke(dp(3), strokeColor)
                cornerRadius = dp(12).toFloat()
            }

            view.animate().cancel()
            view.alpha = 0.95f
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            view.animate()
                .scaleX(1.02f)
                .scaleY(1.02f)
                .setDuration(110L)
                .withEndAction {
                    if (token != generation) return@withEndAction
                    view.animate()
                        .alpha(0f)
                        .setStartDelay(if (longPress) 260L else 130L)
                        .setDuration(180L)
                        .withEndAction { if (token == generation) detach() }
                        .start()
                }
                .start()
        }
    }

    fun destroy(): Unit = onMain {
        generation++
        handler.removeCallbacksAndMessages(null)
        detach()
        ringView = null
    }

    private fun ensureAttached(): View? {
        val view = ringView ?: View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also { ringView = it }

        if (!attached) {
            try {
                windowManager.addView(view, params)
                attached = true
            } catch (_: Exception) {
                return null
            }
        }
        return view
    }

    private fun detach() {
        val view = ringView ?: return
        if (!attached) return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        attached = false
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }
}
