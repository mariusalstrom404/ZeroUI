package com.example.myapplication.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.myapplication.ipc.CommandBridge

/**
 * Floating pill that tells the user what ZeroUI is doing right now ("Understanding…", "Looking for “Cart”", "Done", errors).
 *
 * Supports drag-to-move positioning across the entire screen and keyboard-avoiding inline feedback input over active apps.
 */
class StatusIndicator(private val context: Context, private val windowManager: WindowManager) {

    enum class Phase(val color: Int, val symbol: String?) {
        UNDERSTANDING(0xFFC58AF9.toInt(), null),
        WORKING(0xFF8AB4F8.toInt(), null),
        SUCCESS(0xFF81C995.toInt(), "✓"),
        NEEDS_INPUT(0xFFFDD663.toInt(), "?"),
        ERROR(0xFFF28B82.toInt(), "!"),
        STOPPED(0xFFBDC1C6.toInt(), "■");

        val stoppable: Boolean get() = symbol == null
    }

    /** Called on the main thread when the user taps the stop button. */
    var onStop: (() -> Unit)? = null

    /** Called on the main thread when the user taps the feedback button. */
    var onFeedback: (() -> Unit)? = null

    /** Called on the main thread when the user submits inline overlay feedback. */
    var onFeedbackSubmit: ((String) -> Unit)? = null

    companion object {
        private const val SUCCESS_HIDE_MS = 3000L
        private const val NEEDS_INPUT_HIDE_MS = 5000L
        private const val ERROR_HIDE_MS = 6000L
        private const val STOPPED_HIDE_MS = 2500L
        private const val FADE_MS = 200L
        private const val BOTTOM_MARGIN_DP = 12
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val displayMetrics = context.resources.displayMetrics
    private val hideRunnable = Runnable { hide() }

    private var root: LinearLayout? = null
    private lateinit var spinner: ProgressBar
    private lateinit var badge: TextView
    private lateinit var titleView: TextView
    private lateinit var detailView: TextView
    private lateinit var stopButton: TextView
    private lateinit var feedbackButton: TextView
    private lateinit var feedbackInputRow: LinearLayout
    private lateinit var feedbackEditText: EditText

    private var attached = false
    private var stoppable = false
    private var passThrough = false
    private var generation = 0
    private var userHasDragged = false
    private var savedYBeforeInput = -1

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = (displayMetrics.widthPixels - dp(280)) / 2
        y = displayMetrics.heightPixels - navigationBarHeight() - dp(80)
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
    }

    fun show(phase: Phase, title: String, detail: String? = null): Unit = onMain {
        val view = ensureAttached() ?: return@onMain
        updatePosition(view)
        generation++
        handler.removeCallbacks(hideRunnable)
        view.animate().cancel()
        view.animate().alpha(1f).setDuration(FADE_MS).start()

        spinner.visibility = if (phase.symbol == null) View.VISIBLE else View.GONE
        spinner.indeterminateTintList = ColorStateList.valueOf(phase.color)
        badge.visibility = if (phase.symbol == null) View.GONE else View.VISIBLE
        badge.text = phase.symbol
        (badge.background as GradientDrawable).setColor(phase.color)
        titleView.text = title
        setDetail(detail)
        stoppable = phase.stoppable
        stopButton.visibility = if (stoppable) View.VISIBLE else View.GONE
        feedbackButton.visibility = if (stoppable) View.VISIBLE else View.GONE
        applyTouchability()

        val hideAfter = when (phase) {
            Phase.SUCCESS -> SUCCESS_HIDE_MS
            Phase.NEEDS_INPUT -> NEEDS_INPUT_HIDE_MS
            Phase.ERROR -> ERROR_HIDE_MS
            Phase.STOPPED -> STOPPED_HIDE_MS
            else -> null
        }
        hideAfter?.let { handler.postDelayed(hideRunnable, it) }
    }

    fun updateDetail(detail: String?): Unit = onMain {
        if (attached) setDetail(detail)
    }

    fun setPassThrough(enabled: Boolean): Unit = onMain {
        passThrough = enabled
        applyTouchability()
    }

