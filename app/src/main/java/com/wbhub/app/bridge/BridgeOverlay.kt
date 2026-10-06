package com.wbhub.app.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A small always-visible panel showing the bridge's state.
 *
 * It is a real, readable window rather than a hidden one-pixel view: a window
 * the user can see is what keeps the process at visible importance, which is
 * what stops the system freezing it while it serves requests.
 *
 * Dragging is handled with raw touch deltas. A move threshold separates a drag
 * from a tap, so the panel can be repositioned without accidentally toggling
 * its detail view.
 */
class BridgeOverlay(
    private val context: Context,
    private val onClose: () -> Unit,
    panelOpacity: Float = 0.94f,
    /** When locked the panel cannot be dragged, so it stays where it was put. */
    locked: Boolean = false,
    /** Whether the panel is replaced by a non-interactive dot. */
    private var stealth: Boolean = false,
) {

    /**
     * Panel opacity; changing it repaints the panel immediately.
     *
     * Ignored in stealth mode: the dot derives its alpha from its own colour so
     * one setting does not fight the other.
     */
    var panelOpacity: Float = panelOpacity
        set(value) {
            field = value
            if (!stealth) applyBackground()
        }

    /** Whether dragging is disabled; toggled live from the settings screen. */
    var locked: Boolean = locked

    /**
     * Whether the overlay is a dot.
     *
     * Switching rebuilds the window rather than hiding views: the touchability
     * flag is a property of the window, so it can only be changed by removing
     * and re-adding it.
     */
    var stealthMode: Boolean = stealth
        set(value) {
            if (field == value) return
            field = value
            if (view != null) {
                hide()
                show()
            }
        }

    /**
     * Whether the dot is temporarily accepting touches so it can be moved.
     *
     * A passthrough dot cannot be dragged, which would leave its position
     * unchangeable, so adjustment is a mode the user enters and leaves rather
     * than something always available.
     */
    var adjusting: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (stealthMode && view != null) {
                hide()
                show()
            }
        }

    /** Dot appearance, read at build time so a change needs a rebuild. */
    var dotColor: Int = BridgeSettings.DEFAULT_DOT_COLOR
    var dotSize: Int = BridgeSettings.DEFAULT_DOT_SIZE
    var dotShape: DotShape = DotShape.FILLED
    /**
     * Dot opacity. Zero is meaningful: the window stays and keeps the process
     * visible while showing nothing at all.
     */
    var dotAlpha: Float = BridgeSettings.DEFAULT_DOT_ALPHA

    /** Rebuilds the dot when its appearance changes. */
    fun refreshAppearance(color: Int, size: Int, shape: DotShape, alpha: Float) {
        val changed = color != dotColor || size != dotSize || shape != dotShape || alpha != dotAlpha
        dotColor = color
        dotSize = size
        dotShape = shape
        dotAlpha = alpha
        if (changed && stealthMode && view != null) {
            hide()
            show()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var titleView: TextView
    private lateinit var detailView: TextView
    private lateinit var logView: TextView
    private lateinit var root: LinearLayout

    /** Whether the panel shows its request line; tapping toggles it. */
    private var expanded = true

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    /** Last request count seen, so a change can be turned into a blink. */
    private var lastSeenCount = 0

    /**
     * Flashes the dot when a call has been served.
     *
     * The dot reports nothing on its own, so a blink is the only signal it can
     * give that the bridge is doing something; without it a working endpoint and
     * a stalled one look identical.
     */
    private fun blinkIfCalled() {
        val count = BridgeStatus.requestCount.get()
        if (count == lastSeenCount) return
        lastSeenCount = count
        val dot = view ?: return
        // Fade relative to the configured opacity so the blink reads the same
        // whether the dot is opaque or faint; at zero there is nothing to fade
        // and the blink rises towards half opacity instead, so the signal is
        // still there for someone who chose an invisible dot.
        val peak = if (dotAlpha <= 0.01f) 0.5f else dotAlpha
        val trough = if (dotAlpha <= 0.01f) 0f else dotAlpha * 0.15f
        val animation = android.animation.ObjectAnimator
            .ofFloat(dot, "alpha", peak, trough, peak)
            .apply { duration = BLINK_MS }
        animation.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val content: View = if (stealthMode) buildDot() else buildPanel()
        root = LinearLayout(context).apply { addView(content) }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Not focusable, so the panel never takes input away from the app
            // behind it. The dot goes further and declares itself untouchable,
            // which is what lets a tap land on whatever is underneath — except
            // while it is being moved, when it has to accept the drag.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                if (stealthMode && !adjusting) {
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    0
                },
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // A previously dragged position wins, so the window reappears where
            // the user left it. The dot keeps its own coordinates: the two are
            // different sizes and want different corners.
            val (savedX, savedY) = if (stealthMode) {
                BridgeSettings.dotPosition(context).takeIf { it.first >= 0 || it.second >= 0 }
                    ?: BridgeSettings.position(context)
            } else {
                BridgeSettings.position(context)
            }
            x = if (savedX >= 0) savedX else dp(8)
            y = if (savedY >= 0) savedY else dp(64)
        }
        params = lp

        if (!stealthMode) applyBackground()
        runCatching { wm.addView(root, lp) }
            .onFailure { BridgeStatus.recordError("悬浮窗添加失败：${it.message?.take(50)}") }

        // Nothing to drag on a passthrough dot; a panel always drags, and the
        // dot drags only while it is being adjusted.
        if (!stealthMode || adjusting) attachDrag()
        view = root
        if (!stealthMode) refresh()
        // A dot is polled faster: its only job is to notice a call, and a
        // one-second poll would delay the blink by up to that much.
        handler.postDelayed(ticker, if (stealthMode) DOT_REFRESH_MS else REFRESH_MS)
    }

    /** The full status panel. */
    private fun buildPanel(): View {
        titleView = TextView(context).apply {
            setTextColor(Color.parseColor("#7FE3A0"))
            textSize = 12f
        }
        detailView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
        }
        logView = TextView(context).apply {
            setTextColor(Color.parseColor("#B9C4D4"))
            textSize = 10f
            maxLines = 2
        }
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FF8A80"))
            textSize = 12f
            setPadding(dp(10), dp(2), 0, 0)
            setOnClickListener { onClose() }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close)

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#3A4A63"))
            }
            addView(header)
            addView(detailView)
            addView(logView)
        }
    }

    /**
     * The stealth indicator: a plain shape with no text and no handler.
     *
     * Its whole purpose is to keep the process visible, so it deliberately
     * carries no interaction at all.
     */
    private fun buildDot(): View {
        val side = dp(dotSize)
        val stroke = dp(if (dotSize >= 20) 3 else 2)
        val color = withAlpha(dotColor, dotAlpha)
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when (dotShape) {
                DotShape.FILLED -> setColor(color)
                DotShape.RING -> {
                    setColor(Color.TRANSPARENT)
                    setStroke(stroke, color)
                }
            }
        }
        return View(context).apply {
            background = drawable
            layoutParams = LinearLayout.LayoutParams(side, side)
        }
    }

    /** Drag to move, tap to toggle the detail line. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        root.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            // The lock is read per gesture rather than at bind time, so
            // toggling it in the settings screen takes effect immediately.
            // A locked panel still consumes touches: letting them through would
            // make the panel a hole the app behind it could be tapped through.
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // A locked panel stays put; a dot being adjusted ignores the
                    // lock, since the user explicitly asked to move it.
                    val canDrag = !locked || (stealthMode && adjusting)
                    if (canDrag) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                        if (moved) {
                            lp.x = startX + dx.roundToInt()
                            lp.y = startY + dy.roundToInt()
                            runCatching { windowManager?.updateViewLayout(root, lp) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // A tap collapses the panel to its title so it can be tucked
                    // out of the way; a dot has nothing to collapse. Only an
                    // actual drag records the position.
                    if (!moved) {
                        if (!stealthMode) toggleExpanded()
                    } else if (!locked || (stealthMode && adjusting)) {
                        if (stealthMode) {
                            BridgeSettings.setDotPosition(context, lp.x, lp.y)
                        } else {
                            BridgeSettings.setPosition(context, lp.x, lp.y)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleExpanded() {
        expanded = !expanded
        detailView.visibility = if (expanded) View.VISIBLE else View.GONE
        logView.visibility = if (expanded) View.VISIBLE else View.GONE
        refresh()
    }

    fun hide() {
        handler.removeCallbacks(ticker)
        val v = view ?: return
        runCatching { windowManager?.removeView(v) }
        view = null
    }

    fun isShowing(): Boolean = view != null

    private fun refresh() {
        // The dot has nothing to report, and its text views were never built.
        if (stealthMode) {
            blinkIfCalled()
            return
        }
        val running = BridgeStatus.running
        titleView.text = if (running) "● 转发中" else "○ 已停止"
        titleView.setTextColor(
            if (running) Color.parseColor("#7FE3A0") else Color.parseColor("#FF8A80"),
        )
        if (!expanded) return

        detailView.text = if (running) {
            "端口 ${BridgeStatus.port} · 已服务 ${BridgeStatus.requestCount.get()} 次"
        } else {
            "服务未运行"
        }
        logView.text = when {
            BridgeStatus.lastError.isNotEmpty() -> "⚠ ${BridgeStatus.lastError}"
            BridgeStatus.lastRequest.isNotEmpty() -> "↳ ${BridgeStatus.lastRequest}"
            else -> "等待请求…"
        }
        logView.setTextColor(
            if (BridgeStatus.lastError.isNotEmpty()) {
                Color.parseColor("#FFB4A9")
            } else {
                Color.parseColor("#B9C4D4")
            },
        )
    }

    /** Paints the panel background at the current opacity. */
    private fun applyBackground() {
        val drawable = (root.background as? GradientDrawable) ?: return
        drawable.setColor(withAlpha(Color.parseColor("#1A2233"), panelOpacity))
        root.invalidate()
    }

    /** Applies the configured opacity to a base colour. */
    private fun withAlpha(color: Int, alpha: Float): Int {
        // Zero is a valid choice here, unlike the panel where an invisible panel
        // would defeat its own purpose.
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_MS = 1000L
        const val DOT_REFRESH_MS = 250L
        const val BLINK_MS = 450L
    }
}
