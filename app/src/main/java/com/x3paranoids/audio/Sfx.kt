package com.x3paranoids.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesised SFX bank — no audio binaries besides the voice lines and the one music track.
 * Everything is generated into WAV files in the cache at first launch and played through a
 * SoundPool on its own thread (SoundPool.play is a binder call; the GL thread never waits on it).
 */
class Sfx(private val context: Context) {

    companion object {
        const val FIRE = 0          // the tank cannon: a bright sawtooth zap
        const val ENEMY_FIRE = 1    // Recognizer bolt: lower, buzzier
        const val DEREZ = 2         // a Recognizer loses cohesion — see the [DEREZ SOUND] note
        const val HIT = 3           // tank takes a hit
        const val DIE = 4           // the tank's own derez: the same collapse, slower and deeper
        const val BIT = 5           // (unused since the Bit found its voice — see BIT_GET)
        const val BUMP = 6          // wall bump
        const val TICK = 7          // menu tick
        const val SELECT = 8        // menu select
        const val WAVE = 9          // wave start sting
        const val CLEAR = 10        // wave cleared
        const val GAMEOVER = 11
        const val HISCORE = 12
        const val START = 13
        const val LOCK = 14         // a Recognizer locks on
        const val SPAWN = 15        // Recognizer materialises
        const val THRUST = 16       // a movement impulse
        const val RICOCHET = 17     // shell hits a wall
        const val HUM = 18          // looping Recognizer hover hum (nearest one)
        const val TURN = 19         // the hull's quarter turn: a servo whirr
        // ---- the Bit's voice. It says yes and no and nothing else; that is its whole character.
        const val BIT_YES = 20      // bright rising two-note
        const val BIT_NO = 21       // lower, dissonant, buzzier
        const val BIT_CHIRP = 22    // idle chatter — pitched and paced by how close you are
        const val BIT_GET = 23      // taken
        const val BIT_LOSE = 24     // lost: the same shapes falling instead of rising
        private const val COUNT = 25
        private const val RATE = 22050
    }

