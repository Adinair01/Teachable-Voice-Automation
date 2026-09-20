package com.tva.teachable.voice

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/** Plays the mp3 replies from Deepgram Aura. One utterance at a time; a new one replaces the old. */
class AudioPlayer(private val ctx: Context) {
    private var player: MediaPlayer? = null

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

    @Synchronized fun release() { player?.release(); player = null }
}
