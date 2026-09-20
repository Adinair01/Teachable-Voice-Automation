package com.tva.teachable.a11y

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.tva.teachable.AppState
import com.tva.teachable.voice.VoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Floating control panel drawn as an accessibility overlay (no extra permission): hold-to-talk mic, teach indicator,
 * Done button while teaching, and Yes/No while a sensitive step awaits confirmation.
 */
class Overlay(private val service: AccessibilityService, private val scope: CoroutineScope) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val dp = service.resources.displayMetrics.density
    private lateinit var root: LinearLayout

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val status = TextView(service).apply { setTextColor(Color.WHITE); textSize = 12f; maxWidth = (220 * dp).toInt() }
        val mic = Button(service).apply {
            text = "🎤 Hold to talk"; isAllCaps = false
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> VoiceService.instance?.pttStart()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> VoiceService.instance?.pttStop()
                }
                true
            }
        }
        val done = Button(service).apply { text = "Done teaching"; isAllCaps = false; setOnClickListener { VoiceService.instance?.finishTeaching() } }
        val yes = Button(service).apply { text = "Yes"; isAllCaps = false; setOnClickListener { VoiceService.instance?.answerConfirm(true) } }
        val no = Button(service).apply { text = "No"; isAllCaps = false; setOnClickListener { VoiceService.instance?.answerConfirm(false) } }
        val confirmRow = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; addView(yes); addView(no) }

        root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(Color.argb(220, 20, 22, 30)) }
            addView(status); addView(mic); addView(done); addView(confirmRow)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = (8 * dp).toInt(); y = (96 * dp).toInt() }
        wm.addView(root, lp)

        scope.launch(Dispatchers.Main) {
            combine(AppState.status, AppState.teaching, AppState.confirmPrompt, AppState.listening) { s, t, c, l -> arrayOf(s, t, c, l) }.collect { (s, t, c, l) ->
                status.text = (if (t == true) "● TEACH MODE\n" else "") + s
                done.visibility = if (t == true) View.VISIBLE else View.GONE
                confirmRow.visibility = if (c != null) View.VISIBLE else View.GONE
                mic.text = if (l == true) "🔴 Listening…" else "🎤 Hold to talk"
            }
        }
    }

    fun hide() { runCatching { wm.removeView(root) } }
}