    private val pool = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val ids = IntArray(COUNT)
    @Volatile private var loaded = false
    @Volatile var volume = 0.7f
    @Volatile var duckProvider: (() -> Boolean)? = null
    private var humStream = 0
    private val rng = Random(17)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    fun loadAsync() {
        thread = HandlerThread("x3paranoids-sfx").apply { start() }
        handler = Handler(thread!!.looper)
        handler?.post {
            runCatching {
                val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
                ids[FIRE] = load(dir, "fire", buf(160) { t -> (saw(1500f - 1100f * t, t) * 0.6f + 0.25f * noise() * exp(-t * 30f)) * exp(-t * 14f) })
                ids[ENEMY_FIRE] = load(dir, "efire", buf(260) { t -> (sq(420f - 200f * t, t) * 0.45f + saw(210f, t) * 0.25f) * exp(-t * 8f) })
                // [DEREZ SOUND] — a program coming apart, not a crush. Four things happen at once
                // and all four run DOWN: the tone falls 900→75 Hz; the grain gate slows from a
                // 115 Hz buzz to ~8 Hz chunks, so continuous sound becomes discrete pieces; the
                // bit-crusher OPENS UP (26 levels → 2), so what is left is coarser the longer it
                // lasts; and the whole thing is cut to silence at 0.66 s inside a 0.76 s clip. That
                // last 100 ms of nothing is the point — absence, arriving early enough to hear.
                ids[DEREZ] = load(dir, "derez", buf(760) { t ->
                    if (t > 0.66f) 0f else {
                        val f = 75f + 830f * exp(-t * 5.2f)
                        var v = saw(f, t) * 0.50f + sq(f * 0.5f, t) * 0.28f + sine(f * 2f, t) * 0.16f
                        v += noise() * 0.55f * exp(-t * 26f)              // the shatter transient
                        val gr = 7f + 110f * exp(-t * 3.4f)
                        val gate = if ((t * gr).toInt() % 2 == 0) 1f else 0.16f
                        val q = max(2f, 26f * exp(-t * 2.6f))
                        v = (v * q).toInt() / q
                        v * gate * exp(-t * 2.9f)
                    }
                })
                ids[HIT] = load(dir, "hit", buf(420) { t -> (noise() * 0.5f + sq(110f, t) * 0.4f) * exp(-t * 6f) })
                // The player's own derez: the same collapse an octave down and four times as long,
                // with a wobble that widens as cohesion goes, and 250 ms of silence on the end.
                ids[DIE] = load(dir, "die", buf(2400) { t ->
                    if (t > 2.15f) 0f else {
                        val f = 42f + 360f * exp(-t * 1.35f)
                        var v = saw(f, t) * 0.40f + sq(f * 0.5f, t) * 0.24f + sine(f * 0.5f, t) * 0.30f
                        v += noise() * 0.5f * exp(-t * 9f)
                        v *= 1f + 0.45f * sine(7f - 5f * t, t)
                        val gr = 4f + 90f * exp(-t * 1.5f)
                        val gate = if ((t * gr).toInt() % 2 == 0) 1f else 0.12f
                        val q = max(2f, 24f * exp(-t * 1.1f))
                        v = (v * q).toInt() / q
                        v * gate * exp(-t * 1.15f)
                    }
                })
                // ---------------------------------------------------------------- the Bit's voice
                // YES: two notes UP, C6 then G6, the second gliding up as it goes. Sine-led with a
                // little square on top so it is bright and digital rather than a flute.
                ids[BIT_YES] = load(dir, "bityes", buf(240) { t ->
                    val second = t >= 0.085f
                    val lt = if (second) t - 0.085f else t
                    val f = (if (second) 1568f else 1046f) * (if (second) 1f + 0.34f * lt else 1f)
                    (sine(f, lt) * 0.60f + sine(f * 2f, lt) * 0.20f + sq(f, lt) * 0.13f) * exp(-lt * 11f)
                })
                // NO: two notes DOWN, G4 then C#4 — a tritone apart, which is the most disagreeable
                // interval there is — square-led, detuned against itself so it beats, and ring-
                // modulated at 34 Hz for the growl. Nobody will mistake it for the YES.
                ids[BIT_NO] = load(dir, "bitno", buf(360) { t ->
                    val second = t >= 0.12f
                    val lt = if (second) t - 0.12f else t
                    val f = if (second) 277f else 392f
                    var v = sq(f, lt) * 0.40f + saw(f * 1.008f, lt) * 0.30f + sq(f * 1.414f, lt) * 0.20f
                    v *= 0.70f + 0.30f * sine(34f, t)
                    v * exp(-lt * 6.5f)
                })
                // The idle chirp: one blip, rising hard. Played at a pitch and pace set by range —
                // one asset, a whole proximity cue.
                ids[BIT_CHIRP] = load(dir, "bitchirp", buf(90) { t ->
                    val f = 1250f + 9800f * t
                    (sine(f, t) * 0.65f + sq(f * 0.5f, t) * 0.15f) * exp(-t * 34f)
                })
                // Taken: the YES's interval opened out into a full rising figure, with a shimmer
                // tail that keeps ringing after the notes have gone.
                ids[BIT_GET] = load(dir, "bitget", buf(620) { t ->
                    var v = 0f
                    val notes = floatArrayOf(1046f, 1318f, 1568f, 2093f, 2637f)
                    for ((i, f) in notes.withIndex()) {
                        val st = i * 0.045f
                        if (t >= st) { val lt = t - st; v += (sine(f, lt) * 0.50f + sine(f * 2f, lt) * 0.15f + sq(f, lt) * 0.09f) * exp(-lt * 6.5f) }
                    }
                    v += sine(3136f, t) * 0.18f * exp(-t * 3.2f) * (0.6f + 0.4f * sine(9f, t))
                    v * 0.55f
                })
                // Lost: the same figure falling, crushed coarser as it goes. Resignation, not alarm.
                ids[BIT_LOSE] = load(dir, "bitlose", buf(720) { t ->
                    var v = 0f
                    val notes = floatArrayOf(659f, 494f, 330f)
                    for ((i, f) in notes.withIndex()) {
                        val st = i * 0.15f
                        if (t >= st) { val lt = t - st; v += (sq(f, lt) * 0.28f + saw(f * 1.01f, lt) * 0.26f) * exp(-lt * 5f) }
                    }
                    v *= 0.70f + 0.30f * sine(21f, t)
                    val q = max(3f, 18f * exp(-t * 1.6f))
                    v = (v * q).toInt() / q
                    v * 0.6f
                })
                ids[BIT] = load(dir, "bit", arpeggio(intArrayOf(880, 1174, 1568, 2093, 2637), 60, 0.75f))
                ids[BUMP] = load(dir, "bump", buf(180) { t -> (sine(70f, t) * 0.8f + noise() * 0.2f * exp(-t * 60f)) * exp(-t * 16f) })
                ids[TICK] = load(dir, "tick", buf(50) { t -> sq(1200f, t) * exp(-t * 60f) * 0.35f })
                ids[SELECT] = load(dir, "select", arpeggio(intArrayOf(660, 990), 50, 0.6f))
                ids[WAVE] = load(dir, "wave", arpeggio(intArrayOf(330, 440, 554, 659), 90, 0.7f))
                ids[CLEAR] = load(dir, "clear", arpeggio(intArrayOf(523, 659, 784, 1046, 1318), 80, 0.7f))
                ids[GAMEOVER] = load(dir, "over", buf(1200) { t ->
                    val f = if (t < 0.5f) 300f - t * 160f else 220f - (t - 0.5f) * 120f
                    (saw(f, t) * 0.4f + sine(f * 0.5f, t) * 0.4f) * exp(-t * 1.8f)
                })
                ids[HISCORE] = load(dir, "hi", arpeggio(intArrayOf(523, 659, 784, 1046, 1318, 1568, 2093), 80, 0.7f))
                ids[START] = load(dir, "start", arpeggio(intArrayOf(262, 330, 392, 523, 659, 784), 70, 0.7f))
                ids[LOCK] = load(dir, "lock", buf(240) { t -> sq(if ((t * 12f).toInt() % 2 == 0) 1400f else 1000f, t) * exp(-t * 9f) * 0.35f })
                ids[SPAWN] = load(dir, "spawn", buf(500) { t -> (sine(180f + 1400f * t, t) * 0.4f + saw(90f + 300f * t, t) * 0.2f) * exp(-t * 5f) })
                ids[THRUST] = load(dir, "thrust", buf(260) { t -> (noise() * 0.35f + saw(70f + 90f * t, t) * 0.45f) * exp(-t * 9f) })
                ids[RICOCHET] = load(dir, "rico", buf(160) { t -> (sine(2200f - 1600f * t, t) * 0.4f + noise() * 0.3f) * exp(-t * 22f) })
                // a servo whirr that rises then settles, the length of one quarter turn
                ids[TURN] = load(dir, "turn", buf(300) { t ->
                    val env = sin(3.1416f * (t / 0.3f).coerceIn(0f, 1f))
                    (saw(180f + 260f * sin(3.1416f * t / 0.3f), t) * 0.35f + noise() * 0.12f) * env
                })
                ids[HUM] = load(dir, "hum", buf(1000) { t -> sine(58f, t) * 0.35f + sine(116f, t) * 0.15f + saw(29f, t) * 0.12f })
                loaded = true
            }
        }
    }

