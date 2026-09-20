package com.tva.teachable.a11y

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.tva.teachable.BuildConfig
import com.tva.teachable.core.Selector
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class TvaAccessibilityService : AccessibilityService() {
    private lateinit var executor: ActionExecutor

    override fun onServiceConnected() {
        instance = this
        executor = ActionExecutor(this)
        if (BuildConfig.DEBUG) registerDebugReceiver()
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) { /* recorder hooks in on Day 2 */ }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun snapshot() = rootInActiveWindow?.let { UiTreeReader.read(it) }.orEmpty()

    /** Debug-only adb command channel: `adb shell am broadcast -a com.tva.DEBUG --es cmd dump|tap|type|scroll|back`. */
    private fun registerDebugReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val sel = Selector(
                    resourceId = i.getStringExtra("id").orEmpty(),
                    text = i.getStringExtra("text").orEmpty(),
                    contentDesc = i.getStringExtra("desc").orEmpty(),
                    className = i.getStringExtra("cls").orEmpty(),
                )
                val result = when (i.getStringExtra("cmd")) {
                    "dump" -> dump()
                    "tap" -> executor.tap(sel).toString()
                    "type" -> executor.type(sel, i.getStringExtra("value").orEmpty()).toString()
                    "scroll" -> executor.scroll().toString()
                    "back" -> executor.back().toString()
                    else -> "unknown cmd"
                }
                Log.i(TAG, "cmd=${i.getStringExtra("cmd")} result=$result")
            }
        }
        val filter = IntentFilter("com.tva.DEBUG")
        registerReceiver(receiver, filter, RECEIVER_EXPORTED)
    }

    private fun dump(): String {
        val nodes = snapshot()
        val arr = JSONArray()
        nodes.forEach {
            arr.put(JSONObject().put("i", it.index).put("d", it.depth).put("pkg", it.packageName)
                .put("id", it.resourceId).put("text", it.text).put("desc", it.contentDesc)
                .put("cls", it.className).put("b", it.bounds).put("click", it.clickable)
                .put("edit", it.editable).put("scroll", it.scrollable))
        }
        File(filesDir, "dump.json").writeText(arr.toString())
        return "nodes=${nodes.size} pkg=${nodes.firstOrNull()?.packageName}"
    }

    companion object {
        private const val TAG = "TVA"
        @Volatile var instance: TvaAccessibilityService? = null
    }
}
