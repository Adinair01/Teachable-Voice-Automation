package com.tva.teachable.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.tva.teachable.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Push-to-talk capture: 16 kHz mono PCM16 chunks (what Deepgram Nova 3 is configured for). */
class PttRecorder(private val scope: CoroutineScope, private val onChunk: (ByteArray) -> Unit) {
    private var job: Job? = null
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start(): Boolean {
        if (job != null) return true
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
        } catch (e: Exception) { AppState.log("mic error: $e"); return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { AppState.log("mic not available"); rec.release(); return false }
        record = rec; rec.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(CHUNK)
            while (isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) onChunk(buf.copyOf(n)) else if (n < 0) break
            }
        }
        return true
    }

    fun stop() {
        job?.cancel(); job = null
        record?.let { runCatching { it.stop() }; it.release() }; record = null
    }

    private companion object { const val RATE = 16000; const val CHUNK = 3200 } // 100 ms
}
