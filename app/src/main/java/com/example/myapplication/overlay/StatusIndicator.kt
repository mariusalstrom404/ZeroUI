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
 * Floating pill at the top of the screen that tells the user what ZeroUI is doing right now
 * ("Understanding…", "Looking for “Cart”", "Done", errors).
 *
 * It is an accessibility overlay marked NOT_TOUCHABLE, so the taps and swipes ZeroUI dispatches
 * while automating pass straight through it. The text is a polite live region, so TalkBack users
 * hear status changes too. All methods may be called from any thread.
 */
class StatusIndicator(private val context: Context, private val windowManager: WindowManager) {

    enum class Phase(val color: Int, val symbol: String?) {
        UNDERSTANDING(0xFFC58AF9.toInt(), null),
        WORKING(0xFF8AB4F8.toInt(), null),
        SUCCESS(0xFF81C995.toInt(), "✓"),
        NEEDS_INPUT(0xFFFDD663.toInt(), "?"),
        ERROR(0xFFF28B82.toInt(), "!")
    }

    companion object {
        private const val SUCCESS_HIDE_MS = 3000L
        private const val NEEDS_INPUT_HIDE_MS = 5000L
        private const val ERROR_HIDE_MS = 6000L
        private const val FADE_MS = 200L
        private const val TOP_MARGIN_DP = 8
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val hideRunnable = Runnable { hide() }

    private var root: LinearLayout? = null
    private lateinit var spinner: ProgressBar
    private lateinit var badge: TextView
    private lateinit var titleView: TextView
    private lateinit var detailView: TextView
    private var attached = false
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
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = statusBarHeight() + dp(TOP_MARGIN_DP)
    }

    /** Shows [phase] with a [title] and optional [detail]. Final phases hide themselves after a few seconds. */
    fun show(phase: Phase, title: String, detail: String? = null): Unit = onMain {
        val view = ensureAttached() ?: return@onMain
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

        val hideAfter = when (phase) {
            Phase.SUCCESS -> SUCCESS_HIDE_MS
            Phase.NEEDS_INPUT -> NEEDS_INPUT_HIDE_MS
            Phase.ERROR -> ERROR_HIDE_MS
            else -> null
        }
        hideAfter?.let { handler.postDelayed(hideRunnable, it) }
    }

    /** Updates only the second line (e.g. the automation step), keeping the current phase and title. */
    fun updateDetail(detail: String?): Unit = onMain {
        if (attached) setDetail(detail)
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

            // Keep clear of the status bar / display cutout when drawn edge-to-edge.
            setOnApplyWindowInsetsListener { v, insets ->
                val top = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(
                        android.view.WindowInsets.Type.systemBars() or
                            android.view.WindowInsets.Type.displayCutout()
                    ).top
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }
                val wanted = top + dp(TOP_MARGIN_DP)
                if (params.y != wanted && attached) {
                    params.y = wanted
                    windowManager.updateViewLayout(v, params)
                }
                insets
            }
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    /** Initial guess until the first insets pass arrives. */
    @android.annotation.SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24)
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }
}
