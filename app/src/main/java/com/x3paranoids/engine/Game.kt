package com.x3paranoids.engine

import com.x3paranoids.SettingsStore
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Everything the game asks of the device. Implemented by MainActivity; every call is safe from the GL thread. */
interface GameHost {
    fun sfx(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun hum(level: Float, rate: Float)
    fun say(id: String, urgent: Boolean = false)
    fun sayAll(ids: List<String>)
    fun stopVoice()
    /** The PILOT track (assets/voice_hero). [patienceMs] is how long the line will wait for the floor. */
    fun hero(id: String, patienceMs: Long = 1500L)
    fun stopHero()
    fun musicEnabled(on: Boolean)
    fun voiceEnabled(on: Boolean)
    fun headEnabled(on: Boolean)
    fun recentreHead()
    fun applyVolume(v0to10: Int)
    fun voiceDurationMs(id: String): Int
    fun heroDurationMs(id: String): Int
    /** True while EITHER voice is speaking — ambient chatter stands aside rather than ducking under it. */
    fun voiceBusy(): Boolean
}

enum class State { TITLE, PLAY, WAVE_CLEAR, DYING, GAME_OVER }
enum class Swipe { FORWARD, BACK, UP, DOWN, LEFT, RIGHT }

class Recognizer(var x: Float, var z: Float) {

    /**
     * THE SILHOUETTE IS THE COLLIDER. These are the Recognizer's half-extents in its own frame, at
     * scale 1, and gl/GLRenderer.buildRecognizer draws from these same numbers — because the two
     * drifting apart is precisely how it ended up walking its cross-bar through walls: the bar was
     * drawn 1.65 units out either side and the thing moved on a collision radius of 1.2, so any
     * Recognizer hugging a wall buried nearly half a metre of itself in it.
     *
     * [RADIUS] is the circle that contains the whole model AT ANY YAW — the corner of the foot
     * flare, which is the furthest point on it from the axle. Widening the collider to the model
     * was the right way round to reconcile them: the Recognizer is a gantry that straddles a
     * corridor, and shrinking it to fit a 1.2 circle would have taken a third off the width of the
     * one silhouette the cabinet is remembered for. It still fits everywhere it needs to — a
     * corridor's clear span is CELL − 2·THICK = 8.3 units, so a 3.8-wide Recognizer walks it with
     * 2.2 units of daylight on each side, and a wave spawns it at a cell centre, 4.15 units clear.
     */
    companion object {
        /** Cross-bar half-width. */
        const val BAR_HW = 1.65f
        /** Leg centres, either side of the axle. */
        const val LEG_X = 1.35f
        /** How far a foot flares outboard of its leg — the widest point on the model. */
        const val FOOT_FLARE = 0.5f
        /** Half-depth: the bar and the feet, the deepest parts, both reach half a unit. */
        const val HALF_D = 0.5f
        /** Half-width of the whole machine. */
        const val HALF_W = LEG_X + FOOT_FLARE
        /** The collision radius: no part of the model may end up inside a wall, at any heading. */
        val RADIUS = sqrt(HALF_W * HALF_W + HALF_D * HALF_D)
    }

    var y = 1.6f
    var yaw = 0f
    var hp = 1
    var alert = 0f          // 0 patrol green … 1 hunting red
    var hunting = false
    /**
     * How much of this machine the periscope can actually see, 0..1 — the renderer's alpha, and its
     * whole occlusion test (see GLRenderer's OCCLUSION note). It ramps rather than switching so a
     * Recognizer crossing a doorway de-rezzes over about a tenth of a second instead of strobing on
     * the wall edge. Starts at 0: a machine spawns hidden and fades in only if it is genuinely in
     * sight, which is cheaper to reason about than spawning it lit and hoping.
     */
    var vis = 0f
    /** True on the frames this Recognizer actually has the tank in its sights — it can shoot you NOW. */
    var hasLos = false
    var seenT = -99f        // last time the player was in sight
    var fireCd = 2f
    var hitFlash = 0f
    var phase = Random.nextFloat() * 6.28f
    var targetC = -1; var targetR = -1
    var lockSaid = false
}

class Shot(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, val friendly: Boolean) {
    var life = if (friendly) 1.6f else 2.4f
}

class Spark(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, var life: Float, val r: Float, val g: Float, val b: Float)

/**
 * X3Paranoids — a first-person tank in a maze of light, hunting Recognizers. Head motion aims the
 * periscope, swipes move the hull relative to where you look, a tap fires. Pure logic; the renderer
 * reads its fields, the host does the sounds.
 */
class Game(val store: SettingsStore, private val host: GameHost) {

    companion object {
        const val EYE_H = 1.5f
        const val PLAYER_R = 0.9f
        const val IMPULSE = 7.5f
        const val MAX_SPEED = 9f
        const val SHELL_SPEED = 46f
        const val BOLT_SPEED = 21f
        const val FIRE_CD = 0.28f
        /** rad/s: a quarter turn lands in ~0.3 s — decisive, but never a snap on a head-worn display. */
        const val TURN_RATE = 5.2f
        /**
         * How far clear of a corner a Recognizer must be before it will fire past it. Sized so it
         * can never wrongly block a legitimate shot: it grows a wall's box to 0.95 units off the
         * wall plane, and neither party can ever stand that close to one — the tank is held at
         * PLAYER_R + THICK = 1.25 and a Recognizer at Recognizer.RADIUS + THICK = 2.27 — so a shot
         * taken straight down a corridor, hugging the wall, still reads as clear.
         */
        const val FIRE_PAD = 0.6f
        /** Centre-to-centre range at which a Recognizer is riding the tank down. */
        const val RAM_D = 2.3f
        /** How hard a ram throws the pair apart — spent on the Recognizer first, then on the tank. */
        const val RAM_PUSH = 2.5f
        /**
         * How fast a thing fades in or out of sight as a wall clears or closes, in units of alpha
         * per second — about a tenth of a second end to end. Fast enough that nothing is ever
         * meaningfully drawn through a wall, slow enough that a machine hunting you along a row of
         * doorways de-rezzes and re-rezzes instead of flickering.
         */
        const val VIS_RATE = 9f
        /** How many derezzes may be coming apart at once. Four × 60 segments is the whole budget. */
        const val MAX_DEREZ = 4
        /** Gravity on a falling fragment — heavier than real, so debris settles inside its own life. */
        const val FRAG_G = 13f
        /** How long the tank's death runs before GAME OVER. The sight has to fail visibly first. */
        const val DYING_T = 3.4f
        val INTRO = listOf("intro_1", "intro_2", "intro_3", "intro_4", "intro_5", "intro_6", "intro_7", "intro_8")
        val INTRO_TEXT = listOf(
            "GREETINGS, PROGRAM.",
            "A PROGRAMMER WROTE A GAME IN A BASEMENT.",
            "THE GAME WAS STOLEN.",
            "THE THIEF BUILT THE MONOPOLY CONTROL PROTOCOL ON ITS BONES.",
            "NOW THE RECOGNIZERS HUNT WHOEVER REMEMBERS THE ORIGINAL CODE.",
            "YOU REMEMBER.",
            "FIND THE BIT. SURVIVE THE WAVES.",
            "END OF LINE.",
        )
    }

