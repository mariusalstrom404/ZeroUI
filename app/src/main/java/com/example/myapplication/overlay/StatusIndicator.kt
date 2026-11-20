package com.example.myapplication.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Floating pill at the bottom (or top) of the screen that shows what ZeroUI is doing right now.
 *
 * Upgraded UX:
 * - Modern dark glass pill with subtle hairline border and smooth slide + fade transitions.
 * - Accessible 44dp stop target while a command is running.
 * - Optional multi-step badge & slim progress bar when executing a command chain (e.g. 1/3).
 * - Terminal phases (SUCCESS, ERROR, NEEDS_INPUT) can be tapped to open ZeroUI's dialog so users
 *   can read long replies in full instead of losing truncated lines.
 * - ZeroUI's own gestures still pass cleanly through via [setPassThrough].
 */
class StatusIndicator(private val context: Context, private val windowManager: WindowManager) {

    enum class Phase(val color: Int, val symbol: String?) {
        UNDERSTANDING(0xFFA78BFA.toInt(), null),
        WORKING(0xFF60A5FA.toInt(), null),
        SUCCESS(0xFF34D399.toInt(), "✓"),
        NEEDS_INPUT(0xFFFBBF24.toInt(), "?"),
        ERROR(0xFFF87171.toInt(), "!"),
        STOPPED(0xFF94A3B8.toInt(), "■");

        /** Phases where a command is still running and can be stopped. */
        val stoppable: Boolean get() = symbol == null

        /** Phases where tapping the pill opens the full chat dialog. */
        val openable: Boolean get() = this == SUCCESS || this == ERROR || this == NEEDS_INPUT
    }

    /** Called on the main thread when the user taps the stop button. */
    var onStop: (() -> Unit)? = null

    /** Called on the main thread when the user taps a finished/error pill to view details. */
    var onOpenDialog: (() -> Unit)? = null

    companion object {
        private const val SUCCESS_HIDE_MS = 3500L
        private const val LONG_SUCCESS_HIDE_MS = 5000L
        private const val NEEDS_INPUT_HIDE_MS = 5500L
        private const val ERROR_HIDE_MS = 6500L
        private const val STOPPED_HIDE_MS = 2500L
        private const val FADE_MS = 200L
        private const val BOTTOM_MARGIN_DP = 14
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val hideRunnable = Runnable { hide() }

    private var root: LinearLayout? = null
    private lateinit var spinner: ProgressBar
    private lateinit var badge: TextView
    private lateinit var titleView: TextView
    private lateinit var stepChip: TextView
    private lateinit var detailView: TextView
    private lateinit var stopButton: FrameLayout
    private lateinit var chevronView: TextView
    private lateinit var stepProgressBar: ProgressBar

    private var attached = false
    private var currentPhase = Phase.WORKING
    /** The pill currently accepts user touches (either for Stop or Tap-to-view). */
    private var wantsTouch = false
    /** ZeroUI is dispatching a gesture that must not land on the pill. */
    private var passThrough = false
    /** Bumped on every show so a fade-out that started earlier doesn't remove a newer status. */
    private var generation = 0

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = bottomOffset()
    }

    /**
     * Shows [phase] with [title], optional [detail], and optional multi-command [stepProgress]
     * (`currentStep to totalSteps`). With [atTop] the pill sits at the top of the screen so it
     * doesn't overlap the dialog's input bar.
     */
    fun show(
        phase: Phase,
        title: String,
        detail: String? = null,
        atTop: Boolean = false,
        stepProgress: Pair<Int, Int>? = null
    ): Unit = onMain {
        val view = ensureAttached() ?: return@onMain
        currentPhase = phase
        params.gravity = (if (atTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        updatePosition(view, force = true)
        generation++
        handler.removeCallbacks(hideRunnable)
        view.animate().cancel()
        view.translationY = if (atTop) -dp(8).toFloat() else dp(8).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(FADE_MS).start()

        spinner.visibility = if (phase.symbol == null) View.VISIBLE else View.GONE
        spinner.indeterminateTintList = ColorStateList.valueOf(phase.color)
        badge.visibility = if (phase.symbol == null) View.GONE else View.VISIBLE
        badge.text = phase.symbol
        (badge.background as GradientDrawable).setColor(phase.color)

        titleView.text = title
        setDetail(detail)

        if (stepProgress != null && stepProgress.second > 1) {
            val (cur, total) = stepProgress
            stepChip.visibility = View.VISIBLE
            stepChip.text = "$cur/$total"
            stepProgressBar.visibility = View.VISIBLE
            stepProgressBar.max = total * 100
            stepProgressBar.progress = (cur * 100).coerceIn(0, total * 100)
            stepProgressBar.progressTintList = ColorStateList.valueOf(phase.color)
        } else {
            stepChip.visibility = View.GONE
            stepProgressBar.visibility = View.GONE
        }

        val canStop = phase.stoppable
        val canOpen = phase.openable && onOpenDialog != null
        stopButton.visibility = if (canStop) View.VISIBLE else View.GONE
        chevronView.visibility = if (!canStop && canOpen) View.VISIBLE else View.GONE
        wantsTouch = canStop || canOpen
        applyTouchability()

        val hideAfter = when (phase) {
            Phase.SUCCESS -> if ((detail?.length ?: 0) > 60) LONG_SUCCESS_HIDE_MS else SUCCESS_HIDE_MS
            Phase.NEEDS_INPUT -> NEEDS_INPUT_HIDE_MS
            Phase.ERROR -> ERROR_HIDE_MS
            Phase.STOPPED -> STOPPED_HIDE_MS
            else -> null
        }
        hideAfter?.let { handler.postDelayed(hideRunnable, it) }
    }

    /** Updates only the second line (e.g. the automation step), keeping the current phase and title. */
    fun updateDetail(detail: String?): Unit = onMain {
        if (attached) setDetail(detail)
    }

    /**
     * While [enabled], the pill ignores touches so a tap or swipe ZeroUI dispatches underneath it
     * reaches the app instead of the pill.
     */
    fun setPassThrough(enabled: Boolean): Unit = onMain {
        passThrough = enabled
        applyTouchability()
    }

    private fun applyTouchability() {
        val view = root ?: return
        val touchable = wantsTouch && !passThrough
        val flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (flags == params.flags) return
        params.flags = flags
        if (attached) {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
            }
        }
    }

    fun hide(): Unit = onMain {
        val view = root ?: return@onMain
        if (!attached) return@onMain
        handler.removeCallbacks(hideRunnable)
        val token = generation
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            if (token == generation) detach()
        }.start()
    }

