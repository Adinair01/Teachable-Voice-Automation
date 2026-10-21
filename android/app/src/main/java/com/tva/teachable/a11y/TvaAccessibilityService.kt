package com.tva.teachable.a11y

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.tva.teachable.BuildConfig
import com.tva.teachable.core.FlowJson
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.Selector
import com.tva.teachable.core.SelectorMatcher
import com.tva.teachable.net.BackendConfig
import com.tva.teachable.record.Recorder
import com.tva.teachable.replay.BackendElementPicker
import com.tva.teachable.replay.Device
import com.tva.teachable.replay.Replayer
import com.tva.teachable.core.UiNode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class TvaAccessibilityService : AccessibilityService(), Device {
    private lateinit var executor: ActionExecutor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var recorder: Recorder? = null
    private var catcher: TouchCatcher? = null
    // One lane so taps are recorded in the order they were made.
    @OptIn(ExperimentalCoroutinesApi::class) private val recordLane = Dispatchers.Default.limitedParallelism(1)
    private var overlay: Overlay? = null

    override fun onServiceConnected() {
        instance = this
        executor = ActionExecutor(this)
        overlay = Overlay(this, scope).also { it.show() }
        if (BuildConfig.DEBUG) registerDebugReceiver()
        Log.i(TAG, "service connected")
    }

    /** The app window in front (package, activity/dialog class), kept even when not teaching so a teach starts knowing it. */
    private var front = "" to ""

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()
        // App windows only: the launcher and System UI also report window changes as they go to the background.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg.isNotEmpty() && pkg !in notAnApp) {
            front = pkg to event.className?.toString().orEmpty()
        }
        recorder?.onEvent(event)
    }

    /** While teaching, Back is observed (never consumed): right after a tap it undoes that tap (see Recorder.onBack). */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (BuildConfig.DEBUG) Log.d(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} teaching=${recorder != null}")
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) recorder?.let { r -> scope.launch(recordLane) { r.onBack() } }
        return false
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        overlay?.hide()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Device (used by Replayer) ----
    /**
     * Every window of the foreground app, top-most first. Tooltips and coach marks are separate windows: reading only
     * the active one would show the popup and hide the page under it.
     */
    override fun tree(): LiveTree {
        val active = rootInActiveWindow ?: return UiTreeReader.readLive(null)
        val pkg = active.packageName
        val roots = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }.filter { it.packageName == pkg }
        return UiTreeReader.readLive(roots.ifEmpty { listOf(active) })
    }
    override fun launch(packageName: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) // fresh start: replay must begin from the app home
        startActivity(i); return true
    }
    override fun tap(tree: LiveTree, node: UiNode) = executor.tap(tree, node)
    override fun type(tree: LiveTree, node: UiNode, value: String) = executor.type(tree, node, value)
    override fun scroll(tree: LiveTree) = executor.scroll(tree)
    override fun back() = executor.back()

    private val notAnApp by lazy { setOfNotNull(packageName, "com.android.systemui", homePackage()) }
    private fun homePackage() = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName

    fun startRecording(onBoundary: ((com.tva.teachable.core.Boundary) -> Unit)? = null) {
        recorder = Recorder(packageName, setOfNotNull("com.android.systemui", homePackage())).also {
            it.start(); it.touchMode = true; it.onWindowChanged(front.first, front.second); it.onBoundaryReached = onBoundary
        }
        catcher = TouchCatcher(this, ::onTeachTap) { scope.launch(recordLane) { recorder?.onSwipeUp() } }.also { it.show() }
        overlay?.raise() // the mic / Done panel stays above the catcher, so it keeps working normally
    }
    fun stopRecording(): List<FlowStep> {
        catcher?.hide(); catcher = null
        return recorder?.stop().also { recorder = null }.orEmpty()
    }

    /** Reads the screen BEFORE the tap is delivered, records the element under the finger, then lets the tap through. */
    private fun onTeachTap(x: Int, y: Int, deliver: () -> Unit) {
        scope.launch(recordLane) {
            try { if (!overKeyboardOrSystemUi(x, y)) recorder?.onUserTap(tree().nodes, x, y) }
            catch (e: Exception) { Log.e(TAG, "teach: could not record tap", e) }
            finally { deliver() }
        }
    }

    // Keyboard keys are typing (captured as text), status bar / notification shade are not part of the task.
    private fun overKeyboardOrSystemUi(x: Int, y: Int) = windows.any { w ->
        (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || w.type == AccessibilityWindowInfo.TYPE_SYSTEM) &&
            Rect().also { w.getBoundsInScreen(it) }.contains(x, y)
    }

    /** Debug-only adb channel: `adb shell am broadcast -a com.tva.DEBUG --es cmd <dump|guard|check|tap|type|scroll|back|replay|rec_start|rec_stop>`. */
    private fun registerDebugReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val cmd = i.getStringExtra("cmd").orEmpty()
                val sel = Selector(
                    resourceId = i.getStringExtra("id").orEmpty(), text = i.getStringExtra("text").orEmpty(),
                    contentDesc = i.getStringExtra("desc").orEmpty(), className = i.getStringExtra("cls").orEmpty(),
                    near = i.getStringExtra("near").orEmpty(),
                )
                if (cmd == "replay") { debugReplay(i.getStringExtra("file").orEmpty(), i.getStringExtra("slots").orEmpty()); return }
                val result = when (cmd) {
                    "dump" -> dump()
                    "tap" -> withNode(sel, forTap = true) { t, n -> tap(t, n) }.toString()
                    "type" -> withNode(sel, forTap = false) { t, n -> type(t, n, i.getStringExtra("value").orEmpty()) }.toString()
                    "say" -> (com.tva.teachable.voice.VoiceService.instance?.also { it.sendCommand(i.getStringExtra("text").orEmpty()) } != null).toString()
                    // Dry run: what the guard says about the node a tap WOULD hit. Never taps.
                    "check" -> tree().let { t ->
                        val n = SelectorMatcher.target(t.nodes, sel, i.getStringExtra("slot"), forTap = true)
                        n?.let { "#${it.index} ${it.resourceId} ${it.bounds} -> ${com.tva.teachable.core.ScreenGuard.action(t.nodes, it) ?: "allowed"}" } ?: "no target"
                    }
                    "guard" -> tree().nodes.let { n ->
                        "screen=${com.tva.teachable.core.ScreenGuard.screen(n)} readyToPay=${com.tva.teachable.core.ScreenGuard.readyToPay(n)}"
                    }
                    "scroll" -> scroll(tree()).toString()
                    "back" -> back().toString()
                    "rec_start" -> { startRecording(); "recording" }
                    "rec_stop" -> stopRecording().let { s -> File(filesDir, "recorded.json").writeText(FlowJson.toJson(s).toString(2)); "steps=${s.size}" }
                    else -> "unknown cmd"
                }
                writeResult(cmd, result)
            }
        }
        registerReceiver(receiver, IntentFilter("com.tva.DEBUG"), RECEIVER_EXPORTED)
    }

    /** Same target rule as the Replayer (taps skip text inputs, prefer clickable nodes) so manual tests match replay. */
    private fun withNode(sel: Selector, forTap: Boolean, block: (LiveTree, UiNode) -> Boolean): Boolean {
        val t = tree()
        val n = SelectorMatcher.target(t.nodes, sel, null, forTap) ?: return false
        return block(t, n)
    }

    private fun debugReplay(file: String, slotsJson: String) {
        scope.launch {
            val out = try {
                val flow = FlowJson.parse(File(filesDir, file).readText())
                val slots = JSONObject(slotsJson.ifEmpty { "{}" }).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
                val picker = BackendElementPicker(BackendConfig.url(this@TvaAccessibilityService))
                val r = Replayer(this@TvaAccessibilityService, picker).run(flow, slots)
                JSONObject().put("success", r.success).put("steps", JSONArray().also { a ->
                    r.steps.forEach { a.put(JSONObject().put("i", it.index).put("ok", it.ok).put("llm", it.usedLlmFallback).put("err", it.error ?: JSONObject.NULL)) }
                }).toString()
            } catch (e: Exception) { JSONObject().put("success", false).put("error", e.toString()).toString() }
            writeResult("replay", out)
        }
    }

    private fun writeResult(cmd: String, result: String) {
        Log.i(TAG, "cmd=$cmd result=$result")
        File(filesDir, "result.txt").writeText("$cmd:$result")
    }

    private fun dump(): String {
        val nodes = tree().nodes
        val arr = JSONArray()
        nodes.forEach {
            arr.put(JSONObject().put("i", it.index).put("d", it.depth).put("pkg", it.packageName)
                .put("id", it.resourceId).put("text", it.text).put("desc", it.contentDesc)
                .put("cls", it.className).put("b", it.bounds).put("click", it.clickable)
                .put("edit", it.editable).put("scroll", it.scrollable).put("z", it.drawingOrder).put("vis", it.visibleToUser))
        }
        File(filesDir, "dump.json").writeText(arr.toString())
        return "nodes=${nodes.size} pkg=${nodes.firstOrNull()?.packageName}"
    }

    companion object {
        private const val TAG = "TVA"
        @Volatile var instance: TvaAccessibilityService? = null
    }
}
