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
        const val EXPLODE = 2       // Recognizer derezzes
        const val HIT = 3           // tank takes a hit
        const val DIE = 4           // tank derezzes
        const val BIT = 5           // Bit acquired
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
        private const val COUNT = 20
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
                ids[EXPLODE] = load(dir, "expl", buf(620) { t ->
                    val crush = if ((t * 40f).toInt() % 2 == 0) 1f else 0.5f
                    ((noise() * 0.7f + sq(160f * (1f - t * 0.6f), t) * 0.3f) * crush) * exp(-t * 4.2f)
                })
                ids[HIT] = load(dir, "hit", buf(420) { t -> (noise() * 0.5f + sq(110f, t) * 0.4f) * exp(-t * 6f) })
                ids[DIE] = load(dir, "die", buf(1100) { t ->
                    val f = 300f - t * 220f
                    val crush = if ((t * 22f).toInt() % 2 == 0) 1f else 0.35f
                    ((noise() * 0.5f + saw(f, t) * 0.5f) * crush) * exp(-t * 2.6f)
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
