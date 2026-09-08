package com.x3paranoids.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * The system voice: lines pre-rendered by tools/generate_voice.sh (macOS Zarvox through a
 * ring-modulator/crusher chain) into assets/voice/<id>.m4a. Played through one MediaPlayer on a
 * dedicated thread, one line at a time from a queue; an urgent line clears the queue and interrupts.
 * The voice always finishes its sentence otherwise (the suite's MCP rule). [onLineStart] fires on the
 * voice thread when a line actually begins — the intro crawl reveals its text on that beat.
 */
class Voice(private val context: Context) {
    companion object { private const val TAG = "X3Paranoids" }

    @Volatile var enabled = true
    @Volatile var volume = 1f
    @Volatile var isSpeaking = false; private set
    @Volatile var onLineStart: ((String) -> Unit)? = null
    @Volatile var onLineEnd: ((String) -> Unit)? = null
    /** Clip durations (ms) from the manifest, for anything that wants to time itself to the voice. */
    val durations = HashMap<String, Int>()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var player: MediaPlayer? = null
    private val queue = ArrayDeque<String>()
    private var current: String? = null

    fun load() {
        thread = HandlerThread("x3paranoids-voice").apply { start() }
        handler = Handler(thread!!.looper)
        runCatching {
            val j = JSONObject(context.assets.open("voice/manifest.json").bufferedReader().use { it.readText() })
            for (k in j.keys()) durations[k] = j.getInt(k)
        }.onFailure { Log.w(TAG, "voice manifest", it) }
    }

    fun say(id: String, urgent: Boolean = false) {
        if (!enabled) return
        handler?.post {
            if (urgent) { queue.clear(); stopCurrent() }
            else if (current != null && queue.size >= 3) return@post   // never let chatter pile up
            queue.add(id)
            if (current == null) pump()
        }
    }

    /** Queue several lines back to back (the intro). */
    fun sayAll(ids: List<String>) { if (!enabled) return; handler?.post { queue.clear(); stopCurrent(); queue.addAll(ids); pump() } }

    fun stop() { handler?.post { queue.clear(); stopCurrent() } }

    private fun pump() {
        val id = queue.poll() ?: run { current = null; isSpeaking = false; return }
        current = id
        val fd = runCatching { context.assets.openFd("voice/$id.m4a") }.getOrNull()
        if (fd == null) { Log.w(TAG, "no voice clip $id"); onLineStart?.invoke(id); onLineEnd?.invoke(id); pump(); return }
        runCatching {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length); fd.close()
            mp.setVolume(volume, volume)
            mp.setOnCompletionListener { p ->
                handler?.post { if (player === p) { runCatching { p.release() }; player = null; isSpeaking = false; onLineEnd?.invoke(id); pump() } }
            }
            mp.setOnErrorListener { p, _, _ -> handler?.post { if (player === p) { runCatching { p.release() }; player = null; isSpeaking = false; pump() } }; true }
            mp.prepare(); player = mp; isSpeaking = true
            onLineStart?.invoke(id)
            mp.start()
        }.onFailure { Log.w(TAG, "voice $id", it); player = null; isSpeaking = false; pump() }
    }

    private fun stopCurrent() {
        player?.let { runCatching { it.stop(); it.release() } }; player = null; current = null; isSpeaking = false
    }

    fun release() { handler?.post { queue.clear(); stopCurrent() }; thread?.quitSafely(); thread = null; handler = null }
}
