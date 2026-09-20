package com.tva.teachable.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/** Single WebSocket to the backend with automatic reconnect. Text frames are JSON, binary frames audio. */
class BackendClient(
    private val wsUrl: String,
    private val scope: CoroutineScope,
    private val onJson: (JSONObject) -> Unit,
    private val onAudio: (ByteArray) -> Unit,
    private val onConnection: (Boolean) -> Unit,
) {
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    @Volatile private var socket: WebSocket? = null
    private var loop: Job? = null

    fun start() {
        loop = scope.launch {
            var backoff = 1000L
            while (isActive) {
                val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                socket = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) { backoff = 1000L; onConnection(true) }
                    override fun onMessage(ws: WebSocket, text: String) { runCatching { onJson(JSONObject(text)) } }
                    override fun onMessage(ws: WebSocket, bytes: ByteString) { onAudio(bytes.toByteArray()) }
                    override fun onClosed(ws: WebSocket, code: Int, reason: String) { onConnection(false); closed.complete(Unit) }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { onConnection(false); closed.complete(Unit) }
                })
                closed.await()
                delay(backoff); backoff = (backoff * 2).coerceAtMost(15000L)
            }
        }
    }

    fun send(obj: JSONObject): Boolean = socket?.send(obj.toString()) ?: false
    fun sendAudio(bytes: ByteArray): Boolean = socket?.send(bytes.toByteString()) ?: false
    fun stop() { loop?.cancel(); socket?.close(1000, "bye") }
}
