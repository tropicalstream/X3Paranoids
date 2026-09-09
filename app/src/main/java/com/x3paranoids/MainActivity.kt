package com.x3paranoids

import android.app.Activity
import android.content.pm.ApplicationInfo
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
import com.x3paranoids.audio.VoiceBus
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
 *  - SWIPE UP / DOWN drives forward / backward — FLICK FOR A DASH, HOLD FOR A CRUISE (below)
 *  - SWIPE LEFT / RIGHT quarter-turns the hull, one turn per gesture, classified on finger-up
 *  - DOUBLE-TAP (one KEYCODE_BACK on this hardware) opens the settings; a third tap within
 *    350 ms makes it a TRIPLE-TAP = re-centre the head
 *  - the left temple (cyttsp6) is the system volume pad and is ignored.
 *
 * HOLD TO DRIVE, AND WHY THE PAD IS READ IN TWO DIFFERENT WAYS.
 *
 * A vertical gesture is now classified on the ACTION_MOVE that first crosses the threshold rather
 * than waiting for finger-up, and the drive it starts runs until the finger LIFTS. Lift straight
 * away and nothing has happened but the dash the old code applied on finger-up, so a flick is
 * bit-for-bit the gesture it always was; keep the finger down and [Game.driveStart]'s thrust keeps
 * the tank moving. The axis is LATCHED at that first crossing: a gesture that crossed sideways can
 * never start a drive no matter where the finger wanders afterwards, so a held horizontal is a
 * quarter turn and only a quarter turn.
 *
 * OFF THE ARENA THE OLD CLASSIFIER IS USED UNCHANGED — see [Game.holdDriveArmed]. On the title and
 * in the settings the pad is read once, on finger-up, exactly as before, which is what keeps the
 * menu at one swipe per step however long the pad is held.
 *
 * The suite's standing rule is that a long-press belongs to the system (the X3 reserves a temple
 * hold for its quick-settings shade), and this is the documented exception to it: a 2.5 s injected
 * hold kept this activity focused with no shade and nothing in logcat, and the owner then held the
 * physical pad with a real finger and confirmed the same. If a shade ever DOES appear, the fallback
 * is a latched cruise — an up swipe engages sustained drive until the player swipes down, taps or
 * hits a wall — which needs no hold at all; the engine side of this change would not move.
 */
class MainActivity : Activity(), GameHost {

    private companion object {
        /** The gesture is settled the old way, once, on finger-up. */
        const val AXIS_UP = 1
        /** The gesture crossed vertically on the arena and is driving for as long as it is held. */
        const val AXIS_DRIVE = 2
        const val SRC_NONE = 0
        const val SRC_TOUCH = 1
        const val SRC_KEY = 2
    }

    private lateinit var store: SettingsStore
    private lateinit var sfx: Sfx
    /** The SYSTEM: the assets `voice` directory, Zarvox through a crusher. The game itself, talking. */
    private lateinit var voice: Voice
    /** The PILOT: the assets `voice_hero` directory, the owner's fish.audio model. Talking back. */
    private lateinit var hero: Voice
    /** The floor the two of them share — see [VoiceBus]. They never speak at once. */
    private val voiceBus = VoiceBus()
    private lateinit var music: Music
    private lateinit var head: HeadTracker
    private lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer

    private val ui = Handler(Looper.getMainLooper())
    private var lastTapMs = 0L
    private var backPendingMs = 0L
    /** When the pad last delivered a CLASSIFIED gesture (a tap or a double-tap) as a key. */
    private var lastKeyGestureMs = -10_000L
    /**
     * Latched the first time a TAP arrives already classified into a key (KEYCODE_BUTTON_A). From
     * then on the raw touch stream underneath a tap is only ever an echo and never becomes a tap.
     *
     * Read the name literally: this is "the pad delivers TAPS as keys", and nothing else. An
     * earlier version latched this on KEYCODE_BACK too, on the reasoning that a BACK proves the
     * pad classifies. It proves the pad classifies DOUBLE-taps. It says nothing whatsoever about
     * single taps — and if single taps are in fact only reaching us as touches, latching here on a
     * BACK switches the app's only working tap source off for the rest of the session, one
     * double-tap in. The game went deaf on the title screen and the owner could not start a run.
     * The lesson is cheap to state and was expensive to learn: only evidence about taps may be
     * used to make decisions about taps.
     */
    private var padTapsAreKeys = false
    /** The burst of touch-taps being counted right now, off the arena. See [touchTap]. */
    private var burstN = 0
    private var burstR: Runnable? = null
    /** Did the pad's own classifier speak up mid-burst? Then it owns the outcome, not us. */
    private var burstHadBack = false
    private var pendingDouble: Runnable? = null
    private var downX = 0f; private var downY = 0f; private var downT = 0L
    /** 0 = not yet classified, [AXIS_UP] = settle it on finger-up as before, [AXIS_DRIVE] = driving. */
    private var gestureAxis = 0
    /** Which way the live touch drive is going: -1 = finger up = forward, +1 = finger down = back. */
    private var driveSign = 0
    /** The furthest the finger has got in [driveSign]'s direction — the anchor a reversal is measured from. */
    private var driveExtremeY = 0f
    /** Who owns the live drive: [SRC_NONE], [SRC_TOUCH] or [SRC_KEY]. One source at a time. */
    private var driveOwner = SRC_NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        sfx = Sfx(this).also { it.loadAsync() }
        voice = Voice(this, "voice", "m4a", voiceBus).also { it.load() }
        hero = Voice(this, "voice_hero", "mp3", voiceBus).also { it.load() }
        music = Music(this).also { it.load() }
        head = HeadTracker(this)
        // Effects duck under EITHER voice, and now so does the music: with two speakers trading
        // lines, the track underneath them is the difference between a conversation and a wash.
        sfx.duckProvider = { voice.isSpeaking || hero.isSpeaking }
        game = Game(store, this)
        voice.onLineStart = { id -> refreshDuck(); glView.queueEvent { game.onVoiceLineStart(id) } }
        voice.onLineEnd = { id -> refreshDuck(); glView.queueEvent { game.onVoiceLineEnd(id) } }
        // The pilot's caption is raised on the beat its clip actually starts — see Game.onHeroLineStart.
        hero.onLineStart = { id -> refreshDuck(); glView.queueEvent { game.onHeroLineStart(id) } }
        hero.onLineEnd = { refreshDuck() }
        renderer = GLRenderer(game, head, store).also { it.sbs = store.sbs }
        glView = object : GLSurfaceView(this) {}.apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            // EIGHT BITS PER CHANNEL, asked for out loud. GLSurfaceView's own default chooser asks
            // for 5-6-5, and this renderer spends most of its light in the bottom of the range: the
            // fog floor, the far walls and the rung ladder all live at alphas that land on values
            // like (0,4,1) and (1,6,2). In 565 those quantise to a handful of steps and the arena's
            // distance cue turns into banding, so the strokes that say "far away" stop saying it.
            // ALPHA STAYS 0, deliberately: that is what the default chooser asks for and what this
            // waveguide is already composited with. On a see-through display the window's alpha
            // channel is not a free parameter — black is transparency here, and asking for an 8-bit
            // alpha invites the compositor to blend the surface differently.
            // The 16 is a depth buffer the renderer no longer uses (see GLRenderer's OCCLUSION note:
            // hiding is decided on the CPU now). It is left in the request because this is the exact
            // config verified on the glasses, and a depth attachment that is never cleared, tested
            // or read costs a tile buffer nobody touches.
            setEGLConfigChooser(8, 8, 8, 0, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        applyVolume(store.volume)
        music.enabled = store.music
        voice.enabled = store.voice; hero.enabled = store.voice
        game.boot()
        music.play()
        // DEBUGGABLE BUILDS ONLY: `am start ... --ei wave N --ei shield M` jumps straight into a
        // game at wave N wearing M charges — see Game.debugStart. Release builds ignore the extras.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            val w = intent?.getIntExtra("wave", 0) ?: 0
            val s = intent?.getIntExtra("shield", 0) ?: 0
            val atPool = (intent?.getIntExtra("atpool", 0) ?: 0) != 0
            if (w > 0) glView.queueEvent { game.debugStart(w, s, atPool) }
        }
    }

    // ------------------------------------------------------------ GameHost (any thread)

    override fun sfx(id: Int, pitch: Float, vol: Float) = sfx.play(id, pitch, vol)
    override fun hum(level: Float, rate: Float) = sfx.hum(level, rate)
    override fun say(id: String, urgent: Boolean, patienceMs: Long) = voice.say(id, urgent, patienceMs)
    override fun sayAll(ids: List<String>) = voice.sayAll(ids)
    override fun stopVoice() { voice.stop(); refreshDuck() }
    override fun hero(id: String, patienceMs: Long) = hero.say(id, false, patienceMs)
    override fun stopHero() { hero.stop(); refreshDuck() }
    override fun musicEnabled(on: Boolean) { music.enabled = on }
    override fun voiceEnabled(on: Boolean) {
        voice.enabled = on; hero.enabled = on
        if (!on) { voice.stop(); hero.stop(); refreshDuck() }
    }
    override fun headEnabled(on: Boolean) { ui.post { if (on) head.start() else head.stop() } }
    override fun recentreHead() { head.recentre() }
    override fun applyVolume(v0to10: Int) {
        val v = v0to10 / 10f
        // The pilot sits a shade under the system voice: the machine is loud because it does not
        // care, and the program talking back over its own stolen code is the quieter of the two.
        music.volume = 0.55f * v; sfx.volume = 0.9f * v; voice.volume = 1f * v; hero.volume = 0.92f * v
    }
    override fun voiceDurationMs(id: String): Int = voice.durations[id] ?: 0

    /**
     * Leave the game. The sign-off line is already speaking when this arrives, so the exit waits
     * out the clip rather than cutting the machine off mid-sentence — the one place in the game
     * where the system voice gets the last word. finish() and not a force-stop: the launcher drops
     * a force-stopped app off the Mercury drawer, and a game you quit politely should still be
     * there when you want it again.
     */
    override fun quitGame() {
        val hold = (voice.durations["end_of_line"] ?: 0).coerceIn(0, 2000) + 250L.toInt()
        ui.postDelayed({ if (!isFinishing) finish() }, hold.toLong())
    }
    override fun heroDurationMs(id: String): Int = hero.durations[id] ?: 0
    override fun voiceBusy(): Boolean = voice.isSpeaking || hero.isSpeaking

    private fun refreshDuck() { music.duck = voice.isSpeaking || hero.isSpeaking }

    // --------------------------------------------------------------- input

    /**
     * [fromKey] is the whole point of this signature. The temple pad reports one physical gesture
     * TWICE: once already classified as a key (a single tap is KEYCODE_BUTTON_A, a double-tap is a
     * single KEYCODE_BACK) and again as the raw touch stream underneath it. A physical double-tap
     * therefore arrives as one BACK plus TWO short touches, and when those touches were allowed to
     * become taps the gesture destroyed itself: the first tap actioned the selected menu row, and
     * the second — landing inside the BACK's 350 ms window — was mistaken for the third tap of a
     * triple-tap and cancelled the pending menu toggle. The menu changed a value and refused to
     * close, which is exactly what the owner reported.
     *
     * So the pad's own classifier is authoritative and only a KEY tap may promote to a triple-tap.
     */
    private fun onTap(fromKey: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapMs < 60) return   // KEY + touch echo of one physical press
        // a tap right after a BACK = the third tap of a triple-tap — but ONLY if the pad said so.
        // A touch here is the second half of the double-tap that produced the BACK.
        if (backPendingMs != 0L && now - backPendingMs < 350) {
            // A touch this soon after the BACK is that double-tap's own second finger-lift, which
            // the pad reports at essentially the same instant it emits the BACK. A human going for
            // a third tap cannot get there that fast, so the gap tells the two apart.
            if (!fromKey && now - backPendingMs < 140) return
            pendingDouble?.let { ui.removeCallbacks(it) }; pendingDouble = null; backPendingMs = 0L
            lastTapMs = now
            glView.queueEvent { game.tripleTap() }
            return
        }
        lastTapMs = now
        glView.queueEvent { game.tap() }
    }

    /**
     * A TOUCH-DERIVED TAP OFF THE ARENA, counted into a burst instead of acted on immediately.
     *
     * The pad reports one physical gesture twice — once classified into a key, once as the raw
     * touch underneath — and the classification cannot arrive until the gesture is over. So the
     * first touch of a double-tap always lands while the app still has no idea a second one is
     * coming. Acting on it at once is what made a double-tap change a settings row on its way to
     * closing the menu. Waiting a beat costs nothing here: off the arena there is no shot to miss.
     *
     * If the pad's own BACK turns up mid-burst it has already told us this was a double-tap and it
     * owns the toggle, so the burst resolves to nothing. If it never turns up — hardware that
     * reports touch and nothing else — the burst does the job itself. Three taps cancel the
     * pending toggle the moment the third lands rather than at the end of the burst, because the
     * BACK's own 350 ms runnable would otherwise fire first and open the menu under the re-centre.
     */
    private fun touchTap() {
        burstN++
        burstR?.let { ui.removeCallbacks(it) }
        if (burstN >= 3) { pendingDouble?.let { ui.removeCallbacks(it) }; pendingDouble = null; backPendingMs = 0L }
        val r = Runnable {
            val n = burstN; val hadBack = burstHadBack
            burstN = 0; burstR = null; burstHadBack = false
            when {
                n >= 3 -> glView.queueEvent { game.tripleTap() }
                n == 2 -> if (!hadBack) glView.queueEvent { game.doubleTap() }
                else -> if (!hadBack) { lastTapMs = SystemClock.uptimeMillis(); glView.queueEvent { game.tap() } }
            }
        }
        burstR = r
        ui.postDelayed(r, 300)
    }

    /** Drop a burst in flight: a classified key has superseded it, so the touches were echoes. */
    private fun cancelBurst() {
        burstR?.let { ui.removeCallbacks(it) }
        burstR = null; burstN = 0; burstHadBack = false
    }

    private fun onBack() {
        val now = SystemClock.uptimeMillis()
        lastKeyGestureMs = now
        if (burstR != null) burstHadBack = true
        if (backPendingMs != 0L && now - backPendingMs < 350) return
        backPendingMs = now
        val r = Runnable { backPendingMs = 0L; pendingDouble = null; glView.queueEvent { game.doubleTap() } }
        pendingDouble = r
        ui.postDelayed(r, 350)
    }

    private fun onSwipe(dir: Swipe) = glView.queueEvent { game.swipe(dir) }

    /**
     * Engage the sustained drive. [sign] is -1 for forward (the finger went UP the pad) and +1 for
     * back. The owner check means a drive belongs to whichever input path started it: should this
     * hardware ever deliver one physical gesture down BOTH the touch and the D-pad paths, the
     * second one cannot double the dash or steal the release.
     */
    private fun startDrive(sign: Int, source: Int) {
        if (driveOwner != SRC_NONE && driveOwner != source) return
        driveOwner = source
        val fwd = sign < 0
        val tag = if (source == SRC_TOUCH) "touch" else "key"
        glView.queueEvent { game.driveStart(fwd, tag) }
    }

    private fun endDrive(source: Int) {
        if (driveOwner != source) return
        driveOwner = SRC_NONE
        val tag = if (source == SRC_TOUCH) "touch" else "key"
        glView.queueEvent { game.driveEnd(tag) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && event.eventTime - event.downTime < 450) {
                    padTapsAreKeys = true; cancelBurst()
                    lastKeyGestureMs = SystemClock.uptimeMillis(); onTap(true)
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP) onBack(); return true }
            // The D-pad path is the same two gears as the pad: a key held down drives, a key tapped
            // is a dash and nothing else, because a drive that starts and ends inside one short
            // press has only ever applied [Game.IMPULSE]. Off the arena it stays a single discrete
            // step delivered on key-up, so the menu is unaffected.
            KeyEvent.KEYCODE_DPAD_UP -> { driveKey(event, -1, Swipe.UP); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { driveKey(event, 1, Swipe.DOWN); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_UP) onSwipe(turnDir(-1)); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_UP) onSwipe(turnDir(1)); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun driveKey(event: KeyEvent, sign: Int, fallback: Swipe) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && game.holdDriveArmed) startDrive(sign, SRC_KEY)
            KeyEvent.ACTION_UP -> if (driveOwner == SRC_KEY) endDrive(SRC_KEY) else onSwipe(fallback)
        }
    }

    /** Horizontal swipes quarter-turn the hull; the pad's raw dx sign is a per-owner call, so the setting flips it. */
    private fun turnDir(sign: Int): Swipe {
        val s = if (store.turnReversed) -sign else sign
        return if (s < 0) Swipe.LEFT else Swipe.RIGHT
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.device?.name?.contains("cyttsp6", ignoreCase = true) == true) return true
        val thresh = max(48f, 0.09f * resources.displayMetrics.widthPixels)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downT = SystemClock.uptimeMillis()
                gestureAxis = 0; driveSign = 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureAxis == AXIS_UP) return true         // already settled: nothing to watch for
                if (gestureAxis == 0) {
                    val dx = ev.x - downX; val dy = ev.y - downY
                    if (hypot(dx, dy) < thresh) return true
                    // Off the arena — title, game over, and above all the settings menu — the pad
                    // reverts to being read once, on finger-up. That is the whole guarantee that a
                    // held pad cannot walk the menu.
                    gestureAxis = if (!game.holdDriveArmed || abs(dx) >= abs(dy)) AXIS_UP else AXIS_DRIVE
                    if (gestureAxis == AXIS_UP) return true
                    driveSign = if (dy < 0) -1 else 1
                    driveExtremeY = ev.y
                    startDrive(driveSign, SRC_TOUCH)
                } else {
                    // REVERSING UNDER THE FINGER. Measured from the furthest point reached in the
                    // current direction rather than from the touch-down, so flipping always costs
                    // the same drag whether you reversed after 10 px or after the length of the
                    // pad — and the hysteresis is what stops a wobble at the end of a hold from
                    // sending the tank backwards.
                    val hys = thresh * 0.6f
                    if (driveSign < 0) {
                        if (ev.y < driveExtremeY) driveExtremeY = ev.y
                        if (ev.y - driveExtremeY >= hys) { driveSign = 1; driveExtremeY = ev.y; startDrive(1, SRC_TOUCH) }
                    } else {
                        if (ev.y > driveExtremeY) driveExtremeY = ev.y
                        if (driveExtremeY - ev.y >= hys) { driveSign = -1; driveExtremeY = ev.y; startDrive(-1, SRC_TOUCH) }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureAxis == AXIS_DRIVE) { endDrive(SRC_TOUCH); gestureAxis = 0; return true }
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) return true
                // THE ORIGINAL CLASSIFIER, UNTOUCHED. It still runs for every horizontal gesture,
                // for everything off the arena, and — the case that matters — for a flick so fast
                // that the threshold was never crossed by an ACTION_MOVE at all, which is how a
                // batched or single-sample gesture still dashes instead of doing nothing.
                val dx = ev.x - downX; val dy = ev.y - downY
                val dist = hypot(dx, dy)
                if (dist >= thresh) {
                    if (abs(dx) >= abs(dy)) onSwipe(turnDir(if (dx < 0) -1 else 1))
                    else onSwipe(if (dy < 0) Swipe.UP else Swipe.DOWN)
                } else if (SystemClock.uptimeMillis() - downT < 400) {
                    // Once taps are known to arrive as keys this is pure echo and is dropped. Until
                    // then the touch IS the tap, and where it goes depends on what a late tap would
                    // cost: in the arena, instantly, because a shot that arrives 300 ms after you
                    // asked for it is not the shot you asked for — and because breaking a capture
                    // is a mashing contest. Off the arena, into a burst that can still turn out to
                    // have been half of a double-tap.
                    if (!padTapsAreKeys && SystemClock.uptimeMillis() - lastKeyGestureMs > 600) {
                        if (game.tapsAreUrgent) onTap(false) else touchTap()
                    }
                }
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
        // A finger still on the pad when the window goes away never delivers its ACTION_UP.
        if (driveOwner != SRC_NONE) endDrive(driveOwner)
        gestureAxis = 0
        head.stop()
        sfx.stopHum()
        music.pause()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release(); voice.release(); hero.release(); music.release()
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