    // ------------------------------------------------------------------ state
    var state = State.TITLE; private set
    var time = 0f; private set
    var stateT = 0f; private set
    var menuOpen = false; private set
    var maze = Maze(8, 8, 1L); private set
    private var mazeSeed = 1L

    // player
    var px = 0f; var pz = 0f
    var vx = 0f; var vz = 0f
    var yaw = 0f; var pitch = 0f          // set from the head tracker each frame (or swipe-turned)
    /** The hull's facing: quarter-turned by swipes, eased toward [hullTarget] at TURN_RATE. */
    var hullYaw = 0f; private set
    private var hullTarget = 0f
    /** 0..1 while a quarter turn is in flight — the HUD leans its brackets into the turn. */
    var turnBlend = 0f; private set
    var lives = 3; private set
    var score = 0; private set
    var wave = 0; private set
    var elapsed = 0f; private set
    var invuln = 0f; private set
    var damageFlash = 0f; private set
    var muzzle = 0f; private set
    private var fireCd = 0f
    var kills = 0; private set
    var waveTotal = 0; private set
    val recognizersLeft get() = recognizers.count { it.hp > 0 }
    var lockedOn = false; private set
    var bonusText = ""; private set

    val recognizers = ArrayList<Recognizer>()
    val shots = ArrayList<Shot>()
    val sparks = ArrayList<Spark>()
    /** Everything currently coming apart — see [Derez]. Drained by [updateDerez]. */
    val derezzes = ArrayList<Derez>()
    /** How far the periscope has sunk through the tank's own death, 0 … ~1.15 units. */
    var deathSink = 0f; private set
    var bitX = 0f; var bitZ = 0f; var bitActive = false; private set
    var bitT = 0f; private set
    /** The Bit's share of the same sight ramp. It does not move, so it only ever changes as you do. */
    var bitVis = 0f; private set

    // title / intro
    var introLine = -1; private set      // index of the lore line being spoken; -1 none yet; 8 = done
    var showTap = false; private set
    private var introFallback = 0f
    private var newHigh = false

    // menu
    val menuItems = listOf("MUSIC", "VOLUME", "VOICE", "HEAD LOOK", "MINIMAP", "TURN", "DIFFICULTY", "RESET SETTINGS")
    var menuSel = 0; private set
    var resetArmed = false; private set
    fun menuValue(i: Int): String = when (i) {
        0 -> if (store.music) "ON" else "OFF"
        1 -> store.volume.toString()
        2 -> if (store.voice) "ON" else "OFF"
        3 -> if (store.headLook) "ON" else "OFF"
        4 -> if (store.minimap) "ON" else "OFF"
        5 -> if (store.turnReversed) "REVERSED" else "NORMAL"
        6 -> if (store.difficulty == 1) "HARD" else "NORMAL"
        else -> if (resetArmed) "TAP AGAIN TO CONFIRM" else ""
    }

    private val rng = Random(System.nanoTime())
    private val rnd: () -> Float = { rng.nextFloat() }
    private val tmp = FloatArray(2)
    private var lastKillSay = -99f
    private var humLevel = 0f

    // ------------------------------------------------------------------ timed beats
    /**
     * A one-shot scheduled on the GL thread. It is how the two voices ANSWER each other: the system
     * states a fact, and the pilot's retort is posted for the moment the system finishes saying it.
     * Doing it here rather than off the voice thread's completion callback keeps every decision the
     * game makes on one thread, and lets a beat be cancelled wholesale when the state changes.
     */
    private class Cue(var t: Float, val run: () -> Unit)
    private val cues = ArrayList<Cue>()
    private fun cue(t: Float, run: () -> Unit) { cues += Cue(t, run) }
    private fun clearCues() { cues.clear() }
    private fun runCues(dt: Float) {
        if (cues.isEmpty()) return
        var i = 0
        while (i < cues.size) {
            val c = cues[i]
            c.t -= dt
            if (c.t <= 0f) { cues.removeAt(i); c.run() } else i++
        }
    }

    // ------------------------------------------------------------------ the pilot's voice
    /**
     * RESTRAINT IS THE WHOLE CRAFT HERE. Twenty-one lines will not survive a game that fires them
     * whenever their trigger happens: a pilot who comments on every kill is wallpaper by the end of
     * wave one, and the second time you hear the same quip it stops being a person and becomes a
     * sound effect. So every pilot line passes four gates before it is allowed to exist:
     *
     *  - [gap]    seconds since ANY pilot line. The floor is [PILOT_GAP]; nothing beats it. This is
     *             the single most important number in the mix — it is what makes the pilot someone
     *             who occasionally speaks rather than a commentary track.
     *  - [cd]     seconds since THIS line. Repetition is what kills a small script, so the same
     *             clip is locked out far longer than the gap.
     *  - [chance] a coin. Two identical situations giving different results is what makes a voice
     *             feel like it CHOSE to speak.
     *  - [once]   for the lines that only land the first time: the opening, the last life, the end.
     *
     * [delay] is the conversation. The system's line is queued the instant the event happens; the
     * pilot's answer is scheduled for when that line has FINISHED, and carries enough patience
     * ([VoiceBus]) to wait out any overrun. The alternative — firing both at once and letting the
     * bus arbitrate — produces the same words in an accidental order, which is not a conversation.
     */
    private val pilotLast = HashMap<String, Float>()
    private val pilotOnce = HashSet<String>()
    private var pilotLastAny = -99f
    private var pilotStreak = 0
    private var pilotKillIdx = 0
    private var lastKillT = -99f
    private var hitsRecent = 0
    private var lastHitT = -99f

    // the Bit's own clocks
    private var bitChirpCd = 0f
    private var bitNoCd = 0f
    private var bitNearSaid = false

    /** No two pilot lines closer together than this, ever. */
    private val PILOT_GAP = 7f

    private fun pilot(id: String, gap: Float = PILOT_GAP, cd: Float = 24f, chance: Float = 1f,
                      once: Boolean = false, delay: Float = 0f, patience: Long = 1500L): Boolean {
        if (!store.voice) return false
        if (once && id in pilotOnce) return false
        if (time - pilotLastAny < gap) return false
        if (time - (pilotLast[id] ?: -999f) < cd) return false
        if (chance < 1f && rng.nextFloat() > chance) return false
        pilotLastAny = time; pilotLast[id] = time
        if (once) pilotOnce += id
        if (delay > 0f) cue(delay) { host.hero(id, patience) } else host.hero(id, patience)
        return true
    }