    /** Removes the indicator immediately, e.g. so it doesn't appear in a screenshot. */
    fun hideNow(): Unit = onMain {
        handler.removeCallbacks(hideRunnable)
        generation++
        detach()
    }

    fun destroy(): Unit = onMain {
        handler.removeCallbacksAndMessages(null)
        detach()
        root = null
    }

    private fun setDetail(detail: String?) {
        detailView.text = detail
        detailView.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun ensureAttached(): LinearLayout? {
        val view = root ?: buildView().also { root = it }
        if (!attached) {
            try {
                view.alpha = 0f
                windowManager.addView(view, params)
                attached = true
            } catch (e: Exception) {
                return null
            }
        }
        return view
    }

    private fun detach() {
        val view = root ?: return
        if (!attached) return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
        }
        attached = false
    }

    private fun buildView(): LinearLayout {
        val maxTextWidth = (context.resources.displayMetrics.widthPixels * 0.64).toInt()

        spinner = ProgressBar(context).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        }
        badge = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFF0F172A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val iconBox = FrameLayout(context).apply {
            addView(spinner)
            addView(badge)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) }
        }

        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = maxTextWidth
        }

        stepChip = TextView(context).apply {
            setTextColor(0xFFCBD5E1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x26FFFFFF)
            }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView)
            addView(stepChip)
        }

        detailView = TextView(context).apply {
            setTextColor(0xFFCBD5E1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = maxTextWidth
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleRow)
            addView(detailView)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        // Accessible 44dp stop button with crisp drawn stop square
        val stopSquare = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(Color.WHITE)
            }
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER)
        }
        stopButton = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xD9EF4444.toInt())
            }
            contentDescription = "Stop"
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(12) }
            addView(stopSquare)
            setOnClickListener { onStop?.invoke() }
        }

        chevronView = TextView(context).apply {
            text = "›"
            gravity = Gravity.CENTER
            setTextColor(0xFF94A3B8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) }
        }

        val mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconBox)
            addView(texts)
            addView(stopButton)
            addView(chevronView)
        }

        stepProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            visibility = View.GONE
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3)
            ).apply { topMargin = dp(8) }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xF0181C24.toInt())
                setStroke(dp(1), 0x2BFFFFFF)
            }
            elevation = dp(8).toFloat()
            addView(mainRow)
            addView(stepProgressBar)
            setOnClickListener {
                if (currentPhase.openable && !passThrough) {
                    hide()
                    onOpenDialog?.invoke()
                }
            }
        }
    }

    /** Re-applies the edge offset, e.g. after rotation, a navigation-mode change or a move to the other edge. */
    private fun updatePosition(view: View, force: Boolean = false) {
        val wanted = if (params.gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.TOP) topOffset() else bottomOffset()
        if (force || params.y != wanted) {
            params.y = wanted
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
            }
        }
    }

    private fun bottomOffset(): Int = navigationBarHeight() + dp(BOTTOM_MARGIN_DP)

    @android.annotation.SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun topOffset(): Int {
        val bar = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()
            ).top
        } else {
            val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24)
        }
        return bar + dp(BOTTOM_MARGIN_DP)
    }

    @android.annotation.SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun navigationBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.navigationBars() or
                    android.view.WindowInsets.Type.displayCutout()
            ).bottom
        }
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(48)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }
}
