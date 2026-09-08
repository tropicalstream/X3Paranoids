package com.x3paranoids

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.x3paranoids.audio.Music
import com.x3paranoids.audio.Sfx
import com.x3paranoids.audio.Voice
import com.x3paranoids.engine.Game
import com.x3paranoids.engine.GameHost
import com.x3paranoids.engine.Swipe
import com.x3paranoids.gl.GLRenderer
import com.x3paranoids.head.HeadTracker
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * X3Paranoids — the periscope is your head, the temple pad is the tank:
 *  - TAP fires (arrives as a KEY on the glasses; touch taps work too)
 *  - SWIPE UP / DOWN drives forward / backward, LEFT / RIGHT quarter-turns the hull —
 *    one discrete step per gesture, classified on finger-up
 *  - DOUBLE-TAP (one KEYCODE_BACK on this hardware) opens the settings; a third tap within
 *    350 ms makes it a TRIPLE-TAP = re-centre the head
 *  - the left temple (cyttsp6) is the system volume pad and is ignored. No long-press.
 */
class MainActivity : Activity(), GameHost {

    private lateinit var store: SettingsStore
    private lateinit var sfx: Sfx
    private lateinit var voice: Voice
    private lateinit var music: Music
    private lateinit var head: HeadTracker
    private lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer

    private val ui = Handler(Looper.getMainLooper())
    private var lastTapMs = 0L
    private var backPendingMs = 0L
    private var pendingDouble: Runnable? = null
    private var downX = 0f; private var downY = 0f; private var downT = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        sfx = Sfx(this).also { it.loadAsync() }
        voice = Voice(this).also { it.load() }
        music = Music(this).also { it.load() }
        head = HeadTracker(this)
        sfx.duckProvider = { voice.isSpeaking }
        game = Game(store, this)
        voice.onLineStart = { id -> glView.queueEvent { game.onVoiceLineStart(id) } }
        voice.onLineEnd = { id -> glView.queueEvent { game.onVoiceLineEnd(id) } }
        renderer = GLRenderer(game, head, store).also { it.sbs = store.sbs }
        glView = object : GLSurfaceView(this) {}.apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            // A DEPTH BUFFER, asked for out loud. The renderer needs one: walls are drawn into depth
            // as invisible solids so a Recognizer behind one is hidden (see GLRenderer's occluder
            // prepass). GLSurfaceView's default chooser happens to request depth 16 already, but the
            // whole occlusion pass silently degrades to "everything draws over everything" if a
            // device ever hands back a config without one, which is the exact bug being fixed here.
            // ALPHA STAYS 0, deliberately: that is what the default chooser asks for and what this
            // waveguide is already composited with. On a see-through display the window's alpha
            // channel is not a free parameter — black is transparency here, and asking for an 8-bit
            // alpha invites the compositor to blend the surface differently.
            setEGLConfigChooser(8, 8, 8, 0, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        applyVolume(store.volume)
        music.enabled = store.music
        voice.enabled = store.voice
        game.boot()
        music.play()
    }

    // ------------------------------------------------------------ GameHost (any thread)

    override fun sfx(id: Int, pitch: Float, vol: Float) = sfx.play(id, pitch, vol)
    override fun hum(level: Float, rate: Float) = sfx.hum(level, rate)
    override fun say(id: String, urgent: Boolean) = voice.say(id, urgent)
    override fun sayAll(ids: List<String>) = voice.sayAll(ids)
    override fun stopVoice() = voice.stop()
    override fun musicEnabled(on: Boolean) { music.enabled = on }
    override fun voiceEnabled(on: Boolean) { voice.enabled = on; if (!on) voice.stop() }
    override fun headEnabled(on: Boolean) { ui.post { if (on) head.start() else head.stop() } }
    override fun recentreHead() { head.recentre() }
    override fun applyVolume(v0to10: Int) {
        val v = v0to10 / 10f
        music.volume = 0.55f * v; sfx.volume = 0.9f * v; voice.volume = 1f * v
    }
    override fun voiceDurationMs(id: String): Int = voice.durations[id] ?: 0

    // --------------------------------------------------------------- input

    private fun onTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapMs < 60) return   // KEY + touch echo of one physical press
        lastTapMs = now
        // a tap right after a BACK = the third tap of a triple-tap
        if (backPendingMs != 0L && now - backPendingMs < 350) {
            pendingDouble?.let { ui.removeCallbacks(it) }; pendingDouble = null; backPendingMs = 0L
            glView.queueEvent { game.tripleTap() }
            return
        }
        glView.queueEvent { game.tap() }
    }

    private fun onBack() {
        val now = SystemClock.uptimeMillis()
        if (backPendingMs != 0L && now - backPendingMs < 350) return
        backPendingMs = now
        val r = Runnable { backPendingMs = 0L; pendingDouble = null; glView.queueEvent { game.doubleTap() } }
        pendingDouble = r
        ui.postDelayed(r, 350)
    }

    private fun onSwipe(dir: Swipe) = glView.queueEvent { game.swipe(dir) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && event.eventTime - event.downTime < 450) onTap()
                return true
            }
            KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP) onBack(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_UP) onSwipe(Swipe.UP); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_UP) onSwipe(Swipe.DOWN); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_UP) onSwipe(turnDir(-1)); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_UP) onSwipe(turnDir(1)); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Horizontal swipes quarter-turn the hull; the pad's raw dx sign is a per-owner call, so the setting flips it. */
    private fun turnDir(sign: Int): Swipe {
        val s = if (store.turnReversed) -sign else sign
        return if (s < 0) Swipe.LEFT else Swipe.RIGHT
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.device?.name?.contains("cyttsp6", ignoreCase = true) == true) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downT = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX; val dy = ev.y - downY
                val dist = hypot(dx, dy)
                val thresh = max(48f, 0.09f * resources.displayMetrics.widthPixels)
                if (dist >= thresh) {
                    if (abs(dx) >= abs(dy)) onSwipe(turnDir(if (dx < 0) -1 else 1))
                    else onSwipe(if (dy < 0) Swipe.UP else Swipe.DOWN)
                } else if (SystemClock.uptimeMillis() - downT < 400) onTap()
            }
        }
        return true
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView.onResume()
        if (store.headLook) head.start()
        music.resume()
    }

    override fun onPause() {
        head.stop()
        sfx.stopHum()
        music.pause()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release(); voice.release(); music.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