    /** How long the system voice will be busy saying these, plus a beat of air. */
    private fun after(vararg ids: String): Float {
        var ms = 0
        for (id in ids) ms += max(400, host.voiceDurationMs(id))
        return ms / 1000f + 0.3f
    }

    /** The same, for a pilot line. */
    private fun afterHero(id: String): Float = max(400, host.heroDurationMs(id)) / 1000f + 0.3f

    /**
     * SHIELD BOOSTERS are a later phase; these are the hooks their three pilot lines hang on, wired
     * to the same rate limiter as everything else so that phase has nothing to invent. Call them
     * when the pickup lands, when a shield eats a bolt, and when the last of it goes.
     */
    fun onShieldUp() { host.sfx(com.x3paranoids.audio.Sfx.BIT_YES, 0.8f, 0.7f); pilot("hero_shield_up", cd = 40f, chance = 0.8f) }
    fun onShieldHit() { pilot("hero_shield_hit", cd = 25f, chance = 0.45f) }
    fun onShieldDown() { pilot("hero_shield_down", cd = 30f, chance = 0.9f) }

    // ------------------------------------------------------------------ boot / title
    fun boot() { enterTitle() }

    private fun enterTitle() {
        state = State.TITLE; stateT = 0f
        introLine = -1; showTap = false; introFallback = 0f
        recognizers.clear(); shots.clear(); sparks.clear(); derezzes.clear(); bitActive = false
        deathSink = 0f
        clearCues()
        host.stopHero(); host.stopVoice()
        if (store.voice) host.sayAll(INTRO) else introLine = 0
    }

    /** From the voice thread: a lore line began. */
    fun onVoiceLineStart(id: String) { val i = INTRO.indexOf(id); if (i >= 0 && state == State.TITLE) introLine = i }
    fun onVoiceLineEnd(id: String) { if (id == INTRO.last() && state == State.TITLE) { introLine = INTRO.size; showTap = true } }

    // ------------------------------------------------------------------ input (GL thread)
    fun tap() {
        if (menuOpen) { menuActivate(); return }
        when (state) {
            State.TITLE -> startGame()
            State.PLAY -> fire()
            State.GAME_OVER -> if (stateT > 1.2f) enterTitle()
            else -> {}
        }
    }

    fun doubleTap() {
        if (state == State.TITLE || state == State.GAME_OVER) { if (menuOpen) closeMenu() else openMenu(); return }
        if (menuOpen) closeMenu() else openMenu()
    }

    fun tripleTap() {
        if (menuOpen) return
        host.recentreHead(); hullYaw = 0f; hullTarget = 0f; turnBlend = 0f
        host.say("recentred")
        host.sfx(com.x3paranoids.audio.Sfx.TICK, 1.3f)
    }

    fun swipe(dir: Swipe) {
        if (menuOpen) { menuSwipe(dir); return }
        if (state != State.PLAY) return
        val fx = sin(yaw); val fz = -cos(yaw)
        when (dir) {
            Swipe.UP -> impulse(fx, fz)
            Swipe.DOWN -> impulse(-fx, -fz)
            // A tank turns; it does not sidestep. One swipe = one quarter turn of the hull, which is
            // also how the maze is laid out (every corridor is a right angle), so a corner is always
            // exactly one gesture. The turn is EASED, not snapped: a 90 degrees jump on a head-worn
            // display is the single most nauseating thing a game can do.
            Swipe.RIGHT, Swipe.FORWARD -> turn(+1)
            Swipe.LEFT, Swipe.BACK -> turn(-1)
        }
    }

    /** Quarter-turn the hull. Queues, so two fast swipes turn 180 degrees. */
    private fun turn(sign: Int) {
        hullTarget += sign * (PI.toFloat() / 2f)
        host.sfx(com.x3paranoids.audio.Sfx.TURN, 0.95f + rng.nextFloat() * 0.12f)
    }

    private fun impulse(dx: Float, dz: Float) {
        vx += dx * IMPULSE; vz += dz * IMPULSE
        val s = hypot(vx, vz)
        if (s > MAX_SPEED) { vx *= MAX_SPEED / s; vz *= MAX_SPEED / s }
        host.sfx(com.x3paranoids.audio.Sfx.THRUST, 0.9f + rng.nextFloat() * 0.2f, 0.6f)
    }

    private fun fire() {
        if (fireCd > 0f) return
        fireCd = FIRE_CD; muzzle = 1f
        val cp = cos(pitch)
        val dx = sin(yaw) * cp; val dy = sin(pitch); val dz = -cos(yaw) * cp
        // the shell leaves the barrel below and ahead of the periscope, so it reads as the cabinet's beam
        shots += Shot(px + dx * 1.2f, EYE_H - 0.45f + dy * 1.2f, pz + dz * 1.2f, dx * SHELL_SPEED, dy * SHELL_SPEED, dz * SHELL_SPEED, true)
        host.sfx(com.x3paranoids.audio.Sfx.FIRE, 0.95f + rng.nextFloat() * 0.1f)
    }

    // ------------------------------------------------------------------ menu
    private fun openMenu() { menuOpen = true; menuSel = 0; resetArmed = false; host.say("paused", urgent = true); host.sfx(com.x3paranoids.audio.Sfx.SELECT) }
    private fun closeMenu() { menuOpen = false; resetArmed = false; if (state == State.PLAY) host.say("resumed", urgent = true); host.sfx(com.x3paranoids.audio.Sfx.TICK) }

    private fun menuSwipe(dir: Swipe) {
        when (dir) {
            Swipe.UP -> { menuSel = (menuSel + menuItems.size - 1) % menuItems.size; resetArmed = false; host.sfx(com.x3paranoids.audio.Sfx.TICK) }
            Swipe.DOWN -> { menuSel = (menuSel + 1) % menuItems.size; resetArmed = false; host.sfx(com.x3paranoids.audio.Sfx.TICK) }
            Swipe.FORWARD, Swipe.RIGHT -> adjust(+1)
            Swipe.BACK, Swipe.LEFT -> adjust(-1)
        }
    }

    private fun adjust(d: Int) {
        when (menuSel) {
            0 -> { store.music = !store.music; host.musicEnabled(store.music) }
            1 -> { store.volume = store.volume + d; host.applyVolume(store.volume) }
            2 -> { store.voice = !store.voice; host.voiceEnabled(store.voice) }
            3 -> { store.headLook = !store.headLook; host.headEnabled(store.headLook) }
            4 -> store.minimap = !store.minimap
            5 -> store.turnReversed = !store.turnReversed
            6 -> store.difficulty = 1 - store.difficulty
            else -> {}
        }
        host.sfx(com.x3paranoids.audio.Sfx.TICK, 1.15f)
    }

