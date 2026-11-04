package com.example.myapplication.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.myapplication.R
import kotlin.math.abs

/**
 * Round mic button floating over other apps that opens ZeroUI's voice dialog. It can be dragged,
 * snaps to the nearest side edge when let go, and its colour and pulse show what ZeroUI is doing
 * ([State]). All methods may be called from any thread.
 */
class FloatingMicButton(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onTap: () -> Unit
) {

    enum class State(val color: Int, val pulses: Boolean, val description: String) {
        READY(0xFF1A73E8.toInt(), false, "Talk to ZeroUI"),
        LISTENING(0xFFE53935.toInt(), true, "ZeroUI is listening"),
        WORKING(0xFF8430CE.toInt(), true, "ZeroUI is working. Tap to open")
    }

    private companion object {
        const val SIZE_DP = 52
        const val EDGE_MARGIN_DP = 8
        const val START_Y_DP = 160
        const val TOUCH_SLOP_DP = 8
        const val SNAP_MS = 220L
        const val IDLE_ALPHA = 0.85f
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private var view: FrameLayout? = null
    private var background: GradientDrawable? = null
    private var pulse: ValueAnimator? = null
    private var snap: ValueAnimator? = null
    private var state = State.READY

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        // x counts from the right edge.
        gravity = Gravity.TOP or Gravity.END
        x = dp(EDGE_MARGIN_DP)
        y = dp(START_Y_DP)
    }

    fun show(): Unit = onMain {
        if (view != null) return@onMain
        // Not inside apply: a Drawable has its own `state`.
        val color = state.color
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val button = FrameLayout(context).apply {
            background = bg
            elevation = dp(6).toFloat()
            alpha = IDLE_ALPHA
            contentDescription = state.description
            isClickable = true
            isFocusable = true
            addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            setOnClickListener { onTap() }
            setOnTouchListener(DragListener())
            setOnApplyWindowInsetsListener { v, insets -> keepBelowStatusBar(v, insets); insets }
        }
        try {
            windowManager.addView(button, params.apply {
                width = dp(SIZE_DP)
                height = dp(SIZE_DP)
            })
            view = button
            background = bg
            applyState()
        } catch (e: Exception) {
            // Overlays can be refused (e.g. while the service is being torn down); the dialog still works.
        }
    }

    fun setState(newState: State): Unit = onMain {
        if (state == newState) return@onMain
        state = newState
        applyState()
    }

    fun destroy(): Unit = onMain {
        pulse?.cancel()
        snap?.cancel()
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
            }
        }
        view = null
    }

    private fun applyState() {
        val button = view ?: return
        background?.setColor(state.color)
        button.contentDescription = state.description
        pulse?.cancel()
        pulse = null
        button.scaleX = 1f
        button.scaleY = 1f
        button.alpha = if (state == State.READY) IDLE_ALPHA else 1f
        if (state.pulses) {
            pulse = ValueAnimator.ofFloat(1f, 1.12f).apply {
                duration = if (state == State.LISTENING) 500 else 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    val scale = it.animatedValue as Float
                    button.scaleX = scale
                    button.scaleY = scale
                }
                start()
            }
        }
    }

    /** Drags the button; a touch that barely moves is a tap. On release it snaps to the nearer side. */
    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    snap?.cancel()
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!dragging && (abs(dx) > dp(TOUCH_SLOP_DP) || abs(dy) > dp(TOUCH_SLOP_DP))) dragging = true
                    if (dragging) {
                        params.x = startX - dx.toInt()
                        params.y = (startY + dy.toInt()).coerceIn(0, maxY(v))
                        update(v)
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) snapToEdge(v) else v.performClick()
                MotionEvent.ACTION_CANCEL -> if (dragging) snapToEdge(v)
            }
            return true
        }
    }

    private fun snapToEdge(v: View) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val centerFromRight = params.x + v.width / 2
        val target = if (centerFromRight > screenWidth / 2) screenWidth - v.width - dp(EDGE_MARGIN_DP) else dp(EDGE_MARGIN_DP)
        snap = ValueAnimator.ofInt(params.x, target).apply {
            duration = SNAP_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                update(v)
            }
            start()
        }
    }

    private fun maxY(v: View): Int = context.resources.displayMetrics.heightPixels - v.height - dp(EDGE_MARGIN_DP)

    /** Keeps the button clear of the status bar and camera cutout when apps draw edge to edge. */
    private fun keepBelowStatusBar(v: View, insets: android.view.WindowInsets) {
        val top = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            ).top
        } else {
            0
        }
        if (params.y < top) {
            params.y = top + dp(EDGE_MARGIN_DP)
            update(v)
        }
    }

    private fun update(v: View) {
        try {
            windowManager.updateViewLayout(v, params)
        } catch (e: Exception) {
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }
}
