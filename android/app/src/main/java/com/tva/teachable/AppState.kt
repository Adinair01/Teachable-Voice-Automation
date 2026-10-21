package com.tva.teachable

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** How the microphone is currently being held open. */
enum class ListenMode { IDLE, HOLD, HANDS_FREE }

/** Process-wide UI state shared by the activity, the foreground service and the overlay. */
object AppState {
    val log = MutableStateFlow<List<String>>(emptyList())
    val status = MutableStateFlow("Idle")
    val connected = MutableStateFlow(false)
    val listen = MutableStateFlow(ListenMode.IDLE)
    val teaching = MutableStateFlow(false)
    /** A replay is running (the panel then offers Stop). */
    val running = MutableStateFlow(false)
    /** Non-null while a sensitive step waits for the user's yes/no. */
    val confirmPrompt = MutableStateFlow<String?>(null)

    /** Kept in memory, time-stamped, last [LOG_MAX]: what the assistant heard, said and did, readable over adb ("log"). */
    fun log(msg: String) {
        Log.i("TVA", msg)
        val at = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        log.update { (it + "$at $msg").takeLast(LOG_MAX) }
    }
    private const val LOG_MAX = 400
}
