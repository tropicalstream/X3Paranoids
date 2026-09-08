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
    fun musicEnabled(on: Boolean)
    fun voiceEnabled(on: Boolean)
    fun headEnabled(on: Boolean)
    fun recentreHead()
    fun applyVolume(v0to10: Int)
    fun voiceDurationMs(id: String): Int
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
    var bitX = 0f; var bitZ = 0f; var bitActive = false; private set
    var bitT = 0f; private set

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
    private val tmp = FloatArray(2)
    private var lastKillSay = -99f
    private var humLevel = 0f

    // ------------------------------------------------------------------ boot / title
    fun boot() { enterTitle() }

    private fun enterTitle() {
        state = State.TITLE; stateT = 0f
        introLine = -1; showTap = false; introFallback = 0f
        recognizers.clear(); shots.clear(); sparks.clear(); bitActive = false
        host.stopVoice()
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
        host.stopVoice()
        mazeSeed = System.nanoTime(); maze = Maze(8, 8, mazeSeed)
        lives = 3; score = 0; wave = 0; elapsed = 0f; kills = 0; invuln = 0f; damageFlash = 0f
        vx = 0f; vz = 0f; hullYaw = 0f; hullTarget = 0f; turnBlend = 0f; newHigh = false
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
        host.sfx(com.x3paranoids.audio.Sfx.WAVE)
        host.say(if (wave <= 12) "wave_$wave" else "wave_more", urgent = true)
        host.say("incoming")
    }

    private fun waveCleared() {
        state = State.WAVE_CLEAR; stateT = 0f
        val bonus = 250 * wave
        score += bonus; bonusText = "BONUS $bonus"
        store.bestWave = wave
        host.sfx(com.x3paranoids.audio.Sfx.CLEAR)
        host.say("wave_clear", urgent = true)
    }

    private fun damagePlayer() {
        if (invuln > 0f || state != State.PLAY) return
        lives--
        damageFlash = 1f; invuln = 2.6f
        vx *= 0.3f; vz *= 0.3f
        if (lives <= 0) {
            state = State.DYING; stateT = 0f
            host.sfx(com.x3paranoids.audio.Sfx.DIE)
            host.say("derezzed", urgent = true)
            burst(px, EYE_H, pz, 40, 1f, 0.35f, 0.3f)
        } else {
            host.sfx(com.x3paranoids.audio.Sfx.HIT)
            host.say(if (lives == 1) "last_life" else "hit", urgent = true)
        }
    }

    private fun gameOver() {
        state = State.GAME_OVER; stateT = 0f
        host.hum(0f, 1f)
        newHigh = score > store.highScore && score > 0
        store.highScore = score
        host.sfx(com.x3paranoids.audio.Sfx.GAMEOVER)
        host.say("game_over", urgent = true)
        if (newHigh) { host.say("high_score"); host.sfx(com.x3paranoids.audio.Sfx.HISCORE) }
        host.say("end_of_line")
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
            State.DYING -> { updateWorld(dt, false); if (stateT > 2.4f) gameOver() }
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
            if (hypot(px - bitX, pz - bitZ) < 1.9f) {
                bitActive = false
                score += 500; lives = min(lives + 1, 5)
                burst(bitX, 1.4f, bitZ, 30, 0.4f, 1f, 1f)
                host.sfx(com.x3paranoids.audio.Sfx.BIT)
                host.say("bit", urgent = true)
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
            // Ramming. The recoil is a big shove — 2.5 units, more than a frame of movement — and it
            // used to be written straight into r.x/r.z with no collision test at all, which is a
            // teleport: ram the tank with your back to a wall and the recoil put you through it and
            // out the far side. It goes through maze.move like every other metre this thing travels.
            // The direction is taken FRESH from where the Recognizer stands now, not from the (ddx,
            // ddz) measured before this frame's step, so the shove is along the line you can see.
            if (hostile && d < 2.3f && state == State.PLAY) {
                damagePlayer()
                val bx = r.x - px; val bz = r.z - pz
                val bl = hypot(bx, bz).coerceAtLeast(0.01f)
                maze.move(r.x, r.z, bx / bl * 2.5f, bz / bl * 2.5f, Recognizer.RADIUS, tmp)
                r.x = tmp[0]; r.z = tmp[1]
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
                        burst(r.x, r.y + 1.2f, r.z, 36, 0.5f, 1f, 0.6f)
                        host.sfx(com.x3paranoids.audio.Sfx.EXPLODE, 0.9f + rng.nextFloat() * 0.2f)
                        if (time - lastKillSay > 4f) { lastKillSay = time; host.say("destroyed") }
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