    private fun menuActivate() {
        if (menuSel == menuItems.size - 1) {
            if (!resetArmed) { resetArmed = true; host.sfx(com.x3paranoids.audio.Sfx.LOCK); return }
            store.resetSettings(); resetArmed = false
            host.musicEnabled(store.music); host.voiceEnabled(store.voice); host.headEnabled(store.headLook); host.applyVolume(store.volume)
            host.sfx(com.x3paranoids.audio.Sfx.SELECT); return
        }
        adjust(+1)
    }

    // ------------------------------------------------------------------ game flow
    private fun startGame() {
        host.stopHero(); host.stopVoice()
        clearCues()
        mazeSeed = System.nanoTime(); maze = Maze(8, 8, mazeSeed)
        lives = 3; score = 0; wave = 0; elapsed = 0f; kills = 0; invuln = 0f; damageFlash = 0f
        vx = 0f; vz = 0f; hullYaw = 0f; hullTarget = 0f; turnBlend = 0f; newHigh = false
        derezzes.clear(); deathSink = 0f
        pilotLast.clear(); pilotOnce.clear(); pilotLastAny = -99f; pilotStreak = 0; pilotKillIdx = 0
        lastKillT = -99f; lastKillSay = -99f
        hitsRecent = 0; lastHitT = -99f; bitNearSaid = false; bitChirpCd = 1.4f; bitNoCd = 0f
        store.games = store.games + 1
        placePlayer(maze.cols / 2, maze.rows / 2)
        host.recentreHead()
        host.sfx(com.x3paranoids.audio.Sfx.START)
        nextWave()
    }

    private fun placePlayer(c: Int, r: Int) { px = maze.cellX(c); pz = maze.cellZ(r); vx = 0f; vz = 0f }

    private fun nextWave() {
        wave++
        state = State.PLAY; stateT = 0f
        shots.clear(); recognizers.clear()
        if (wave > 1 && (wave - 1) % 3 == 0) { mazeSeed += 7919L; maze = Maze(8, 8, mazeSeed); placePlayer(maze.cols / 2, maze.rows / 2) }
        val hard = store.difficulty == 1
        val n = min(2 + wave, 9)
        waveTotal = n; kills = 0
        val pc = maze.colOf(px); val pr = maze.rowOf(pz)
        val dist = maze.distances(pc, pr)
        val far = ArrayList<IntArray>()
        for (c in 0 until maze.cols) for (r in 0 until maze.rows) if (dist[c][r] >= 4) far += intArrayOf(c, r)
        far.shuffle(rng)
        for (i in 0 until n) {
            val cell = far[i % far.size]
            // The jitter keeps a wave from lining up on cell centres. It cannot currently put a
            // Recognizer in a wall — a cell centre is 4.15 units clear and the jitter is at most 1 —
            // but this is the one place in the game a body is positioned without going through
            // maze.move, so it is checked rather than reasoned about: land in a wall and take the
            // centre instead. If RADIUS or CELL is ever retuned, this stays honest by itself.
            var sx = maze.cellX(cell[0]) + (rng.nextFloat() - 0.5f) * 2f
            var sz = maze.cellZ(cell[1]) + (rng.nextFloat() - 0.5f) * 2f
            if (maze.inWall(sx, sz, Recognizer.RADIUS)) { sx = maze.cellX(cell[0]); sz = maze.cellZ(cell[1]) }
            val rec = Recognizer(sx, sz)
            rec.hp = if (hard || wave >= 6) 2 else 1
            rec.fireCd = 2f + rng.nextFloat() * 2f
            recognizers += rec
        }
        // the Bit hides somewhere far
        val bitCells = far.filter { dist[it[0]][it[1]] >= 3 }
        val bc = if (bitCells.isNotEmpty()) bitCells[rng.nextInt(bitCells.size)] else far[0]
        bitX = maze.cellX(bc[0]); bitZ = maze.cellZ(bc[1]); bitActive = true; bitT = 0f
        bitNearSaid = false; bitChirpCd = 2.2f; bitNoCd = 3f
        host.sfx(com.x3paranoids.audio.Sfx.WAVE)
        val waveId = if (wave <= 12) "wave_$wave" else "wave_more"
        host.say(waveId, urgent = true)
        host.say("incoming")
        // THE FIRST CONVERSATION. The system announces the wave and says INCOMING; the pilot answers
        // it once the machine has finished talking. Wave one is the opening statement and always
        // lands; after that the answer is occasional, and from wave six it is the tired one.
        val answerAt = after(waveId, "incoming")
        val answered = when {
            wave == 1 -> pilot("hero_start", gap = 0f, once = true, delay = answerAt, patience = 6000L)
            wave >= 6 -> pilot("hero_wave_late", gap = 0f, cd = 50f, chance = 0.55f, delay = answerAt, patience = 5000L)
            else -> pilot("hero_wave", gap = 0f, cd = 45f, chance = 0.40f, delay = answerAt, patience = 5000L)
        }
        // Only when the wave line did NOT fire: the villain gets named out loud, once a game and
        // never early. A flourish stops being one the moment it is on a schedule.
        if (!answered && wave >= 4) pilot("hero_mcp", gap = 0f, chance = 0.22f, once = true, delay = answerAt, patience = 5000L)
    }

    private fun waveCleared() {
        state = State.WAVE_CLEAR; stateT = 0f
        val bonus = 250 * wave
        score += bonus; bonusText = "BONUS $bonus"
        store.bestWave = wave
        host.sfx(com.x3paranoids.audio.Sfx.CLEAR)
        host.say("wave_clear", urgent = true)
        // THE BIT WAS LEFT BEHIND. It has been chirping at you for a whole wave; if you never came,
        // it says so — the one reaction that makes it a character with an opinion about you rather
        // than a pickup you happened not to collect.
        if (bitActive) {
            bitActive = false
            cue(after("wave_clear") - 0.15f) { host.sfx(com.x3paranoids.audio.Sfx.BIT_LOSE, 1f, 0.75f) }
        }
        // The pilot answers the clear; failing that, it sometimes just thinks out loud in the quiet.
        val at = after("wave_clear")
        pilot("hero_wave_clear", gap = 5f, cd = 40f, chance = 0.70f, delay = at, patience = 4000L) ||
            pilot("hero_quiet", gap = 5f, cd = 90f, chance = 0.45f, delay = at, patience = 4000L)
    }


