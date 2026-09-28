package com.tva.teachable.a11y

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.tva.teachable.AppState
import com.tva.teachable.ListenMode
import com.tva.teachable.voice.VoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Floating control panel drawn as an accessibility overlay (no extra permission). The panel can be dragged anywhere
 * over any app and snaps to the nearest edge; its position survives restarts. The mic button carries two modes —
 * press-and-hold (records while held) and double-tap (hands-free until the next tap) — plus the teach indicator,
 * the Done button while teaching and Yes/No while a sensitive step awaits confirmation.
 */
class Overlay(private val service: AccessibilityService, private val scope: CoroutineScope) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val dp = service.resources.displayMetrics.density
    private val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val slop = ViewConfiguration.get(service).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private val micBg = GradientDrawable().apply { cornerRadius = 14 * dp }
    private val panelBg = GradientDrawable().apply { cornerRadius = 16 * dp }

    // drag state
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    // mic gesture state
    private var holding = false
    private var lastTapAt = 0L
    private val startHold = Runnable { holding = true; VoiceService.instance?.pttStart(ListenMode.HOLD) }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val status = TextView(service).apply {
            setTextColor(Color.WHITE); textSize = 12f; maxWidth = (220 * dp).toInt()
            setOnTouchListener { _, e -> onDragOnly(e) } // second drag handle, so the panel moves without touching the mic
        }
        val mic = Button(service).apply {
            isAllCaps = false; background = micBg; setTextColor(Color.WHITE); isSingleLine = true
            minWidth = (150 * dp).toInt()
            setOnTouchListener { _, e -> onMicTouch(e) }
        }
        val done = Button(service).apply {
            text = "Done teaching"; isAllCaps = false; visibility = View.GONE
            setOnClickListener { VoiceService.instance?.finishTeaching() }
        }
        val yes = Button(service).apply { text = "Yes"; isAllCaps = false; setOnClickListener { VoiceService.instance?.answerConfirm(true) } }
        val no = Button(service).apply { text = "No"; isAllCaps = false; setOnClickListener { VoiceService.instance?.answerConfirm(false) } }
        val confirmRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; addView(yes); addView(no)
        }

        root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            background = panelBg
            addView(status); addView(mic); addView(done); addView(confirmRow)
        }
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, screen().first) // clamped to the right edge on first layout
            y = prefs.getInt(KEY_Y, (screen().second * 0.62f).toInt())
        }
        wm.addView(root, lp)
        // The panel grows and shrinks with its state (teach/confirm rows), so re-hug the edge whenever it is re-measured.
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (!dragging && (r - l != or_ - ol || b - t != ob - ot)) root.post { snap(persist = false) }
        }

        scope.launch(Dispatchers.Main) {
            var wasTeaching = false
            combine(AppState.status, AppState.teaching, AppState.confirmPrompt, AppState.listen) { s, t, c, l -> arrayOf(s, t, c, l) }
                .collect { (s, t, c, l) ->
                    val teaching = t == true
                    status.text = if (teaching) "● TEACH MODE" else "$s"
                    mic.visibility = if (teaching) View.GONE else View.VISIBLE
                    done.visibility = if (teaching) View.VISIBLE else View.GONE
                    confirmRow.visibility = if (c != null) View.VISIBLE else View.GONE
                    panelBg.setColor(if (teaching) TEACH_PANEL else IDLE_PANEL)
                    panelBg.setStroke(if (teaching) (2 * dp).toInt() else 0, TEACH_RING)
                    renderMic(mic, l as ListenMode, teaching)
                    // While teaching the panel shrinks to a label + Done and parks on the left edge, mid-screen: apps
                    // put their search, ADD and cart controls along the top, right and bottom, and a demo tap that
                    // lands on the panel never reaches the app (or the Recorder). Restore the user's spot after.
                    if (teaching && !wasTeaching) move(0, (screen().second * 0.55f).toInt())
                    else if (!teaching && wasTeaching) move(prefs.getInt(KEY_X, screen().first), prefs.getInt(KEY_Y, (screen().second * 0.62f).toInt()))
                    wasTeaching = teaching
                }
        }
    }

    fun hide() { handler.removeCallbacks(startHold); runCatching { wm.removeView(root) } }

    /** Re-adds the panel so it sits above an overlay added later (the teach-mode TouchCatcher). */
    fun raise() = handler.post { if (::root.isInitialized && root.isAttachedToWindow) { wm.removeView(root); wm.addView(root, lp) } }

    // ---- mic button: hold vs double-tap vs drag ----

    /**
     * A press only becomes a recording after [HOLD_MS], which is what separates press-and-hold from the quick taps of
     * a double-tap; any movement past the touch slop before that turns the gesture into a drag instead.
     */
    private fun onMicTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginDrag(e); holding = false
                handler.postDelayed(startHold, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !holding && hypot(e.rawX - downRawX, e.rawY - downRawY) > slop) {
                    dragging = true; handler.removeCallbacks(startHold)
                }
                if (dragging) move(startX + (e.rawX - downRawX).toInt(), startY + (e.rawY - downRawY).toInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(startHold)
                when {
                    dragging -> endDrag()
                    holding -> { holding = false; VoiceService.instance?.pttStop() }
                    e.actionMasked == MotionEvent.ACTION_UP -> onTap()
                }
            }
        }
        return true
    }

    private fun onTap() {
        val vs = VoiceService.instance ?: return
        if (AppState.listen.value == ListenMode.HANDS_FREE) { lastTapAt = 0; vs.pttStop(); return }
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt <= DOUBLE_TAP_MS) { lastTapAt = 0; vs.pttStart(ListenMode.HANDS_FREE) } else lastTapAt = now
    }

    // ---- dragging ----

    private fun onDragOnly(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginDrag(e)
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.rawX - downRawX, e.rawY - downRawY) > slop) dragging = true
                if (dragging) move(startX + (e.rawX - downRawX).toInt(), startY + (e.rawY - downRawY).toInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) endDrag()
        }
        return true
    }

    private fun beginDrag(e: MotionEvent) {
        downRawX = e.rawX; downRawY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
    }

    private fun endDrag() { dragging = false; snap(persist = true) }

    /** Snaps to whichever side edge is closer — a bubble parked mid-screen would cover the app under test. */
    private fun snap(persist: Boolean) {
        val w = screen().first
        move(if (lp.x + root.width / 2 < w / 2) 0 else w - root.width, lp.y)
        if (persist) prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
    }

    private fun move(x: Int, y: Int) {
        val (w, h) = screen()
        lp.x = x.coerceIn(0, maxOf(0, w - root.width))
        lp.y = y.coerceIn(0, maxOf(0, h - root.height))
        runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun screen(): Pair<Int, Int> =
        service.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    // ---- visual state ----

    private fun renderMic(mic: Button, mode: ListenMode, teaching: Boolean) {
        val (label, color) = when {
            mode == ListenMode.HOLD -> "🔴 Release to send" to HOLD_COLOR
            mode == ListenMode.HANDS_FREE -> "🟢 Tap to send" to HANDS_FREE_COLOR
            teaching -> "🎤 Hold · 2-tap" to TEACH_MIC
            else -> "🎤 Hold · 2-tap" to IDLE_COLOR
        }
        mic.text = label
        micBg.setColor(color)
        micBg.setStroke(if (mode == ListenMode.IDLE) 0 else (2 * dp).toInt(), Color.WHITE)
        if (mode == ListenMode.HANDS_FREE) {
            if (mic.animation == null) mic.startAnimation(AlphaAnimation(1f, 0.5f).apply {
                duration = PULSE_MS; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE
            })
        } else mic.clearAnimation()
    }

    private companion object {
        const val PREFS = "tva_overlay"
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val HOLD_MS = 220L
        const val DOUBLE_TAP_MS = 450L
        const val PULSE_MS = 700L
        val IDLE_PANEL = Color.argb(220, 20, 22, 30)
        val TEACH_PANEL = Color.argb(235, 70, 40, 5)
        val TEACH_RING = Color.rgb(255, 170, 40)
        val IDLE_COLOR = Color.rgb(45, 108, 223)
        val HOLD_COLOR = Color.rgb(217, 52, 43)
        val HANDS_FREE_COLOR = Color.rgb(30, 158, 90)
        val TEACH_MIC = Color.rgb(200, 120, 20)
    }
}
