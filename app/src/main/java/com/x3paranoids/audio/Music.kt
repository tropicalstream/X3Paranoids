package com.x3paranoids.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/** The IO Tower cover from x3cycles, looping under the title and the maze. Never touched from the GL thread. */
class Music(private val context: Context) {
    companion object { private const val TAG = "X3Paranoids"; private const val TRACK = "music/io_tower.mp3" }

    @Volatile var volume = 0.5f
        set(v) { field = v; handler?.post { runCatching { player?.setVolume(v, v) } } }
    @Volatile var enabled = true
        set(v) { field = v; handler?.post { if (v) startOnThread() else stopOnThread() } }
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var player: MediaPlayer? = null
    private var afd: AssetFileDescriptor? = null

    fun load() {
        thread = HandlerThread("x3paranoids-music").apply { start() }
        handler = Handler(thread!!.looper)
    }

    fun play() { handler?.post { if (enabled) startOnThread() } }
    fun pause() { handler?.post { runCatching { player?.pause() } } }
    fun resume() { handler?.post { if (enabled) runCatching { player?.start() } } }

    private fun startOnThread() {
        if (player != null) { runCatching { if (player?.isPlaying == false) player?.start() }; return }
        runCatching {
            val fd = context.assets.openFd(TRACK); afd = fd
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            mp.isLooping = true; mp.setVolume(volume, volume); mp.prepare(); mp.start()
            player = mp
        }.onFailure { Log.w(TAG, "music start", it) }
    }

    private fun stopOnThread() {
        player?.let { runCatching { it.stop(); it.release() } }; player = null
        afd?.let { runCatching { it.close() } }; afd = null
    }

    fun release() { handler?.post { stopOnThread() }; thread?.quitSafely(); thread = null; handler = null }
}
