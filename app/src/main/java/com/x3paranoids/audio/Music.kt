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
    companion object {
        private const val TAG = "X3Paranoids"
        private const val TRACK = "music/io_tower.mp3"
        /**
         * HOW FAR THE TRACK DROPS UNDER A VOICE. It was 0.42 — a fifty-eight percent cut, which
         * against a default volume of 0.5 left io_tower.mp3 a rumour. The round-one note asked for
         * the duck to be softened and it never was; the track is the arena's own pulse and it
         * should step aside for a line, not leave the room for one. At 0.62 the two voices still
         * sit clearly on top of it and the music is audibly still playing underneath them, which
         * is the whole point of a duck rather than a mute.
         */
        private const val DUCK = 0.62f
    }

    @Volatile var volume = 0.5f
        set(v) { field = v; applyGain() }
    /**
     * Held down while either voice has the floor. The suite's sound effects already duck under
     * speech (Sfx.duckProvider); the music was the one thing that did not, and it is the loudest
     * continuous source in the mix — with two voices now trading lines, a track running at full
     * level under them is what turns a conversation into a wash.
     */
    @Volatile var duck = false
        set(v) { if (field != v) { field = v; applyGain() } }
    private fun applyGain() {
        val g = volume * (if (duck) DUCK else 1f)
        handler?.post { runCatching { player?.setVolume(g, g) } }
    }
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
            val g = volume * (if (duck) DUCK else 1f)
            mp.isLooping = true; mp.setVolume(g, g); mp.prepare(); mp.start()
            player = mp
        }.onFailure { Log.w(TAG, "music start", it) }
    }

    private fun stopOnThread() {
        player?.let { runCatching { it.stop(); it.release() } }; player = null
        afd?.let { runCatching { it.close() } }; afd = null
    }

    fun release() { handler?.post { stopOnThread() }; thread?.quitSafely(); thread = null; handler = null }
}