    private fun damagePlayer() {
        if (invuln > 0f || state != State.PLAY) return
        lives--
        damageFlash = 1f; invuln = 2.6f
        vx *= 0.3f; vz *= 0.3f
        hitsRecent = if (time - lastHitT < 20f) hitsRecent + 1 else 1
        lastHitT = time
        if (lives <= 0) {
            state = State.DYING; stateT = 0f; deathSink = 0f
            // NOTHING THE PILOT HAD LINED UP STILL APPLIES. Watched on the glasses: a hero_last_life
            // queued four seconds earlier, held off the floor all that time by the system's LOCKON
            // chatter, finally landed ON the death — and then ran long enough that the urgent GAME
            // OVER cut off the derez retort behind it. The death is one of three beats in this game
            // that are deliberately timed, so it clears the decks first: pending cues go, and
            // anything the pilot is mid-way through stops.
            clearCues()
            host.stopHero()
            host.sfx(com.x3paranoids.audio.Sfx.DIE)
            host.say("derezzed", urgent = true)
            // THE PLAYER'S OWN DEREZ. The hull comes apart around the periscope (see Derez.seedPlayer)
            // and the sight fails on top of it in the renderer. The sparks are only grit now — the
            // structure leaving you is what the moment is made of.
            derezzes += Derez(px, EYE_H, pz, yaw, 1f, 1f, true).also { it.seedPlayer(rnd) }
            burst(px, EYE_H, pz, 26, 1f, 0.35f, 0.3f)
            // "DEREZZED," says the machine. The pilot has the last word over its own death.
            pilot("hero_derez", gap = 0f, cd = 0f, delay = after("derezzed"), patience = 3000L)
        } else {
            host.sfx(com.x3paranoids.audio.Sfx.HIT)
            val sysId = if (lives == 1) "last_life" else "hit"
            host.say(sysId, urgent = true)
            val at = after(sysId)
            when {
                // The last life is the one damage beat that always gets an answer — but it is a
                // REACTION, so it does not loiter. If the machine is still talking two and a half
                // seconds later the moment has gone, and the line is better dropped than delivered
                // over whatever happened next.
                lives == 1 -> pilot("hero_last_life", gap = 0f, once = true, delay = at, patience = 2500L)
                hitsRecent >= 2 -> pilot("hero_hit_bad", gap = 9f, cd = 26f, chance = 0.60f, delay = at, patience = 2500L)
                else -> pilot("hero_hit", gap = 9f, cd = 22f, chance = 0.35f, delay = at, patience = 2000L)
            }
        }
    }

    /**
     * THE LAST CONVERSATION, and the one worth timing by hand. The machine pronounces the ending;
     * the pilot answers it; and only then does the machine get its END OF LINE. A new high score
     * opens the exchange out to five beats, alternating, which is the closest the two of them ever
     * come to actually talking. Everything after the first line is scheduled rather than queued, so
     * the order is authored and not an accident of who reached the bus first.
     */
    private fun gameOver() {
        state = State.GAME_OVER; stateT = 0f
        host.hum(0f, 1f)
        newHigh = score > store.highScore && score > 0
        store.highScore = score
        host.sfx(com.x3paranoids.audio.Sfx.GAMEOVER)
        host.say("game_over", urgent = true)
        var t = after("game_over")
        cue(t) { host.hero("hero_game_over", 4000L) }
        t += afterHero("hero_game_over")
        if (newHigh) {
            cue(t) { host.sfx(com.x3paranoids.audio.Sfx.HISCORE); host.say("high_score") }
            t += after("high_score")
            cue(t) { host.hero("hero_high_score", 4000L) }
            t += afterHero("hero_high_score")
        }
        cue(t) { host.say("end_of_line") }
    }
    val isNewHigh get() = newHigh

