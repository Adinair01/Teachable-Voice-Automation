package com.tva.teachable.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Teach mode only: an invisible full-screen accessibility overlay that sees the user's finger first. A tap is reported
 * to [onTap] (which reads the tree and records the element under the finger) and then re-delivered to the app with
 * dispatchGesture; swipes and long presses are re-delivered as they were performed. Needed because many apps
 * (Compose lists, dialogs) never send TYPE_VIEW_CLICKED, so event-based recording silently misses those taps.
 * Uses only AccessibilityService APIs: no extra permission, no target-app cooperation.
 */
class TouchCatcher(
    private val service: AccessibilityService,
    private val onTap: (x: Int, y: Int, deliver: () -> Unit) -> Unit,
    private val onSwipeUp: () -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(service).scaledTouchSlop
    private val longPress = ViewConfiguration.getLongPressTimeout().toLong()
    private var view: View? = null
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )
    private val points = ArrayList<Triple<Float, Float, Long>>()

    @SuppressLint("ClickableViewAccessibility")
    fun show() = main.post {
        if (view != null) return@post
        view = View(service).apply { setOnTouchListener { _, e -> onTouch(e); true } }
        wm.addView(view, lp)
    }

    fun hide() = main.post { view?.let { wm.removeView(it) }; view = null }

    private fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { points.clear(); points += Triple(e.rawX, e.rawY, e.eventTime) }
            MotionEvent.ACTION_MOVE -> points += Triple(e.rawX, e.rawY, e.eventTime)
            MotionEvent.ACTION_UP -> { points += Triple(e.rawX, e.rawY, e.eventTime); gesture(points.toList()) }
            MotionEvent.ACTION_CANCEL -> points.clear()
        }
    }

    private fun gesture(p: List<Triple<Float, Float, Long>>) {
        val (x0, y0, t0) = p.first()
        val (x1, y1, t1) = p.last()
        val duration = (t1 - t0).coerceIn(MIN_MS, MAX_MS)
        val path = Path().apply { moveTo(x0, y0); p.drop(1).forEach { (x, y) -> lineTo(x, y) } }
        when {
            hypot(x1 - x0, y1 - y0) >= slop -> {
                if (y1 - y0 < 0 && abs(y1 - y0) > abs(x1 - x0)) onSwipeUp()
                deliver(path, duration)
            }
            t1 - t0 >= longPress -> deliver(path, duration) // long press: passed through, not recorded
            // Our own re-delivered tap caught again (the pass-through flag was not applied yet): send it on, don't record.
            isEcho(x0, y0, t0) -> deliver(path, TAP_MS)
            else -> onTap(x0.toInt(), y0.toInt()) { deliver(path, TAP_MS) }
        }
    }

    private var lastDelivered = Triple(-1f, -1f, 0L)
    private fun isEcho(x: Float, y: Float, t: Long) =
        hypot(x - lastDelivered.first, y - lastDelivered.second) < slop && t - lastDelivered.third < ECHO_MS

    /** Lets the gesture fall through to whatever is underneath, then catches touches again. */
    private fun deliver(path: Path, durationMs: Long) = main.post {
        val v = view ?: return@post
        setTouchable(v, false)
        // Give the window manager a moment to apply the pass-through flag before injecting the gesture.
        main.postDelayed({ dispatch(v, path, durationMs) }, SETTLE_MS)
    }

    private fun dispatch(v: View, path: Path, durationMs: Long) {
        val start = RectF().also { path.computeBounds(it, true) }
        lastDelivered = Triple(start.left, start.top, android.os.SystemClock.uptimeMillis())
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val back = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription) { setTouchable(v, true) }
            override fun onCancelled(d: GestureDescription) { Log.w("TVA", "teach: gesture cancelled"); setTouchable(v, true) }
        }
        if (!service.dispatchGesture(g, back, main)) { Log.w("TVA", "teach: gesture not dispatched"); setTouchable(v, true) }
    }

    private fun setTouchable(v: View, on: Boolean) {
        if (view !== v) return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(v, lp)
    }

    private companion object {
        const val TAP_MS = 60L
        const val MIN_MS = 60L
        const val MAX_MS = 3000L
        const val SETTLE_MS = 80L
        const val ECHO_MS = 800L
    }
}
