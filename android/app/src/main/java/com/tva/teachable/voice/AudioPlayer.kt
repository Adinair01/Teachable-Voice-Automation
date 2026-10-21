package com.tva.teachable.voice

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/** Plays the mp3 replies from Deepgram Aura. One utterance at a time; a new one replaces the old. */
class AudioPlayer(private val ctx: Context) {
    private var player: MediaPlayer? = null
    /** When the last utterance stopped playing (finished, replaced, or cut off because the user started talking). */
    @Volatile var stoppedAt = 0L
        private set
    val playing get() = player != null

    @Synchronized
    fun play(mp3: ByteArray) {
        release()
        val f = File(ctx.cacheDir, "tts.mp3").apply { writeBytes(mp3) }
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { release() }
            prepare(); start()
        }
    }

    @Synchronized fun release() { if (player != null) stoppedAt = System.currentTimeMillis(); player?.release(); player = null }
}