    fun play(id: Int, pitch: Float = 1f, vol: Float = 1f) {
        if (!loaded || id < 0 || id >= COUNT) return
        handler?.post {
            val s = ids[id]; if (s == 0) return@post
            val duck = if (duckProvider?.invoke() == true) 0.45f else 1f
            val v = (volume * vol * duck).coerceIn(0f, 1f); if (v <= 0f) return@post
            pool.play(s, v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
        }
    }

    /** Looping hover hum whose volume follows the nearest Recognizer (0 = stop). */
    fun hum(level: Float, rate: Float = 1f) {
        handler?.post {
            if (!loaded) return@post
            val v = (volume * 0.5f * level.coerceIn(0f, 1f)).coerceIn(0f, 1f)
            if (v <= 0.01f) { if (humStream != 0) { pool.stop(humStream); humStream = 0 }; return@post }
            if (humStream == 0) humStream = pool.play(ids[HUM], v, v, 0, -1, rate.coerceIn(0.5f, 2f))
            else { pool.setVolume(humStream, v, v); pool.setRate(humStream, rate.coerceIn(0.5f, 2f)) }
        }
    }

    fun stopHum() { handler?.post { if (humStream != 0) { pool.stop(humStream); humStream = 0 } } }

    fun release() {
        handler?.post { runCatching { pool.release() } }
        thread?.quitSafely(); thread = null; handler = null
    }

    private fun buf(ms: Int, gen: (Float) -> Float): ShortArray {
        val n = RATE * ms / 1000
        return ShortArray(n) { i -> (gen(i.toFloat() / RATE).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }
    private fun sine(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()
    private fun saw(f: Float, t: Float): Float { val p = (f * t) % 1f; return 2f * p - 1f }
    private fun sq(f: Float, t: Float) = if ((f * t) % 1f < 0.5f) 1f else -1f
    private fun noise() = rng.nextFloat() * 2f - 1f
    private fun arpeggio(freqs: IntArray, noteMs: Int, amp: Float): ShortArray {
        val total = noteMs * freqs.size + 220
        return buf(total) { t ->
            var v = 0f
            for ((i, f) in freqs.withIndex()) {
                val start = i * noteMs / 1000f
                if (t >= start) { val lt = t - start; v += (sine(f.toFloat(), lt) + 0.3f * sine(f * 2f, lt)) * exp(-lt * 5.5f) * amp * 0.4f }
            }
            v
        }
    }

    private fun DataOutputStream.wInt(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF) }
    private fun DataOutputStream.wShort(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val f = File(dir, "$name.wav")
        val dataLen = pcm.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { o ->
            o.writeBytes("RIFF"); o.wInt(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.wInt(16); o.wShort(1); o.wShort(1)
            o.wInt(RATE); o.wInt(RATE * 2); o.wShort(2); o.wShort(16)
            o.writeBytes("data"); o.wInt(dataLen)
            for (s in pcm) o.wShort(s.toInt())
        }
        return pool.load(f.absolutePath, 1)
    }
}
