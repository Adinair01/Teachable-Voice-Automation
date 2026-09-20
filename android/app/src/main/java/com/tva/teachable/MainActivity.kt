package com.tva.teachable

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tva.teachable.a11y.TvaAccessibilityService
import com.tva.teachable.net.BackendConfig
import com.tva.teachable.voice.VoiceService
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var flows: TextView
    private lateinit var log: TextView

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startVoice() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status); flows = findViewById(R.id.flows); log = findViewById(R.id.log)
        val url = findViewById<EditText>(R.id.backendUrl).apply { setText(BackendConfig.url(this@MainActivity)) }
        val typed = findViewById<EditText>(R.id.typed)
        findViewById<Button>(R.id.openA11y).setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<Button>(R.id.startVoice).setOnClickListener {
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
        findViewById<Button>(R.id.saveUrl).setOnClickListener {
            BackendConfig.setUrl(this, url.text.toString()); stopService(Intent(this, VoiceService::class.java)); startVoice()
        }
        findViewById<Button>(R.id.send).setOnClickListener {
            typed.text.toString().trim().takeIf { it.isNotEmpty() }?.let { VoiceService.instance?.sendCommand(it); typed.text.clear() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(AppState.connected, AppState.status, AppState.log) { c, s, l -> Triple(c, s, l) }.collect { (c, s, l) ->
                    status.text = "Accessibility: ${if (TvaAccessibilityService.instance != null) "ENABLED" else "not enabled"}\nBackend: ${if (c) "connected" else "offline"}\nStatus: $s"
                    log.text = l.takeLast(25).joinToString("\n")
                }
            }
        }
    }

    override fun onResume() { super.onResume(); refreshFlows() }

    private fun startVoice() { ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java)); refreshFlows() }

    private fun refreshFlows() = lifecycleScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("${BackendConfig.url(this@MainActivity)}/api/flows").openConnection() as HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 5000
                val arr = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("flows")
                (0 until arr.length()).joinToString("\n") { "• ${arr.getJSONObject(it).getString("description")}" }.ifEmpty { "none yet" }
            }.getOrDefault("(backend unreachable)")
        }
        flows.text = text
    }
}
