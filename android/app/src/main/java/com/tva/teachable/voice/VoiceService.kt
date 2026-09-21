package com.tva.teachable.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.tva.teachable.AppState
import com.tva.teachable.ListenMode
import com.tva.teachable.a11y.TvaAccessibilityService
import com.tva.teachable.core.ConfirmText
import com.tva.teachable.core.FlowJson
import com.tva.teachable.net.BackendClient
import com.tva.teachable.net.BackendConfig
import com.tva.teachable.replay.BackendElementPicker
import com.tva.teachable.replay.ConfirmationGate
import com.tva.teachable.replay.Replayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Foreground (microphone) service: owns the backend socket, push-to-talk, TTS playback and the command loop. */
class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var client: BackendClient
    private lateinit var recorder: PttRecorder
    private lateinit var player: AudioPlayer
    private var teach: JSONObject? = null
    private var expectAudio = false
    private var confirm: CompletableDeferred<Boolean>? = null
    @Volatile private var awaitingConfirmSpeech = false
    private var handsFreeCap: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        player = AudioPlayer(this)
        recorder = PttRecorder(scope) { client.sendAudio(it) }
        client = BackendClient(BackendConfig.wsUrl(this), scope, ::onServer, ::onAudio, ::onConnection)
        client.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice assistant", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL).setContentTitle("Teachable Voice is listening for push-to-talk")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null; recorder.stop(); client.stop(); player.release(); scope.cancel(); super.onDestroy()
    }

    /** Server-side session state dies with the socket, so a reconnect must drop any half-finished teach session too. */
    private fun onConnection(up: Boolean) {
        AppState.connected.value = up
        AppState.log(if (up) "connected" else "disconnected")
        if (up) client.send(JSONObject().put("type", "apps").put("apps", InstalledApps.toJson(this)))
        if (up && teach != null) {
            TvaAccessibilityService.instance?.stopRecording()
            teach = null; AppState.teaching.value = false; AppState.status.value = "Idle"
            AppState.log("teach session cancelled after reconnect — say the command again")
        }
    }

    // ---- microphone (called from the overlay button) ----
    /**
     * Opens the mic in [ListenMode.HOLD] (stops on release) or [ListenMode.HANDS_FREE] (stops on the next tap, with a
     * safety cap so a forgotten hands-free session cannot record indefinitely).
     */
    fun pttStart(mode: ListenMode) {
        if (AppState.listen.value != ListenMode.IDLE) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppState.log("microphone permission missing — open the app and allow it"); return
        }
        client.send(JSONObject().put("type", "audio_start").put("mode", if (awaitingConfirmSpeech) "confirm" else "command"))
        if (!recorder.start()) { client.send(JSONObject().put("type", "audio_end")); return } // don't leave the STT stream open
        AppState.listen.value = mode
        AppState.status.value = if (mode == ListenMode.HANDS_FREE) "Listening hands-free — tap to send" else "Listening…"
        if (mode == ListenMode.HANDS_FREE) {
            handsFreeCap = scope.launch {
                delay(HANDS_FREE_MAX_MS)
                if (AppState.listen.value == ListenMode.HANDS_FREE) { AppState.log("hands-free timed out after ${HANDS_FREE_MAX_MS / 1000}s"); pttStop() }
            }
        }
    }

    fun pttStop() {
        if (AppState.listen.value == ListenMode.IDLE) return
        handsFreeCap?.cancel(); handsFreeCap = null
        recorder.stop(); AppState.listen.value = ListenMode.IDLE; AppState.status.value = "Thinking…"
        client.send(JSONObject().put("type", "audio_end"))
    }

    fun sendCommand(text: String) { AppState.log("you (typed): $text"); client.send(JSONObject().put("type", "command").put("text", text)) }

    /** Overlay "Done" button (or the spoken word). */
    fun finishTeaching() = scope.launch { finishTeachingNow() }

    // ---- confirmation (button or voice) ----
    fun answerConfirm(yes: Boolean) { confirm?.complete(yes) }

    // ---- server messages ----
    private fun onAudio(bytes: ByteArray) { if (expectAudio) { expectAudio = false; runCatching { player.play(bytes) } } }

    private fun onServer(m: JSONObject) {
        when (m.optString("type")) {
            "transcript" -> onTranscript(m.optString("text"))
            "say" -> AppState.log("assistant: ${m.optString("text")}")
            "audio" -> expectAudio = true
            "teach_start" -> startTeaching(m)
            "teach_finish" -> scope.launch { finishTeachingNow() }
            "replay" -> scope.launch { runReplay(m) }
            "launch_app" -> openApp(m.optString("package"), m.optString("label"))
            "learned" -> { AppState.status.value = "Learned"; AppState.log("learned flow (${m.optInt("steps")} steps)") }
            "teach_failed" -> AppState.status.value = "Idle"
            "error" -> AppState.log("server error: ${m.optString("error")}")
        }
    }

    private fun onTranscript(text: String) {
        AppState.log("you: $text")
        confirm?.let { if (awaitingConfirmSpeech && text.isNotBlank()) it.complete(ConfirmText.isYes(text)) }
        if (!AppState.teaching.value) AppState.status.value = "Idle"
    }

    private fun openApp(pkg: String, label: String) {
        val svc = TvaAccessibilityService.instance ?: return AppState.log("Accessibility Service is not enabled")
        AppState.log(if (svc.launch(pkg)) "opened $label" else "could not open $label")
    }

    private fun startTeaching(m: JSONObject) {
        val svc = TvaAccessibilityService.instance ?: return AppState.log("Accessibility Service is not enabled")
        teach = m; svc.startRecording(); AppState.teaching.value = true
        AppState.status.value = "Do the task on screen, then say or tap Done"
        AppState.log("teach mode: ${m.optString("command")}")
    }

    private fun finishTeachingNow() {
        val ctx = teach ?: return
        val steps = TvaAccessibilityService.instance?.stopRecording().orEmpty()
        teach = null; AppState.teaching.value = false; AppState.status.value = "Saving…"
        client.send(JSONObject().put("type", "teach_done").put("command", ctx.optString("command")).put("intentKey", ctx.optString("intentKey"))
            .put("slots", ctx.optJSONObject("slots") ?: JSONObject()).put("steps", FlowJson.toJson(steps)))
    }

    private suspend fun runReplay(m: JSONObject) {
        val svc = TvaAccessibilityService.instance ?: return AppState.log("Accessibility Service is not enabled")
        val flowJson = m.getJSONObject("flow"); val flow = FlowJson.parse(flowJson.toString())
        val slots = m.optJSONObject("slots")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.orEmpty()
        AppState.status.value = "Running: ${flow.description}"
        val gate = ConfirmationGate { step, _ -> askConfirmation("${step.type.name.lowercase()} ${step.selector.text.ifEmpty { step.selector.contentDesc }}".trim()) }
        val r = Replayer(svc, BackendElementPicker(BackendConfig.url(this)), gate).run(flow, slots)
        AppState.status.value = if (r.success) "Done" else if (r.declined) "Stopped" else "Failed"
        AppState.log("replay ${if (r.success) "succeeded" else if (r.declined) "stopped by you" else "FAILED"}: ${r.steps.count { it.ok }}/${flow.steps.size}")
        client.send(JSONObject().put("type", "run_result").put("flowId", m.optString("flowId")).put("success", r.success).put("declined", r.declined)
            .put("utterance", m.optString("utterance")).put("steps", JSONArray().also { a ->
                r.steps.forEach { a.put(JSONObject().put("i", it.index).put("ok", it.ok).put("llm", it.usedLlmFallback).put("err", it.error ?: JSONObject.NULL)) } }))
    }

    /** Pauses replay until the user says or taps "yes". Nothing sensitive proceeds without it; 30 s of silence = no. */
    private suspend fun askConfirmation(what: String): Boolean {
        val d = CompletableDeferred<Boolean>(); confirm = d; awaitingConfirmSpeech = true
        AppState.confirmPrompt.value = what; AppState.status.value = "Confirm: $what?"
        client.send(JSONObject().put("type", "say_request").put("text", "Confirm? About to $what."))
        val ok = withTimeoutOrNull(30_000) { d.await() } ?: false
        confirm = null; awaitingConfirmSpeech = false; AppState.confirmPrompt.value = null
        AppState.log("confirmation for \"$what\": ${if (ok) "YES" else "no"}")
        return ok
    }

    companion object {
        private const val CHANNEL = "voice"
        private const val HANDS_FREE_MAX_MS = 60_000L
        @Volatile var instance: VoiceService? = null
    }
}
