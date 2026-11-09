package com.example.myapplication.overlay

import android.annotation.SuppressLint
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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.myapplication.R
import kotlin.math.abs

/**
 * Floating status pill that tells the user what ZeroUI is doing right now ("Understanding…",
 * "Looking for “Cart”", "Done", errors) and lets them answer straight away.
 *
 * - While a command runs it shows a stop button ([onStop]).
 * - Otherwise it shows a mic button ([onMic]) to reply or give feedback by voice, and a close
 *   button ([onDismiss]). In the [Phase.LISTENING] phase the mic button is red and stops listening.
 * - Tapping the text opens the chat ([onOpenChat]), for typing instead.
 * - It can be dragged anywhere; the place is remembered. Until then it sits at the bottom, above
 *   the navigation bar.
 *
 * ZeroUI's own taps and swipes pass through it, because the engine calls [setPassThrough] around
 * each gesture. The text is a polite live region, so TalkBack users hear status changes too.
 * All methods may be called from any thread; callbacks run on the main thread.
 */
class StatusIndicator(private val context: Context, private val windowManager: WindowManager) {

    enum class Phase(val color: Int, val symbol: String?) {
        UNDERSTANDING(0xFFC58AF9.toInt(), null),
        WORKING(0xFF8AB4F8.toInt(), null),
        SUCCESS(0xFF81C995.toInt(), "✓"),
        NEEDS_INPUT(0xFFFDD663.toInt(), "?"),
        ERROR(0xFFF28B82.toInt(), "!"),
        STOPPED(0xFFBDC1C6.toInt(), "■"),
        /** ZeroUI's mic is open for the user's next request. */
        LISTENING(0xFFFF6E6E.toInt(), "●"),
        /** Nothing running; the mic button is there if the user wants to say more. */
        READY(0xFF8AB4F8.toInt(), "…");

        /** Phases where a command is still running and can be stopped. */
        val stoppable: Boolean get() = symbol == null
    }

    var onStop: (() -> Unit)? = null
    var onMic: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onOpenChat: (() -> Unit)? = null

    companion object {
        private const val SUCCESS_HIDE_MS = 3000L
        private const val NEEDS_INPUT_HIDE_MS = 8000L
        private const val ERROR_HIDE_MS = 6000L
        private const val STOPPED_HIDE_MS = 2500L
        private const val READY_HIDE_MS = 10000L
        private const val FADE_MS = 200L
        private const val EDGE_MARGIN_DP = 12
        private const val PREFS = "status_pill"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val hideRunnable = Runnable { hide() }

    private var root: LinearLayout? = null
    private lateinit var spinner: ProgressBar
    private lateinit var badge: TextView
    private lateinit var titleView: TextView
    private lateinit var detailView: TextView
    private lateinit var stopButton: TextView
    private lateinit var micButton: ImageView
    private lateinit var closeButton: TextView
    private var attached = false
    private var phase: Phase? = null
    /** ZeroUI is dispatching a gesture that must not land on the pill. */
    private var passThrough = false
    /** Bumped on every show so a fade-out that started earlier doesn't remove a newer status. */
    private var generation = 0

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    /**
     * Shows [phase] with a [title] and optional [detail]. Final phases hide themselves after a few
     * seconds. With [atTop] the pill sits at the top of the screen instead, unless the user has
     * moved it: e.g. while ZeroUI's dialog is open for an answer, at the bottom it would cover the
     * dialog's text field and mic button.
     */
    fun show(phase: Phase, title: String, detail: String? = null, atTop: Boolean = false): Unit = onMain {
        val view = ensureAttached() ?: return@onMain
        this.phase = phase
        placeWindow(view, atTop)
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

        val listening = phase == Phase.LISTENING
        stopButton.visibility = if (phase.stoppable) View.VISIBLE else View.GONE
        micButton.visibility = if (phase.stoppable) View.GONE else View.VISIBLE
        closeButton.visibility = if (phase.stoppable) View.GONE else View.VISIBLE
        (micButton.background as GradientDrawable).setColor(if (listening) 0xFFE53935.toInt() else 0x33FFFFFF)
        micButton.contentDescription = if (listening) "Stop listening" else "Speak to ZeroUI"
        applyTouchability()

        val hideAfter = when (phase) {
            Phase.SUCCESS -> SUCCESS_HIDE_MS
            Phase.NEEDS_INPUT -> NEEDS_INPUT_HIDE_MS
            Phase.ERROR -> ERROR_HIDE_MS
            Phase.STOPPED -> STOPPED_HIDE_MS
            Phase.READY -> READY_HIDE_MS
            else -> null
        }
        hideAfter?.let { handler.postDelayed(hideRunnable, it) }
    }

    /** Updates only the second line (e.g. the automation step), keeping the current phase and title. */
    fun updateDetail(detail: String?): Unit = onMain {
        if (attached) setDetail(detail)
    }

    /** The phase on screen, or null when the pill is hidden. */
    val currentPhase: Phase? get() = if (attached) phase else null

    /**
     * While [enabled], the pill ignores touches so a tap or swipe ZeroUI dispatches underneath it
     * (e.g. a bottom "Add to cart" bar) reaches the app instead of the pill.
     */
    fun setPassThrough(enabled: Boolean): Unit = onMain {
        passThrough = enabled
        applyTouchability()
    }

    private fun applyTouchability() {
        val flags = if (passThrough) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (flags == params.flags) return
        params.flags = flags
        relayout()
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
        phase = null
    }

    private fun relayout() {
        val view = root ?: return
        if (!attached) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
        }
    }

