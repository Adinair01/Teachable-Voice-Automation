package com.tva.teachable

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Process-wide UI state shared by the activity, the foreground service and the overlay. */
object AppState {
    val log = MutableStateFlow<List<String>>(emptyList())
    val status = MutableStateFlow("Idle")
    val connected = MutableStateFlow(false)
    val listening = MutableStateFlow(false)
    val teaching = MutableStateFlow(false)
    /** Non-null while a sensitive step waits for the user's yes/no. */
    val confirmPrompt = MutableStateFlow<String?>(null)

    fun log(msg: String) { Log.i("TVA", msg); log.update { (it + msg).takeLast(80) } }
}
