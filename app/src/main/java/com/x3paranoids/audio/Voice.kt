package com.x3paranoids.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * ONE FLOOR, TWO SPEAKERS.
 *
 * The game has two voices and the contrast between them is the point, so the one thing they must
 * never do is talk over each other — two speech tracks summed on a head-worn display is not a
 * conversation, it is mud. The bus is the floor: whoever holds it speaks, and the other one waits.
 *
 * It is deliberately asymmetric. The SYSTEM can CUT THE PILOT OFF ([preempt], used by urgent lines
 * — a wave, a hit, a derez); the pilot never interrupts anybody and simply waits its turn, and
 * gives up if it has waited past its patience. That is a mixing rule and a characterisation at the
 * same time: the system is the game and gets to speak; the pilot is a program talking back, and the
 * lines it loses are the ones it would have thrown away anyway.
 */
class VoiceBus {
    private var holder: Voice? = null
    @Synchronized fun acquire(v: Voice): Boolean {
        if (holder == null) { holder = v; return true }
        return holder === v
    }
    @Synchronized fun release(v: Voice) { if (holder === v) holder = null }
    /** Tell whoever is speaking to stop, so [by] can take the floor on its next retry. */
    fun preempt(by: Voice) {
        val h = synchronized(this) { holder }
        if (h != null && h !== by) h.stop()
    }
}

/**
 * A pre-rendered voice track: `assets/<dir>/<id>.<ext>` plus a `manifest.json` of clip durations.
 * Two are built on it:
 *
 *  - THE SYSTEM — the `voice` directory, m4a, macOS Zarvox through a ring-modulator/crusher chain.
 *    Flat, machine, indifferent. It narrates the lore crawl and states facts.
 *  - THE PILOT — the `voice_hero` directory, mp3, the owner's fish.audio model. The program whose
 *    game was stolen, talking back over its own code.
 *
 * One MediaPlayer on a dedicated thread, one line at a time from a queue, arbitrated by a shared
 * [VoiceBus]. An urgent line clears THIS voice's queue, interrupts its own current line and
 * preempts the other voice; otherwise a line always finishes its sentence. [onLineStart] fires on
 * the voice thread when a line actually begins — the intro crawl reveals its text on that beat.
 *
 * PATIENCE. A queued line carries how long it is willing to wait for the floor. A throwaway pilot
 * quip gets a second and a half and is dropped if the system is still talking; a scripted retort —
 * the pilot answering a wave announcement or a game over — gets several seconds, because arriving
 * late is the whole idea. A stale line is worse than no line, so nothing waits forever.
 */
class Voice(private val context: Context, private val dir: String, private val ext: String, private val bus: VoiceBus) {
    companion object {
        private const val TAG = "X3Paranoids"
        /** A line that has to elbow past the other voice usually gets the floor within a retry or two. */
        private const val RETRY_MS = 60L
    }

    @Volatile var enabled = true
    @Volatile var volume = 1f
    @Volatile var isSpeaking = false; private set
    @Volatile var onLineStart: ((String) -> Unit)? = null
    @Volatile var onLineEnd: ((String) -> Unit)? = null
    /** Clip durations (ms) from the manifest, for anything that wants to time itself to the voice. */
    val durations = HashMap<String, Int>()

    private class Line(val id: String, val patienceMs: Long) { var waitFrom = 0L }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var player: MediaPlayer? = null
    private val queue = ArrayDeque<Line>()
    private var current: String? = null
    /**
     * A pump chain is live — including one that is only WAITING for the floor.
     *
     * This flag is the whole reason the pilot does not talk over itself, and it was put here after
     * watching it do exactly that on the glasses: hero_last_life and hero_derez played on top of
     * each other for two seconds. The window is small and entirely real. A line that cannot get the
     * floor leaves a retry chain ticking every [RETRY_MS] while `current` is still null, because
     * nothing is playing yet; a second [say] arriving inside that window used to see the null and
     * start a SECOND chain. Both chains then waited, both were handed the floor by [VoiceBus]
     * (the bus grants re-entry to its own holder, as it must for a queued crawl), and each one
     * built its own MediaPlayer over the top of the other's.
     *
     * So the gate is "is a chain already running", not "is something already playing".
     */
    private var pumping = false

    fun load() {
        thread = HandlerThread("x3paranoids-voice-$dir").apply { start() }
        handler = Handler(thread!!.looper)
        runCatching {
            val j = JSONObject(context.assets.open("$dir/manifest.json").bufferedReader().use { it.readText() })
            for (k in j.keys()) durations[k] = j.getInt(k)
        }.onFailure { Log.w(TAG, "voice manifest $dir", it) }
    }

    fun say(id: String, urgent: Boolean = false, patienceMs: Long = 1500L) {
        if (!enabled) return
        handler?.post {
            if (urgent) { queue.clear(); stopCurrent(); bus.preempt(this) }
            else if (queue.size >= 3) return@post   // never let chatter pile up
            queue.add(Line(id, patienceMs))
            if (!pumping && !isSpeaking) pump()
        }
    }

    /** Queue several lines back to back (the intro). */
    fun sayAll(ids: List<String>) {
        if (!enabled) return
        handler?.post {
            queue.clear(); stopCurrent(); bus.preempt(this)
            for (id in ids) queue.add(Line(id, 6000L))
            if (!pumping) pump()          // a live chain will pick the crawl up on its next tick
        }
    }

    fun stop() { handler?.post { queue.clear(); stopCurrent(); bus.release(this) } }

    private fun pump() {
        pumping = false
        val line = queue.peek()
        if (line == null) { current = null; isSpeaking = false; bus.release(this); return }
        // THE FLOOR. Hold what we already have (so a queued crawl runs uninterrupted), otherwise
        // wait for it — and drop the line rather than deliver it late once its patience is spent.
        if (!bus.acquire(this)) {
            val now = SystemClock.uptimeMillis()
            if (line.waitFrom == 0L) line.waitFrom = now
            if (now - line.waitFrom > line.patienceMs) { queue.poll(); pump(); return }
            pumping = true
            handler?.postDelayed({ pump() }, RETRY_MS)
            return
        }
        queue.poll()
        val id = line.id
        // ONE PLAYER PER VOICE, ALWAYS. Belt to the `pumping` brace: whatever route got us here,
        // anything still sounding on this track stops before the next line is built.
        stopCurrent()
        current = id
        val fd = runCatching { context.assets.openFd("$dir/$id.$ext") }.getOrNull()
        if (fd == null) { Log.w(TAG, "no clip $dir/$id.$ext"); onLineStart?.invoke(id); onLineEnd?.invoke(id); pump(); return }
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
            // The one log line that makes the two-voice mix auditable from a terminal: who took the
            // floor, when, and how long they will hold it. Verifying "the voices never collide" by
            // ear on a head-worn display is guesswork; verifying it from a timestamped transcript
            // is not. Cheap — one line per utterance, a few dozen a game.
            Log.i(TAG, "say[$dir] $id (${durations[id] ?: -1}ms)")
            onLineStart?.invoke(id)
            mp.start()
        }.onFailure { Log.w(TAG, "voice $dir/$id", it); player = null; isSpeaking = false; pump() }
    }

    private fun stopCurrent() {
        player?.let { runCatching { it.stop(); it.release() } }; player = null; current = null; isSpeaking = false
    }

    fun release() { handler?.post { queue.clear(); stopCurrent(); bus.release(this) }; thread?.quitSafely(); thread = null; handler = null }
}