    private fun applyTouchability() {
        val view = root ?: return
        val touchable = (stoppable || feedbackInputRow.visibility == View.VISIBLE) && !passThrough
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

    fun showInlineInput(): Unit = onMain {
        val rootContainer = root ?: return@onMain
        if (feedbackInputRow.visibility != View.VISIBLE) {
            feedbackInputRow.visibility = View.VISIBLE
            savedYBeforeInput = params.y
            val displayHeight = displayMetrics.heightPixels
            // If the pill is in the lower 65% of the screen, move it up above the soft keyboard
            if (params.y > displayHeight * 0.35) {
                params.y = (displayHeight * 0.18).toInt()
            }
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            applyTouchability()
            try {
                windowManager.updateViewLayout(rootContainer, params)
            } catch (e: Exception) {
            }
            feedbackEditText.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(feedbackEditText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun toggleFeedbackInput() {
        if (feedbackInputRow.visibility == View.VISIBLE) {
            hideFeedbackInput()
        } else {
            showInlineInput()
        }
    }

    private fun hideFeedbackInput() {
        val rootContainer = root ?: return
        feedbackInputRow.visibility = View.GONE
        feedbackEditText.setText("")
        if (savedYBeforeInput != -1) {
            params.y = savedYBeforeInput
            savedYBeforeInput = -1
        }
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        applyTouchability()
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(feedbackEditText.windowToken, 0)
            windowManager.updateViewLayout(rootContainer, params)
        } catch (e: Exception) {
        }
    }

    private fun submitInlineFeedback() {
        val text = feedbackEditText.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            hideFeedbackInput()
            if (!CommandBridge.deliverAnswer(text)) {
                onFeedbackSubmit?.invoke(text)
            }
        }
    }

    private fun buildView(): LinearLayout {
        val maxTextWidth = (displayMetrics.widthPixels * 0.72).toInt()

        spinner = ProgressBar(context).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        }
        badge = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFF202124.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val iconBox = FrameLayout(context).apply {
            addView(spinner)
            addView(badge)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) }
        }

        titleView = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = maxTextWidth
        }
        detailView = TextView(context).apply {
            setTextColor(0xFFCFD3D8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = maxTextWidth
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(detailView)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        feedbackButton = TextView(context).apply {
            text = "💬"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            contentDescription = "Feedback"
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(8) }
            setOnClickListener {
                toggleFeedbackInput()
                onFeedback?.invoke()
            }
        }

        stopButton = TextView(context).apply {
            text = "■"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            contentDescription = "Stop"
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(8) }
            setOnClickListener { onStop?.invoke() }
        }

        val mainPillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconBox)
            addView(texts)
            addView(feedbackButton)
            addView(stopButton)
        }

        feedbackEditText = EditText(context).apply {
            hint = "Feedback / 搜尋錯誤或新增操作..."
            setHintTextColor(0x88FFFFFF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = null
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sendFeedbackButton = TextView(context).apply {
            text = "▶"
            gravity = Gravity.CENTER
            setTextColor(0xFF8AB4F8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(8), 0, dp(4), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { submitInlineFeedback() }
        }

        feedbackInputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
            addView(feedbackEditText)
            addView(sendFeedbackButton)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xEB202124.toInt())
            }
            elevation = dp(6).toFloat()
            addView(mainPillRow)
            addView(feedbackInputRow)
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { v, event ->
            if (passThrough) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                        userHasDragged = true
                        params.x = (initialX + dx).coerceIn(0, displayMetrics.widthPixels - v.width.coerceAtLeast(dp(100)))
                        params.y = (initialY + dy).coerceIn(0, displayMetrics.heightPixels - v.height.coerceAtLeast(dp(50)))
                        try {
                            windowManager.updateViewLayout(v, params)
                        } catch (e: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        return container
    }

    private fun updatePosition(view: View) {
        if (!userHasDragged && savedYBeforeInput == -1) {
            params.x = (displayMetrics.widthPixels - view.width.coerceAtLeast(dp(200))) / 2
            params.y = displayMetrics.heightPixels - navigationBarHeight() - dp(80)
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
            }
        }
    }

    private fun navigationBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars() or
                    WindowInsets.Type.displayCutout()
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
