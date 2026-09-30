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
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Floating pill at the bottom of the screen, just above the navigation bar, that tells the user what ZeroUI is doing right now
 * ("Understanding…", "Looking for “Cart”", "Done", errors).
 *
 * While a command runs the pill shows a stop button, which calls [onStop]. Only then is the pill
 * touchable; otherwise it is NOT_TOUCHABLE. ZeroUI's own taps and swipes pass through it either
 * way, because the engine calls [setPassThrough] around each gesture. The text is a polite live
 * region, so TalkBack users hear status changes too. All methods may be called from any thread.
 */
class StatusIndicator(private val context: Context, private val windowManager: WindowManager) {

    enum class Phase(val color: Int, val symbol: String?) {
        UNDERSTANDING(0xFFC58AF9.toInt(), null),
        WORKING(0xFF8AB4F8.toInt(), null),
        SUCCESS(0xFF81C995.toInt(), "✓"),
        NEEDS_INPUT(0xFFFDD663.toInt(), "?"),
        ERROR(0xFFF28B82.toInt(), "!"),
        STOPPED(0xFFBDC1C6.toInt(), "■");

        /** Phases where a command is still running and can be stopped. */
        val stoppable: Boolean get() = symbol == null
    }

    /** Called on the main thread when the user taps the stop button. */
    var onStop: (() -> Unit)? = null

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
    private val hideRunnable = Runnable { hide() }

    private var root: LinearLayout? = null
    private lateinit var spinner: ProgressBar
    private lateinit var badge: TextView
    private lateinit var titleView: TextView
    private lateinit var detailView: TextView
    private lateinit var stopButton: TextView
    private var attached = false
    /** The stop button is showing, so the pill should take touches. */
    private var stoppable = false
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

    /** Shows [phase] with a [title] and optional [detail]. Final phases hide themselves after a few seconds. */
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

    /** Updates only the second line (e.g. the automation step), keeping the current phase and title. */
    fun updateDetail(detail: String?): Unit = onMain {
        if (attached) setDetail(detail)
    }

    /**
     * While [enabled], the pill ignores touches so a tap or swipe ZeroUI dispatches underneath it
     * (e.g. a bottom "Add to cart" bar) reaches the app instead of the stop button.
     */
    fun setPassThrough(enabled: Boolean): Unit = onMain {
        passThrough = enabled
        applyTouchability()
    }

    private fun applyTouchability() {
        val view = root ?: return
        val touchable = stoppable && !passThrough
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
        val maxTextWidth = (context.resources.displayMetrics.widthPixels * 0.72).toInt()

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
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(12) }
            setOnClickListener { onStop?.invoke() }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xEB202124.toInt())
            }
            elevation = dp(6).toFloat()
            addView(iconBox)
            addView(texts)
            addView(stopButton)
        }
    }

    /** Re-applies the bottom offset, e.g. after rotation or a navigation-mode change. */
    private fun updatePosition(view: View) {
        val wanted = bottomOffset()
        if (params.y != wanted) {
            params.y = wanted
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Distance from the bottom edge that keeps the pill clear of the navigation bar (3-button bar
     * or gesture handle). Read from the display's metrics rather than this window's own insets:
     * once the pill sits above the bar its own bottom inset is 0, which would make it oscillate.
     */
    private fun bottomOffset(): Int = navigationBarHeight() + dp(BOTTOM_MARGIN_DP)

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
