package com.example.myapplication.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.myapplication.R
import com.example.myapplication.ui.UserPreferences
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Round mic button floating over other apps that opens ZeroUI's voice dialog.
 *
 * Improvements inspired by modern Android accessibility & AI overlays:
 * - Remembers the user's preferred edge & vertical position across restarts ([UserPreferences]).
 * - Automatically dims and gently shrinks/tucks toward the edge after a few idle seconds so it
 *   doesn't obscure underlying app controls.
 * - Dragging reveals a bottom-center dismiss drop zone to temporarily hide the floating button.
 * - Can be temporarily hidden during screenshots via [setTemporarilyHidden].
 */
class FloatingMicButton(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onTap: () -> Unit,
    private val onHideRequested: () -> Unit = {}
) {

    enum class State(val color: Int, val ringColor: Int, val pulses: Boolean, val description: String) {
        READY(0xFF2563EB.toInt(), 0x4D93C5FD.toInt(), false, "Talk to ZeroUI"),
        LISTENING(0xFFEF4444.toInt(), 0x66FCA5A5.toInt(), true, "ZeroUI is listening"),
        WORKING(0xFF7C3AED.toInt(), 0x66C4B5FD.toInt(), true, "ZeroUI is working. Tap to open")
    }

    private companion object {
        const val SIZE_DP = 54
        const val ACTIVE_EDGE_MARGIN_DP = 10
        const val PEEK_EDGE_MARGIN_DP = 0
        const val START_Y_DP = 180
        const val TOUCH_SLOP_DP = 8
        const val SNAP_MS = 220L
        const val IDLE_DELAY_MS = 3500L
        const val IDLE_ALPHA = 0.46f
        const val ACTIVE_ALPHA = 0.96f
        const val IDLE_SCALE = 0.88f
        const val DISMISS_ZONE_SIZE_DP = 60
        const val DISMISS_HIT_RADIUS_DP = 68
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private var view: FrameLayout? = null
    private var background: GradientDrawable? = null
    private var pulse: ValueAnimator? = null
    private var snap: ValueAnimator? = null
    private var state = State.READY
    private var isIdleDimmed = false
    private var userVisible = true
    private var tempHidden = false
    private var autoDimEnabled = true
    private var isDragging = false

    // Dismiss drop zone shown only while dragging
    private var dismissZone: TextView? = null
    private var dismissBg: GradientDrawable? = null
    private var inDismissZone = false

    private val idleRunnable = Runnable { enterIdleDim() }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        // x counts from the right edge.
        gravity = Gravity.TOP or Gravity.END
        x = dp(ACTIVE_EDGE_MARGIN_DP)
        y = dp(START_Y_DP)
    }

    fun show(): Unit = onMain {
        UserPreferences.init(context)
        userVisible = UserPreferences.floatingVisible.value
        autoDimEnabled = UserPreferences.floatingAutoDim.value
        restoreSavedPosition()
        if (!userVisible || tempHidden) return@onMain
        attachButton()
    }

    fun applyPreferences(resetPosition: Boolean = false): Unit = onMain {
        UserPreferences.init(context)
        userVisible = UserPreferences.floatingVisible.value
        autoDimEnabled = UserPreferences.floatingAutoDim.value
        if (resetPosition) {
            UserPreferences.clearFloatingPosition()
            params.x = dp(ACTIVE_EDGE_MARGIN_DP)
            params.y = dp(START_Y_DP)
            view?.let { update(it) }
        }
        if (userVisible && !tempHidden) {
            if (view == null) attachButton()
            else {
                if (!autoDimEnabled) exitIdleDim(immediate = false)
                else scheduleIdleDim()
            }
        } else {
            detachButton()
        }
    }

    /** Temporarily hides the button without changing user preferences (e.g. during a screenshot). */
    fun setTemporarilyHidden(hidden: Boolean): Unit = onMain {
        if (tempHidden == hidden) return@onMain
        tempHidden = hidden
        if (hidden) {
            view?.visibility = View.GONE
        } else if (userVisible) {
            if (view == null) attachButton()
            else view?.visibility = View.VISIBLE
        }
    }

    private fun attachButton() {
        if (view != null) return
        val bgColor = state.color
        val borderColor = state.ringColor
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
            setStroke(dp(2), borderColor)
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val button = FrameLayout(context).apply {
            background = bg
            elevation = dp(8).toFloat()
            alpha = ACTIVE_ALPHA
            contentDescription = state.description
            isClickable = true
            isFocusable = true
            addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            setOnClickListener {
                exitIdleDim(immediate = true)
                onTap()
            }
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
            // Overlays can be refused while the service is being torn down; the dialog still works.
        }
    }

    private fun detachButton() {
        handler.removeCallbacks(idleRunnable)
        pulse?.cancel()
        snap?.cancel()
        hideDismissZone()
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
            }
        }
        view = null
        background = null
    }

    fun setState(newState: State): Unit = onMain {
        if (state == newState) return@onMain
        state = newState
        applyState()
    }

    fun destroy(): Unit = onMain {
        detachButton()
    }

    private fun applyState() {
        val button = view ?: return
        background?.setColor(state.color)
        background?.setStroke(dp(if (state.pulses) 3 else 2), state.ringColor)
        button.contentDescription = state.description
        pulse?.cancel()
        pulse = null
        handler.removeCallbacks(idleRunnable)

        if (state == State.READY) {
            button.scaleX = 1f
            button.scaleY = 1f
            button.alpha = ACTIVE_ALPHA
            scheduleIdleDim()
        } else {
            exitIdleDim(immediate = true)
            button.alpha = 1f
            if (state.pulses) {
                pulse = ValueAnimator.ofFloat(1f, 1.11f).apply {
                    duration = if (state == State.LISTENING) 460 else 760
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
    }

    private fun scheduleIdleDim() {
        handler.removeCallbacks(idleRunnable)
        if (!autoDimEnabled || state != State.READY || isDragging) return
        handler.postDelayed(idleRunnable, IDLE_DELAY_MS)
    }

    private fun enterIdleDim() {
        val button = view ?: return
        if (!autoDimEnabled || state != State.READY || isDragging) return
        isIdleDimmed = true
        button.animate()
            .alpha(IDLE_ALPHA)
            .scaleX(IDLE_SCALE)
            .scaleY(IDLE_SCALE)
            .setDuration(260)
            .start()
        val screenWidth = context.resources.displayMetrics.widthPixels
        val isOnRight = (params.x + button.width / 2) <= screenWidth / 2
        val peekX = if (isOnRight) dp(PEEK_EDGE_MARGIN_DP) else (screenWidth - button.width - dp(PEEK_EDGE_MARGIN_DP)).coerceAtLeast(0)
        animateXTo(button, peekX)
    }

    private fun exitIdleDim(immediate: Boolean) {
        handler.removeCallbacks(idleRunnable)
        val button = view ?: return
        val wasDimmed = isIdleDimmed
        isIdleDimmed = false
        button.animate().cancel()
        if (immediate) {
            button.alpha = ACTIVE_ALPHA
            button.scaleX = 1f
            button.scaleY = 1f
        } else {
            button.animate()
                .alpha(ACTIVE_ALPHA)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .start()
        }
        if (wasDimmed && !isDragging) {
            val screenWidth = context.resources.displayMetrics.widthPixels
            val isOnRight = (params.x + button.width / 2) <= screenWidth / 2
            val activeX = if (isOnRight) dp(ACTIVE_EDGE_MARGIN_DP) else (screenWidth - button.width - dp(ACTIVE_EDGE_MARGIN_DP)).coerceAtLeast(0)
            animateXTo(button, activeX)
        }
    }

    private fun animateXTo(v: View, targetX: Int) {
        snap?.cancel()
        if (params.x == targetX) return
        snap = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = SNAP_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                update(v)
            }
            start()
        }
    }

    /** Drags the button; a touch that barely moves is a tap. Dragging to bottom center hides it. */
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
                    exitIdleDim(immediate = false)
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    dragging = false
                    isDragging = false
                    inDismissZone = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!dragging && (abs(dx) > dp(TOUCH_SLOP_DP) || abs(dy) > dp(TOUCH_SLOP_DP))) {
                        dragging = true
                        isDragging = true
                        showDismissZone()
                    }
                    if (dragging) {
                        params.x = startX - dx.toInt()
                        params.y = (startY + dy.toInt()).coerceIn(0, maxY(v))
                        update(v)
                        updateDismissHighlight(e.rawX, e.rawY)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    if (dragging) {
                        val shouldHide = inDismissZone
                        hideDismissZone()
                        if (shouldHide) {
                            UserPreferences.setFloatingVisible(false)
                            userVisible = false
                            detachButton()
                            onHideRequested()
                        } else {
                            snapToEdge(v)
                        }
                    } else {
                        v.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    hideDismissZone()
                    if (dragging) snapToEdge(v) else scheduleIdleDim()
                }
            }
            return true
        }
    }

    private fun showDismissZone() {
        if (dismissZone != null) return
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC1E2330.toInt())
            setStroke(dp(2), 0x80FFFFFF.toInt())
        }
        val zone = TextView(context).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            background = bg
            elevation = dp(6).toFloat()
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
        }
        val zoneParams = WindowManager.LayoutParams(
            dp(DISMISS_ZONE_SIZE_DP),
            dp(DISMISS_ZONE_SIZE_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(40)
        }
        try {
            windowManager.addView(zone, zoneParams)
            dismissZone = zone
            dismissBg = bg
            zone.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        } catch (e: Exception) {
        }
    }

    private fun updateDismissHighlight(rawX: Float, rawY: Float) {
        val zone = dismissZone ?: return
        val dm = context.resources.displayMetrics
        val zoneCenterX = dm.widthPixels / 2f
        val zoneCenterY = dm.heightPixels - dp(40 + DISMISS_ZONE_SIZE_DP / 2).toFloat()
        val dist = hypot((rawX - zoneCenterX).toDouble(), (rawY - zoneCenterY).toDouble())
        val hovered = dist <= dp(DISMISS_HIT_RADIUS_DP)
        if (hovered == inDismissZone) return
        inDismissZone = hovered
        dismissBg?.setColor(if (hovered) 0xE6EF4444.toInt() else 0xCC1E2330.toInt())
        zone.animate()
            .scaleX(if (hovered) 1.18f else 1f)
            .scaleY(if (hovered) 1.18f else 1f)
            .setDuration(120)
            .start()
    }

    private fun hideDismissZone() {
        val zone = dismissZone ?: return
        dismissZone = null
        dismissBg = null
        inDismissZone = false
        try {
            windowManager.removeView(zone)
        } catch (e: Exception) {
        }
    }

    private fun snapToEdge(v: View) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val centerFromRight = params.x + v.width / 2
        val isRightEdge = centerFromRight <= screenWidth / 2
        val target = if (isRightEdge) dp(ACTIVE_EDGE_MARGIN_DP) else (screenWidth - v.width - dp(ACTIVE_EDGE_MARGIN_DP)).coerceAtLeast(0)
        UserPreferences.saveFloatingPosition(isRightEdge, params.y)
        animateXTo(v, target)
        scheduleIdleDim()
    }

    private fun restoreSavedPosition() {
        val saved = UserPreferences.getFloatingPosition() ?: return
        val (isRightEdge, savedY) = saved
        val dm = context.resources.displayMetrics
        val buttonSize = dp(SIZE_DP)
        params.x = if (isRightEdge) dp(ACTIVE_EDGE_MARGIN_DP) else (dm.widthPixels - buttonSize - dp(ACTIVE_EDGE_MARGIN_DP)).coerceAtLeast(0)
        params.y = savedY.coerceIn(dp(24), (dm.heightPixels - buttonSize - dp(ACTIVE_EDGE_MARGIN_DP)).coerceAtLeast(dp(24)))
    }

    private fun maxY(v: View): Int = (context.resources.displayMetrics.heightPixels - v.height - dp(ACTIVE_EDGE_MARGIN_DP)).coerceAtLeast(0)

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
            params.y = top + dp(ACTIVE_EDGE_MARGIN_DP)
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