    // ------------------------------------------------------------------ update
    fun update(dtRaw: Float, headYaw: Float, headPitch: Float, headOn: Boolean) {
        val dt = min(dtRaw, 0.05f)
        time += dt
        // The hull turns in quarter steps; the head is a periscope free-looking on top of it.
        var d = hullTarget - hullYaw
        val step = TURN_RATE * dt
        hullYaw = if (abs(d) <= step) hullTarget else hullYaw + step * (if (d > 0f) 1f else -1f)
        turnBlend = (abs(hullTarget - hullYaw) / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
        yaw = if (headOn) headYaw + hullYaw else hullYaw
        pitch = if (headOn) headPitch else 0f
        if (menuOpen) return
        stateT += dt
        muzzle = max(0f, muzzle - dt * 9f)
        damageFlash = max(0f, damageFlash - dt * 1.6f)
        runCues(dt)
        // Derez runs outside the state machine: a machine that broke apart a moment before the wave
        // cleared, or the tank's own hull leaving the seat, has to finish falling wherever it is.
        if (state != State.TITLE) updateDerez(dt)
        when (state) {
            State.TITLE -> {
                // voice off (or missing clips): advance the crawl on the manifest's own timing
                if (!store.voice && introLine in 0 until INTRO.size) {
                    introFallback += dt
                    val d = max(600, host.voiceDurationMs(INTRO[introLine])) / 1000f + 0.25f
                    if (introFallback >= d) { introFallback = 0f; introLine++; if (introLine >= INTRO.size) showTap = true }
                }
                if (stateT > 4f && !store.voice && introLine < 0) introLine = 0
            }
            State.PLAY -> updatePlay(dt)
            State.WAVE_CLEAR -> { updateWorld(dt, false); if (stateT > 3.2f) nextWave() }
            State.DYING -> {
                updateWorld(dt, false)
                // The periscope sinks as the hull goes: an eased 1.15 units over about two seconds.
                // Slow and monotonic on purpose — this is a head-worn display, and the one thing a
                // death must not do is throw the horizon around.
                deathSink = 1.15f * (1f - exp(-stateT * 1.3f))
                if (stateT > DYING_T) gameOver()
            }
            State.GAME_OVER -> {}
        }
    }

    private fun updatePlay(dt: Float) {
        elapsed += dt
        fireCd = max(0f, fireCd - dt)
        invuln = max(0f, invuln - dt)
        // hull physics: impulses decay, the maze walls slide
        val damp = exp(-dt / 0.55f)
        vx *= damp; vz *= damp
        val speed = hypot(vx, vz)
        if (speed > 0.02f) {
            val bumped = maze.move(px, pz, vx * dt, vz * dt, PLAYER_R, tmp)
            px = tmp[0]; pz = tmp[1]
            if (bumped && speed > 3f) { host.sfx(com.x3paranoids.audio.Sfx.BUMP, 0.9f + rng.nextFloat() * 0.2f, min(1f, speed / 9f)); vx *= 0.35f; vz *= 0.35f }
        }
        updateWorld(dt, true)
        if (bitActive) {
            bitT += dt
            val bitSeen = maze.lineOfSight(bitX, bitZ, px, pz)
            val step = dt * VIS_RATE
            bitVis = if (bitSeen) min(1f, bitVis + step) else max(0f, bitVis - step)
            val bd = hypot(px - bitX, pz - bitZ)
            updateBitVoice(dt, bd)
            // once per wave, and only when you can actually SEE it — the line is a confirmation,
            // not a hint, and a hint from behind a wall would undercut the chirps that are the hint
            if (!bitNearSaid && bd < 13f && bitSeen) {
                bitNearSaid = true
                pilot("hero_bit_near", cd = 35f, chance = 0.7f)
            }
            if (bd < 1.9f) {
                bitActive = false
                score += 500; lives = min(lives + 1, 5)
                burst(bitX, 1.4f, bitZ, 30, 0.4f, 1f, 1f)
                host.sfx(com.x3paranoids.audio.Sfx.BIT_GET)
                // it says YES on the way out — the Bit's one unambiguous word, on its one good day
                cue(0.24f) { host.sfx(com.x3paranoids.audio.Sfx.BIT_YES, 1f, 0.9f) }
                host.say("bit", urgent = true)
                pilot("hero_bit_get", gap = 4f, cd = 30f, chance = 0.6f, delay = after("bit"), patience = 3000L)
            }
        }
        if (recognizersLeft == 0) waveCleared()
    }

    /** Enemies, shots and sparks — also runs (frozen player) during wave-clear and death. */
    private fun updateWorld(dt: Float, hostile: Boolean) {
        val hard = store.difficulty == 1
        val speedBase = (3.0f + 0.25f * wave + (if (hard) 0.8f else 0f)).coerceAtMost(6.5f)
        var nearest = 999f
        lockedOn = false
        val it = recognizers.iterator()
        while (it.hasNext()) {
            val r = it.next()
            if (r.hp <= 0) { it.remove(); continue }
            r.hitFlash = max(0f, r.hitFlash - dt * 6f)
            r.y = 1.6f + 0.3f * sin(time * 2.1f + r.phase)
            val ddx = px - r.x; val ddz = pz - r.z
            val d = hypot(ddx, ddz)
            nearest = min(nearest, d)
            // CAN THE PERISCOPE SEE IT? The renderer draws nothing it cannot, so this is the whole of
            // the wall occlusion for entities. Three samples across the machine's own width — axle
            // and both ends of the cross-bar in its current heading — because it is 3.7 units wide
            // and a test on the axle alone would blink the thing out while a third of it is still
            // round the corner in plain sight. The local +x axis maps to world (cos yaw, -sin yaw),
            // matching GLRenderer.buildRecognizer exactly, so the samples sit on the drawn bar.
            val ec = cos(r.yaw) * Recognizer.HALF_W; val es = -sin(r.yaw) * Recognizer.HALF_W
            val seen = maze.lineOfSight(r.x, r.z, px, pz) ||
                maze.lineOfSight(r.x + ec, r.z + es, px, pz) ||
                maze.lineOfSight(r.x - ec, r.z - es, px, pz)
            val visStep = dt * VIS_RATE
            r.vis = if (seen) min(1f, r.vis + visStep) else max(0f, r.vis - visStep)
            val los = d < 34f && maze.lineOfSight(r.x, r.z, px, pz)
            if (los) r.seenT = time
            // SEEING YOU AND HAVING THE SHOT ARE TWO DIFFERENT TESTS. Sight is measured axle to
            // axle, so a Recognizer whose body is still mostly behind a corner has a centre that
            // can already see round it — and a bolt fired from there leaves the barrel inside the
            // wall and reads, fairly, as shooting through it. The shot gate re-runs the same test
            // with every wall grown by [FIRE_PAD], so it must be genuinely clear of the corner
            // before it will take the shot, while it still hunts you the moment it spots you.
            val fireLos = los && maze.lineOfSight(r.x, r.z, px, pz, FIRE_PAD)
            // The plate's spur and the WARNING both mean "this one can shoot you NOW", so they read
            // the shot gate, not the sight gate. Stepping behind a wall must visibly switch them off.
            r.hasLos = hostile && fireLos
            val chasing = hostile && (los || time - r.seenT < 5f)
            r.hunting = chasing
            r.alert += ((if (chasing) 1f else 0f) - r.alert) * (1f - exp(-dt / 0.45f))
            var mx = 0f; var mz = 0f
            if (chasing && los) {
                // stand off at ~6 u, circle a little, keep facing the tank
                val want = 6f
                val towards = if (d > want + 1f) 1f else if (d < want - 1.5f) -0.6f else 0f
                val nx = ddx / max(d, 0.01f); val nz = ddz / max(d, 0.01f)
                val side = sin(time * 0.7f + r.phase)
                mx = nx * towards + (-nz) * side * 0.5f
                mz = nz * towards + nx * side * 0.5f
                r.yaw = atan2(ddx, -ddz)
                if (hostile) {
                    r.fireCd -= dt
                    if (r.fireCd <= 0f && d < 26f && fireLos) {
                        r.fireCd = (2.6f - 0.15f * wave - (if (hard) 0.5f else 0f)).coerceAtLeast(1.1f)
                        val spread = (0.10f - 0.008f * wave).coerceAtLeast(0.03f)
                        val aimX = px + (rng.nextFloat() - 0.5f) * spread * d
                        val aimZ = pz + (rng.nextFloat() - 0.5f) * spread * d
                        val ax = aimX - r.x; val ay = EYE_H - 0.2f - (r.y + 0.8f); val az = aimZ - r.z
                        val al = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(0.01f)
                        shots += Shot(r.x, r.y + 0.8f, r.z, ax / al * BOLT_SPEED, ay / al * BOLT_SPEED, az / al * BOLT_SPEED, false)
                        host.sfx(com.x3paranoids.audio.Sfx.ENEMY_FIRE, 0.9f + rng.nextFloat() * 0.2f, (1f - d / 40f).coerceIn(0.3f, 1f))
                    }
                    if (d < 20f && fireLos) {
                        lockedOn = true
                        if (!r.lockSaid) { r.lockSaid = true; host.sfx(com.x3paranoids.audio.Sfx.LOCK, 1f, 0.7f); if (time - lastKillSay > 3f) host.say("lockon") }
                    }
                }
            } else {
                // patrol the corridors by cell; a chaser that lost sight paths to the tank's last cell
                r.lockSaid = false
                val c = maze.colOf(r.x); val rr = maze.rowOf(r.z)
                if (chasing) { r.targetC = maze.colOf(px); r.targetR = maze.rowOf(pz) }
                if (r.targetC < 0 || (c == r.targetC && rr == r.targetR)) { r.targetC = rng.nextInt(maze.cols); r.targetR = rng.nextInt(maze.rows) }
                val step = maze.stepToward(c, rr, r.targetC, r.targetR)
                if (step != null) {
                    val tx = maze.cellX(step[0]); val tz = maze.cellZ(step[1])
                    val dx = tx - r.x; val dz = tz - r.z; val l = hypot(dx, dz).coerceAtLeast(0.01f)
                    mx = dx / l; mz = dz / l
                    r.yaw = atan2(dx, -dz)
                } else { r.targetC = -1 }
            }
            val sp = speedBase * (if (chasing) 1.15f else 0.8f)
            if (mx != 0f || mz != 0f) {
                maze.move(r.x, r.z, mx * sp * dt, mz * sp * dt, Recognizer.RADIUS, tmp); r.x = tmp[0]; r.z = tmp[1]
            }
            // RAMMING. The recoil used to be written straight into r.x/r.z — a raw 2.5-unit
            // displacement with no collision test at all, which is a teleport: ram the tank with a
            // wall at your back and the recoil put the Recognizer through that wall and out the far
            // side. It goes through maze.move now, like every other metre this thing travels.
            //
            // But clipping the shove is only half of it, because this runs on EVERY frame the two
            // are touching, not once per hit. Clipped and left there, a Recognizer shoved straight
            // into a wall simply does not move — so it stands inside the tank and takes another life
            // every time the invulnerability lapses. So the shove SEPARATES the pair instead of
            // displacing one of them: the Recognizer gives way first, and whatever of the push a
            // wall behind it refuses is spent driving the TANK back by the remainder. Both halves go
            // through maze.move, so the two always come apart and neither travels through a wall to
            // do it. The direction is taken fresh from where the Recognizer stands NOW, not from the
            // (ddx, ddz) measured before this frame's step, so the shove is along the line you see.
            if (hostile && d < RAM_D && state == State.PLAY) {
                damagePlayer()
                val bx = r.x - px; val bz = r.z - pz
                val bl = hypot(bx, bz).coerceAtLeast(0.01f)
                val ux = bx / bl; val uz = bz / bl
                maze.move(r.x, r.z, ux * RAM_PUSH, uz * RAM_PUSH, Recognizer.RADIUS, tmp)
                val gave = hypot(tmp[0] - r.x, tmp[1] - r.z)
                r.x = tmp[0]; r.z = tmp[1]
                val rest = RAM_PUSH - gave
                if (rest > 0.01f) {
                    maze.move(px, pz, -ux * rest, -uz * rest, PLAYER_R, tmp)
                    px = tmp[0]; pz = tmp[1]
                }
            }
        }
        // hover hum follows the nearest Recognizer
        val target = if (recognizers.isEmpty() || state == State.GAME_OVER) 0f else (1f - nearest / 26f).coerceIn(0f, 1f)
        humLevel += (target - humLevel) * (1f - exp(-dt / 0.3f))
        host.hum(humLevel, 0.9f + 0.3f * humLevel)

        // shots
        val si = shots.iterator()
        while (si.hasNext()) {
            val s = si.next()
            s.life -= dt
            val nx = s.x + s.vx * dt; val ny = s.y + s.vy * dt; val nz = s.z + s.vz * dt
            val t = maze.rayHit(s.x, s.z, nx, nz)
            if (s.life <= 0f || t <= 1f || ny < 0f || ny > Maze.WALL_H + 2f) {
                if (t <= 1f) { burst(s.x + (nx - s.x) * t, ny, s.z + (nz - s.z) * t, 6, if (s.friendly) 1f else 1f, if (s.friendly) 0.9f else 0.3f, 0.3f); if (s.friendly) host.sfx(com.x3paranoids.audio.Sfx.RICOCHET, 1f, 0.5f) }
                si.remove(); continue
            }
            s.x = nx; s.y = ny; s.z = nz
            if (s.friendly) {
                for (r in recognizers) if (r.hp > 0 && hypot(s.x - r.x, s.z - r.z) < 1.9f && abs(s.y - (r.y + 1.3f)) < 2.2f) {
                    r.hp--; r.hitFlash = 1f
                    if (r.hp <= 0) {
                        kills++
                        score += 100 * wave * (if (hard) 3 else 2) / 2
                        spawnDerez(r)
                        // A handful of sparks at the break, no more. The fragments carry the death now;
                        // the old 36-dot puff on top of them was just a second, worse explosion.
                        burst(r.x, r.y + 1.2f, r.z, 12, 0.9f, 1f, 0.85f)
                        host.sfx(com.x3paranoids.audio.Sfx.DEREZ, 0.92f + rng.nextFloat() * 0.16f)
                        val said = time - lastKillSay > 4f
                        if (said) { lastKillSay = time; host.say("destroyed") }
                        onKill(said)
                    } else host.sfx(com.x3paranoids.audio.Sfx.RICOCHET, 0.7f)
                    si.remove(); break
                }
            } else if (hostile && state == State.PLAY && hypot(s.x - px, s.z - pz) < 1.15f && abs(s.y - EYE_H) < 1.6f) {
                si.remove(); damagePlayer()
            }
        }
        // sparks
        val pi = sparks.iterator()
        while (pi.hasNext()) {
            val p = pi.next()
            p.life -= dt
            if (p.life <= 0f) { pi.remove(); continue }
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
            p.vy -= 9f * dt
        }
    }

    // ------------------------------------------------------------------ derez
    /**
     * Overload, then fracture, then the grid. See [Derez] for the shape of the sequence; this is
     * only its physics.
     *
     * FRAGMENTS OBEY THE WALLS, like everything else in this game: the centre goes through
     * [Maze.move] on a small radius, so a Recognizer that derezzes against a wall throws its pieces
     * back off it instead of through it, and the debris of a death round a corner stays round the
     * corner. The renderer runs its own sight test per fragment, so it is never DRAWN through one
     * either.
     *
     * The landing is the part that ties the death to the room. A piece bounces once or twice with
     * most of its energy gone, and from the first touch it is [Frag.down]: its own length rotates
     * down into the horizontal (at constant length — it lies flat, it does not shrink), its spin
     * bleeds off, and it slides to a stop on the floor grid it will fade into.
     */
    private fun updateDerez(dt: Float) {
        if (derezzes.isEmpty()) return
        val di = derezzes.iterator()
        while (di.hasNext()) {
            val d = di.next()
            d.t += dt
            if (!d.broken) {
                if (d.t >= d.overload) { if (d.player) d.seedPlayer(rnd) else d.seedRecognizer(rnd) }
                continue
            }
            val fi = d.frags.iterator()
            while (fi.hasNext()) {
                val f = fi.next()
                f.life -= dt
                if (f.life <= 0f) { fi.remove(); continue }
                f.vy -= FRAG_G * dt
                // tumble: Rodrigues about the fragment's own axis
                if (abs(f.w) > 0.01f) {
                    val th = f.w * dt
                    val ct = cos(th); val st = sin(th)
                    val dot = f.ax * f.ex + f.ay * f.ey + f.az * f.ez
                    val crx = f.ay * f.ez - f.az * f.ey
                    val cry = f.az * f.ex - f.ax * f.ez
                    val crz = f.ax * f.ey - f.ay * f.ex
                    f.ex = f.ex * ct + crx * st + f.ax * dot * (1f - ct)
                    f.ey = f.ey * ct + cry * st + f.ay * dot * (1f - ct)
                    f.ez = f.ez * ct + crz * st + f.az * dot * (1f - ct)
                }
                // walls, on the same slide-and-stop the tank uses
                val bumped = maze.move(f.cx, f.cz, f.vx * dt, f.vz * dt, 0.14f, tmp)
                f.cx = tmp[0]; f.cz = tmp[1]
                if (bumped) { f.vx *= -0.30f; f.vz *= -0.30f; f.w *= 1.35f }
                f.cy += f.vy * dt
                val low = f.cy - abs(f.ey)
                if (low < 0.05f) {
                    f.cy += 0.05f - low
                    if (f.vy < 0f) { f.vy = -f.vy * 0.30f; f.vx *= 0.62f; f.vz *= 0.62f; f.w *= 0.5f }
                    if (abs(f.vy) < 0.7f) f.vy = 0f
                    f.down = true
                }
                if (f.down) {
                    // settle flat onto the grid, at constant length
                    val l0 = sqrt(f.ex * f.ex + f.ey * f.ey + f.ez * f.ez)
                    f.ey *= max(0f, 1f - dt * 3.4f)
                    val l1 = sqrt(f.ex * f.ex + f.ey * f.ey + f.ez * f.ez).coerceAtLeast(1e-4f)
                    val k = l0 / l1
                    f.ex *= k; f.ey *= k; f.ez *= k
                    val fr = max(0f, 1f - dt * 1.7f)
                    f.vx *= fr; f.vz *= fr; f.w *= max(0f, 1f - dt * 2.4f)
                }
            }
            if (d.frags.isEmpty()) di.remove()
        }
    }

    /**
     * The pilot on a kill. THREE kill lines and one streak line will not survive being spoken every
     * time something dies — a wave is up to nine machines, and a voice that marks every one of them
     * is a laugh track. So: they ROTATE (never the same line twice running), they fire about a
     * third of the time, and the global gap means a burst of kills yields at most one remark.
     *
     * A STREAK IS DIFFERENT and gets its own line at a much higher rate: three inside nine seconds
     * is a thing you actually did, and the one moment where being told so is earned.
     *
     * [systemSpoke] delays the answer past the machine's own DESTROYED, so when both fire it reads
     * as a retort rather than a collision.
     */
    private fun onKill(systemSpoke: Boolean) {
        pilotStreak = if (time - lastKillT < 9f) pilotStreak + 1 else 1
        lastKillT = time
        val at = if (systemSpoke) after("destroyed") else 0.35f
        if (pilotStreak >= 3 && pilot("hero_kill_streak", gap = 6f, cd = 55f, chance = 0.85f, delay = at, patience = 2500L)) {
            pilotStreak = 0
            return
        }
        val ids = arrayOf("hero_kill_1", "hero_kill_2", "hero_kill_3")
        val id = ids[pilotKillIdx % ids.size]
        if (pilot(id, gap = 10f, cd = 40f, chance = 0.32f, delay = at, patience = 2000L)) pilotKillIdx++
    }

    private fun spawnDerez(r: Recognizer) {
        // Oldest first: a wave that dies all at once should show you the DEATHS IN FRONT OF YOU, and
        // the one still coming apart is always the newest.
        while (derezzes.size >= MAX_DEREZ) derezzes.removeAt(0)
        derezzes += Derez(r.x, r.y, r.z, r.yaw, 1f, r.alert, false)
    }

    // ------------------------------------------------------------------ the Bit's voice
    /**
     * The Bit is DEFINED by its voice: it says yes and no and nothing else, and that is its entire
     * character. Silent, it was a waypoint. Given a voice it becomes the only thing in the arena
     * that is on your side, and — because the chatter tightens as you close — it is also the answer
     * to the one objective the game states out loud and then refuses to help with.
     *
     * THE MIX IS THE HARD PART. Three rules keep it from becoming a metronome:
     *  - the interval is a smooth function of range, from 4.6 s across the arena down to a floor of
     *    0.75 s at arm's length, with a random tail so it never locks to a beat;
     *  - a chirp is SKIPPED, not ducked, while either voice has the floor — it is ambience, and
     *    ambience waits;
     *  - close in, roughly one chirp in three becomes an actual YES, quietly. That is the Bit
     *    getting excited, and it is the cue that says "you are nearly on it" without a HUD element.
     *
     * And it reacts. A Recognizer within seven units of the Bit gets a NO — the Bit is frightened
     * of them, which tells you where one is AND makes the Bit a character with a stake in this.
     */
    private fun updateBitVoice(dt: Float, d: Float) {
        bitChirpCd -= dt
        bitNoCd -= dt
        if (bitChirpCd <= 0f) {
            val near = (1f - (d - 3f) / 28f).coerceIn(0f, 1f)      // 0 across the arena … 1 on top of it
            bitChirpCd = 4.6f - 3.85f * near + rng.nextFloat() * 0.6f
            if (!host.voiceBusy()) {
                if (near > 0.55f && rng.nextFloat() < 0.32f) host.sfx(com.x3paranoids.audio.Sfx.BIT_YES, 0.95f + 0.15f * near, 0.22f + 0.26f * near)
                else host.sfx(com.x3paranoids.audio.Sfx.BIT_CHIRP, 0.82f + 0.62f * near, 0.20f + 0.40f * near)
            }
        }
        if (bitNoCd <= 0f) {
            var nr = 999f
            for (r in recognizers) if (r.hp > 0) nr = min(nr, hypot(r.x - bitX, r.z - bitZ))
            if (nr < 7f) { bitNoCd = 6.5f; if (!host.voiceBusy()) host.sfx(com.x3paranoids.audio.Sfx.BIT_NO, 1f, 0.45f) }
        }
    }

    private fun burst(x: Float, y: Float, z: Float, n: Int, r: Float, g: Float, b: Float) {
        for (i in 0 until n) {
            val a = rng.nextFloat() * 6.283f; val e = rng.nextFloat() * 3.14f
            val sp = 3f + rng.nextFloat() * 7f
            sparks += Spark(x, y, z, cos(a) * sin(e) * sp, cos(e) * sp * 0.8f + 2f, sin(a) * sin(e) * sp, 0.5f + rng.nextFloat() * 0.7f, r, g, b)
        }
    }

    // ------------------------------------------------------------------ HUD helpers
    fun timerText(): String { val s = elapsed.toInt(); return "%d:%02d".format(s / 60, s % 60) }
    fun objectiveText(): String = if (bitActive) "FIND THE BIT" else "WAVE $wave"
    fun waveProgress(): Float = if (waveTotal == 0) 0f else kills.toFloat() / waveTotal
    /** Wall/grid colour drifts from phosphor green toward the later waves' white-cyan. */
    fun wallTint(): FloatArray {
        val k = ((wave - 2) / 4f).coerceIn(0f, 1f)
        return floatArrayOf(0.25f + 0.55f * k, 1f, 0.45f + 0.45f * k)
    }
}