    // --- Position ---

    /** Where the user dragged the pill to, as its top-left corner, or null if never moved. */
    private fun savedPosition(): Pair<Int, Int>? =
        if (prefs.contains("x")) prefs.getInt("x", 0) to prefs.getInt("y", 0) else null

    private fun placeWindow(view: View, atTop: Boolean) {
        val saved = savedPosition()
        if (saved != null) {
            params.gravity = Gravity.TOP or Gravity.START
            val (x, y) = clamp(view, saved.first, saved.second)
            params.x = x
            params.y = y
        } else {
            params.gravity = (if (atTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            params.x = 0
            params.y = if (atTop) topOffset() else bottomOffset()
        }
        relayout()
    }

    /** Keeps the pill fully on screen, e.g. after rotating or when its text got longer. */
    private fun clamp(view: View, x: Int, y: Int): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val width = view.width.takeIf { it > 0 } ?: dp(200)
        val height = view.height.takeIf { it > 0 } ?: dp(56)
        return x.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0)) to
            y.coerceIn(topOffset() - dp(EDGE_MARGIN_DP), (metrics.heightPixels - height).coerceAtLeast(0))
    }

    /** Drags the pill with the finger; a touch that doesn't move is a tap, which opens the chat. */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragHandler() = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    // Switches to top-left coordinates, so moving is a plain offset from where it is now.
                    val location = IntArray(2).also { view.getLocationOnScreen(it) }
                    startX = location[0]
                    startY = location[1]
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        handler.removeCallbacks(hideRunnable)
                        params.gravity = Gravity.TOP or Gravity.START
                    }
                    if (dragging) {
                        val (x, y) = clamp(view, startX + dx.toInt(), startY + dy.toInt())
                        params.x = x
                        params.y = y
                        relayout()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                        // Moving it counts as looking at it: final phases get their full time again.
                        phase?.let { if (!it.stoppable && it != Phase.LISTENING) handler.postDelayed(hideRunnable, READY_HIDE_MS) }
                    } else {
                        view.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }

    // --- Views ---

    private fun buildView(): LinearLayout {
        val maxTextWidth = (context.resources.displayMetrics.widthPixels * 0.55).toInt()

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

        stopButton = roundButton("■", "Stop") { onStop?.invoke() }
        micButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            contentDescription = "Speak to ZeroUI"
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(12) }
            setOnClickListener { onMic?.invoke() }
        }
        closeButton = roundButton("✕", "Dismiss") { onDismiss?.invoke() }.apply {
            (background as GradientDrawable).setColor(0x00000000)
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(4) }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xEB202124.toInt())
            }
            elevation = dp(6).toFloat()
            addView(iconBox)
            addView(texts)
            addView(stopButton)
            addView(micButton)
            addView(closeButton)
            contentDescription = null
            setOnClickListener { onOpenChat?.invoke() }
            setOnTouchListener(dragHandler())
        }
    }

    private fun roundButton(symbol: String, description: String, onClick: () -> Unit) = TextView(context).apply {
        text = symbol
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
        contentDescription = description
        isClickable = true
        isFocusable = true
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(12) }
        setOnClickListener { onClick() }
    }

    /**
     * Distance from the bottom edge that keeps the pill clear of the navigation bar (3-button bar
     * or gesture handle). Read from the display's metrics rather than this window's own insets:
     * once the pill sits above the bar its own bottom inset is 0, which would make it oscillate.
     */
    private fun bottomOffset(): Int = navigationBarHeight() + dp(EDGE_MARGIN_DP)

    /** Distance from the top edge that keeps the pill clear of the status bar and camera cutout. */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun topOffset(): Int {
        val bar = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()
            ).top
        } else {
            val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24)
        }
        return bar + dp(EDGE_MARGIN_DP)
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
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
