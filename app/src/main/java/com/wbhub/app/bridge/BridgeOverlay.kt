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
) {

    /** Panel opacity; changing it repaints the panel immediately. */
    var panelOpacity: Float = panelOpacity
        set(value) {
            field = value
            applyBackground()
        }

    /** Whether dragging is disabled; toggled live from the settings screen. */
    var locked: Boolean = locked

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

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#3A4A63"))
            }
        }

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

        root.addView(header)
        root.addView(detailView)
        root.addView(logView)

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
            // behind it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // A previously dragged position wins, so the panel reappears where
            // the user left it.
            val (savedX, savedY) = BridgeSettings.position(context)
            x = if (savedX >= 0) savedX else dp(8)
            y = if (savedY >= 0) savedY else dp(64)
        }
        params = lp

        applyBackground()
        runCatching { wm.addView(root, lp) }
            .onFailure { BridgeStatus.recordError("悬浮窗添加失败：${it.message?.take(50)}") }

        attachDrag()
        view = root
        refresh()
        handler.postDelayed(ticker, REFRESH_MS)
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
                    if (!locked) {
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
                    // out of the way; only an actual drag records the position.
                    if (!moved) {
                        toggleExpanded()
                    } else if (!locked) {
                        BridgeSettings.setPosition(context, lp.x, lp.y)
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
        val a = (alpha.coerceIn(0.15f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_MS = 1000L
    }
}
