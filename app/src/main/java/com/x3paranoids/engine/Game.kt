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
    /**
     * [patienceMs] is how long the line will wait for the floor before it is dropped. The default
     * suits a reaction — a fact about something that just happened, worthless once it has not. The
     * intro passes a much longer one: its lines are a recital, and a recital with a sentence
     * missing out of the middle is worse than one that ran late.
     */
    fun say(id: String, urgent: Boolean = false, patienceMs: Long = 1500L)
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

        // the beats of a capture — see [Game.updateCrush]
        const val CRUSH_NONE = 0
        /** Rising over the tank, legs splaying: the anticipation. */
        const val CRUSH_LUNGE = 1
        /** Coming down, legs swinging shut: the descent. */
        const val CRUSH_DROP = 2
        /** Landed and clamped: the hold. */
        const val CRUSH_HOLD = 3
        /** Legs opening, lifting off (or being thrown off): the release. */
        const val CRUSH_RELEASE = 4
    }

    /** The eye's world position — where a disc leaves from, and the point the lock glow sits on. */
    fun eye(out: FloatArray) = RecognizerModel.toWorld(x, y, z, yaw, 1f,
        RecognizerModel.EYE_PT_X, RecognizerModel.EYE_PT_Y, RecognizerModel.EYE_PT_Z, out)

    var y = 1.6f
    var yaw = 0f
    var hp = 1
    var alert = 0f          // 0 patrol green … 1 hunting red
    var hunting = false
    /**
     * IT HAS TO BE LOOKING AT YOU. True on the frames the cab is within [Game.FIRE_ARC] of the
     * tank with a clear shot line — the only state a disc may leave from. Until then the machine
     * is TURNING, and the turn is the telegraph the whole facing rule exists to give you.
     */
    var facing = false
    /** Radians the cab is still off the tank while it tracks you; 0 when it is not tracking. */
    var aimErr = 0f
    /** 0..1 ramp of [facing] — the eye's lock glow, so the slit brightens as it finds you rather than blinking. */
    var lock = 0f
    // ------------------------------------------------------------------ the crush
    /** [CRUSH_NONE], or which beat of the capture this machine is in — see [Game.updateCrush]. */
    var crush = CRUSH_NONE
    var crushT = 0f
    /** Where it was hovering when the lunge began, so the rise starts from there. */
    var crushY0 = 1.6f
    /** How far the legs are folded, 0 hanging … 1 clamped shut, a little below 0 splayed — see [RecognizerModel.segment]. */
    var fold = 0f
    /** Seconds before this machine may capture again. The player's recovery window. */
    var crushCd = 0f
    /** Seconds of reeling after a release: it neither fires nor closes, and its heading wanders. */
    var stagger = 0f
    /** The release was the SHELL throwing it off rather than the machine letting go. */
    var thrown = false
    /** How long the current hold lasts — shorter when it is straining against a shell. */
    var holdT = 0f
    /** Yaw wobble seed for the stagger. */
    var reel = 0f
    /** Seconds left of a CHARGE — it has stopped standing off and is coming to capture. */
    var charge = 0f
    var chargeCd = 0f
    /** The approach line at the moment of capture, unit, machine → tank. Fixed for the sequence. */
    var crushUx = 0f
    var crushUz = -1f
    /**
     * WEDGED. A hunter drives straight at the tank it can see, and a wall corner between them
     * stops it dead: it sees you through the gap, presses on the wall, and stands there. Watched
     * on the glasses for a full minute, two machines at seventeen units, neither able to close
     * nor fire. [stuckT] accumulates while a chase step goes nowhere; past a beat it sets
     * [reroute], and for that long the machine walks the BFS step toward your cell instead of
     * the straight line — still turning to face you, still firing if the line clears.
     */
    var stuckT = 0f
    var reroute = 0f
    /**
     * SIGHT WITHOUT THE LINE. Standing off at six units with the tank in view but the shot line
     * clipping a door post, the old AI circled there indefinitely — two machines did, for seventy
     * seconds, on the glasses. This accumulates while it can see you and cannot shoot you; past a
     * beat it CLOSES instead of standing off, which walks it round the post and into the line —
     * or into the tank, which is the other thing it does.
     */
    var noLineT = 0f
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
    /**
     * The TRACKING servo has been heard for this engagement — the mirror of [lockSaid], cleared the
     * same way (when the shot line is lost). The design's own comment calls TRACKING "the beat on
     * which moving still works", and until now it set a flag and made no sound at all: the one
     * moment a player could still act on was silent, and the one that is nearly too late was the
     * one that made a noise. On a headset, where the threat is frequently outside the field of
     * view, audio is the only channel that reaches you regardless of where you are looking.
     */
    var trackSaid = false
    /**
     * THE NAV PLATE'S LAST-KNOWN RETURN for this machine — the cell centre it was last sampled in,
     * and the sweep index that sampled it. Written by the renderer, exactly as [vis] is, because the
     * plate is the only thing that reads them; they live here rather than in a map beside the
     * renderer so a machine carries its own return and a wave rollover throws it away with the
     * machine. See GLRenderer.buildMinimap's GHOSTS note for what they are for and why they are
     * coarse and late. [ghostSweep] of -1 means "never sampled": the first frame that wants it takes
     * a return immediately, so a wave opens with its contacts already on the plate.
     */
    var ghostX = 0f
    var ghostZ = 0f
    /** The same return as cell indices, so the plate can tell two contacts in one room apart. */
    var ghostC = 0
    var ghostR = 0
    var ghostSweep = -1
}

class Shot(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, val friendly: Boolean) {
    var life = if (friendly) 1.6f else 2.4f
    /** The disc's spin phase about its own travel axis — set at the throw so no two spin in step. */
    var spin = 0f
    /** Range to the tank on the previous frame, for the closest-approach test — see [Game.NEAR_MISS_D]. */
    var prevD = 999f
    /** The near miss has been called; a disc whooshes once. */
    var passed = false
    /**
     * Struck out of the air by a player shell — see [Game.cutDisc]. It is a FLAG rather than an
     * immediate removal because the cut is discovered while iterating the same list the disc lives
     * in; the shot loop drains flagged discs at the top of the next pass, and nothing acts on one
     * in between.
     */
    var dead = false
}

class Spark(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, var life: Float, val r: Float, val g: Float, val b: Float)

/**
 * SOMETHING LANDED ON THE SIGHT. A world point the HUD projects each frame and draws a shock ring
 * around, so the impact stays anchored to where it happened as the head moves. [kind] is what
 * happened there: the hull took it, the shell took it, or it went past.
 */
class Impact(val x: Float, val y: Float, val z: Float, val kind: Int) {
    var age = 0f
    companion object {
        const val HULL = 0
        const val SHIELD = 1
        const val NEAR = 2
        /** How long a ring lives on the glass. */
        const val LIFE = 0.55f
    }
}

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

        // ------------------------------------------------------------------ [DRIVING: DASH AND CRUISE]
        /**
         * THE HULL HAS TWO GEARS AND THEY ARE THE SAME GESTURE HELD FOR DIFFERENT LENGTHS OF TIME.
         *
         * A FLICK IS A DASH. Cross the swipe threshold and lift: [IMPULSE] goes into the velocity in
         * one go and [DRIVE_DAMP] takes it back out again, which carries the tank IMPULSE × DAMP =
         * about 4.1 units — under half a [Maze.CELL]. That is a nudge for lining up a shot, and it is
         * exactly what this game did before hold-to-drive existed; it is unchanged on purpose,
         * because it is the gesture the arena's fine positioning is built out of.
         *
         * A HOLD IS A CRUISE. Keep the finger down after the threshold and thrust is applied EVERY
         * FRAME until it lifts. Crossing this arena is 72 units; at 4.1 units a flick that was
         * eighteen flicks of the pad, which is the complaint this exists to answer.
         *
         * The thrust is sized off the damping rather than picked: against an e-folding of
         * [DRIVE_DAMP] a constant acceleration `a` settles at `a · DRIVE_DAMP`, so [DRIVE_ACCEL] is
         * defined as the acceleration whose terminal speed is exactly [MAX_SPEED]. The tank
         * ASYMPTOTES to its own speed limit instead of slamming into a clamp, which is the
         * difference between a cruise and a governor.
         *
         * [DRIVE_FLOOR] IS WHY A HOLD DOES NOT SAG. The dash lands first, at 7.5 — five sixths of
         * the cruise speed — so a ramp that started from zero thrust would let the damping eat the
         * dash for a fifth of a second before the engine caught it, and that dip is felt as mush at
         * exactly the moment the player is deciding whether the hold worked. So the ramp starts at
         * IMPULSE / MAX_SPEED of full thrust — the acceleration that exactly HOLDS the dash's speed
         * — and climbs from there to full over [DRIVE_RAMP]. Speed therefore never falls during a
         * hold: it leaves the dash at 7.5 and eases up to 9 over about half a second. Ramping at all
         * is a head-worn-display concession; a step change in acceleration is felt in the inner ear.
         */
        const val DRIVE_DAMP = 0.55f
        /** The acceleration whose terminal speed under [DRIVE_DAMP] is exactly [MAX_SPEED]. */
        const val DRIVE_ACCEL = MAX_SPEED / DRIVE_DAMP
        /** Fraction of [DRIVE_ACCEL] the sustained thrust starts at: the accel that holds a dash. */
        const val DRIVE_FLOOR = IMPULSE / MAX_SPEED
        /** Seconds for the sustained thrust to climb from [DRIVE_FLOOR] to full. */
        const val DRIVE_RAMP = 0.5f
        /**
         * Seconds between wall thuds. A held drive into a wall re-presses on EVERY frame, and
         * without this the bump sound fires sixty times a second — which is the difference between
         * a tank leaning on a wall and a road drill.
         */
        const val BUMP_CD = 0.35f
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
        /** Centre-to-centre range at which a Recognizer has TOUCHED the tank — the capture trigger. */
        const val RAM_D = 2.3f
        /** How hard a contact that cannot become a capture throws the pair apart — spent on the Recognizer first, then on the tank. */
        const val RAM_PUSH = 2.5f

        // ------------------------------------------------------------------ [FACING BEFORE FIRING]
        /**
         * A RECOGNIZER MUST BE LOOKING AT YOU BEFORE IT CAN SHOOT YOU, and it has to get there by
         * turning. The old AI wrote the bearing to the tank straight into the machine's yaw on the
         * frame it acquired you — a snap — and fired on the same frame if its cooldown allowed,
         * which from the seat read as a machine shooting without ever having faced you. Now the
         * cab SWINGS: [TURN_HUNT] radians a second when it is hunting (about 160 degrees a second —
         * a half-turn takes just over a second), [TURN_PATROL] on the beat (about 115, a machine
         * on its rounds), and it may only fire once the cab is within [FIRE_ARC] of the tank —
         * ten degrees — with the shot line clear. The swing is the telegraph. "It is turning
         * toward me — move" is a thing the player can learn, and a Recognizer that acquires you
         * side-on gives you most of a second to act on it.
         *
         * The servo eases over its last twenty degrees ([TURN_EASE]) rather than stopping dead —
         * a rate limit that hits its target at full speed reads as a snap in miniature.
         */
        const val TURN_HUNT = 2.8f
        const val TURN_PATROL = 2.0f
        const val FIRE_ARC = 0.175f
        const val TURN_EASE = 0.35f
        /**
         * Range inside which a facing Recognizer with the shot line will throw.
         *
         * IT WAS 26 — most of a 72-unit arena, which meant a machine that acquired you across four
         * cells could keep throwing for as long as the corridor stayed straight, and the only
         * escape available was to look away from it. Two-and-a-bit cells is the range at which
         * BREAKING THE LINE OF SIGHT IS AN ESCAPE THAT COMPLETES: a corner that far off is
         * reachable inside the dwell at cruise speed, so ducking round it is a move rather than a
         * hope. Machines beyond it still hunt, still close, still turn to face — they just have to
         * come and get you, which is what the charge and the crush are for, and what makes a
         * corridor a decision.
         *
         * IT WAS BRIEFLY 17 AND THAT WAS TOO FAR. Measured on the glasses: six engagements, ninety
         * seconds of contact, TWO throws in the whole session. A machine's fire cooldown only ticks
         * while it is chasing with the line, so cutting the range that hard did not merely make the
         * arena fairer, it made it quiet — and a Recognizer that never throws is not a threat, it
         * is scenery. Twenty units keeps the corridor duel and still fits inside a corner.
         */
        const val FIRE_RANGE = 20f
        /**
         * A PLAYER SHELL CAN CUT A DISC OUT OF THE AIR. This is the radius of that meeting, and it
         * is the only new verb in this game — the answer to a rim chevron that could otherwise only
         * ever say "death is coming from there" and leave you with nothing to do about it but look
         * elsewhere. Turn onto the bearing and FIRE, and the disc bursts.
         *
         * It costs the existing gestures nothing (the cannon and the periscope are the same ray)
         * and it is the film's own move: the disc is the weapon in this world, and meeting one is
         * what programs do. A defensive shot inside a 0.62 s dwell has to be a reflex, not a
         * marksmanship exam.
         *
         * THE TEST IS A HORIZONTAL RADIUS AND A SEPARATE VERTICAL WINDOW — the same shape as the
         * shell-versus-Recognizer test, and for the same reason. A single 3-D radius did not fire
         * once in a whole test run, and the geometry says why: a disc leaves the cab four units up
         * and comes DOWN at the periscope, while a shell leaves the barrel at 1.05 and travels
         * essentially flat, so the two pass one to two units apart vertically even when the player's
         * aim is perfect. The vertical separation is not the player's decision — it is the
         * machine's own model — so it must not be the thing that decides whether they were right.
         * The horizontal radius is the part the player earns, and it stays tight.
         */
        const val DISC_CUT_R = 2.0f
        /**
         * How far above or below the shell a disc may be and still be met. Measured rather than
         * reasoned: instrumented on the glasses, a shell that passed a disc did so with a VERTICAL
         * separation of 0.38 units and a horizontal one of 3.13 — the descent the geometry made me
         * worry about is a non-issue, and the whole difficulty is lateral aim, which is exactly the
         * part that should be the player's problem. So the vertical window is generous and the
         * horizontal radius is what the shot has to earn.
         */
        const val DISC_CUT_Y = 2.5f
        /** Points for cutting a disc down. Small: it saved your life, that is most of the reward. */
        const val DISC_CUT_SCORE = 25
        /**
         * Temporary bench instrument: log how close every player shell actually came to a disc, so
         * [DISC_CUT_R] and [DISC_CUT_Y] are set from measurements rather than from arithmetic about
         * a geometry nobody has watched. Off in anything a player will ever run.
         */
        const val CUT_TRACE = false
        /** A disc passing inside this without landing is a NEAR MISS: it whooshes, and the sight feels it. */
        const val NEAR_MISS_D = 3.2f

        // ------------------------------------------------------------------ [THE CRUSH]
        /**
         * WHAT A RECOGNIZER DOES WHEN IT TOUCHES YOU. It does not bump. It captures, the way the
         * machines in the film do: it comes down over its target and its two legs fold inward
         * beneath the bar until the feet meet, closing on whatever is between them. Here that is
         * the tank, and you are inside it, so the whole sequence is watched from the seat.
         *
         * Five beats on the machine's own clock — see [updateCrush]:
         *  LUNGE   [CRUSH_LUNGE_T]  it rises to [CRUSH_RISE_Y] and slides to directly over the hull
         *                           while the legs SPLAY a little: the anticipation, the hands opening.
         *  DROP    [CRUSH_DROP_T]   it falls — accelerating — to [CRUSH_LAND_Y], and the legs swing
         *                           shut on a cubic so the clamp SNAPS closed at the bottom of the drop.
         *  LAND                     the hard beat: the slam, the sight kicked inward, static in the
         *                           periscope — and the OUTCOME, which depends on the shell (below).
         *  HOLD    [CRUSH_HOLD_T]   clamped, juddering, the servos straining. You can still look —
         *                           that is the point — and you can still shoot it.
         *  RELEASE [CRUSH_RELEASE_T] the legs open and it lifts off and backs away, or the shell
         *                           THROWS it open and away; either way it then reels for
         *                           [STAGGER_T] and may not capture again for [CRUSH_CD].
         *
         * THE SHELL. A crush against a shielded tank costs [CRUSH_CHARGES] = 2 of the shell's three
         * bands. Not one: a bolt costs one, and the heaviest thing a Recognizer can do to you must
         * cost more than a bolt or the crush is a bump with a longer animation. Not three: the
         * machines already PRESS a shielded tank — closer standoff, faster chase — so a shell that
         * popped on any touch would be a shell that only stops bolts, and the pool would stop being
         * worth crossing for. Two means a full shell survives exactly one capture with a single
         * band left, and a shell already touched does not. The tank is untouchable for a beat
         * longer than a bolt buys ([SHIELD_IFRAME] + [CRUSH_GRACE]) so the thrown machine's
         * companions cannot land the next one while you are still finding the pad.
         *
         * BARE, it is a life, with the full derez if it was the last — and the machine holds its
         * clamp through the death rather than letting go: the periscope sinks between its legs.
         */
        const val CRUSH_LUNGE_T = 0.40f
        const val CRUSH_DROP_T = 0.30f
        const val CRUSH_HOLD_T = 0.65f
        /** The strain before a shell discharges — long enough to wonder whether it will hold. */
        const val CRUSH_HOLD_SHELL_T = 0.50f
        const val CRUSH_RELEASE_T = 0.55f
        const val CRUSH_RISE_Y = 2.55f
        /** Where the axle sits when landed: the bar at ~2.15, over the periscope; the legs closing at eye height. */
        const val CRUSH_LAND_Y = 0.12f
        const val CRUSH_CHARGES = 2
        const val CRUSH_GRACE = 0.55f
        const val CRUSH_CD = 3.5f
        const val STAGGER_T = 1.4f
        /** How fast the gantry slides over the tank during the lunge, units a second. */
        const val CRUSH_CLOSE_SPEED = 9f
        /**
         * WHERE THE GANTRY LANDS, and why it is not dead over the periscope. Measured on the glasses:
         * a machine centred exactly on the eye puts its legs 1.35 units to either SIDE of the
         * camera — ninety degrees off the view axis — and the whole fold happens out of frame while
         * the sight fills with the bar's strokes crossing overhead. The periscope sits at the back
         * of the hull; the machine comes down over the FRONT of it, [CRUSH_STAND] short of the eye
         * along its own approach line, so from the seat the two legs enter from both edges of the
         * sight and swing shut in the centre of it, at knee height, with the bar across the top.
         * That is the film's image, seen from inside.
         */
        const val CRUSH_STAND = 1.9f
        /**
         * After the eye first finds you, the disc waits this long: the lock bar and WARNING always
         * precede the first throw. It was 0.45 s, which is under the time it takes to saccade to a
         * corner plate, parse a three-pixel spur and act — so the only rational response to WARNING
         * was to move at random. At 0.62 s the sting has landed, the rim chevron has been read and
         * a decision (turn onto it and shoot the disc, or break the line) is genuinely available.
         * EASY widens it further; see [dwell].
         */
        const val LOCK_DWELL = 0.62f
        /** How far a released machine backs off over the release, and how far a thrown one is flung. */
        const val CRUSH_BACK_OFF = 3.6f
        const val CRUSH_THROW = 5.5f
        /**
         * THE CHARGE — when a Recognizer decides to capture rather than shoot. A machine that only
         * ever stood off at six units would crush you only when you drove into it, and a set piece
         * nobody sees is not a set piece. So, hunting inside [CHARGE_RANGE] with the line clear, a
         * Recognizer rolls every [CHARGE_CD] seconds: a [CHARGE_CHANCE] chance to close for
         * [CHARGE_T] seconds, straight at the hull, faster, no sway. A shielded tank is charged at
         * [PRESS_CHARGE_CHANCE] — that is the press: they come and take the Protocol's energy back,
         * and the crush is how. The charge is readable — the sway stops, the machine grows, the eye
         * stays on you — and it is also the machine walking into your cannon, which is its cost.
         *
         * THE PRESSED ROLL USED TO BE A CERTAINTY, and that made the pool's reward arguably a
         * punishment: crossing the arena and standing still for nearly a second bought you a state
         * in which every hunting machine inside eleven units charged you every 3.2 s, moved 28%
         * faster, fired 43% more often and closed two units nearer — against three bands, two of
         * which one capture spends. The press should RAISE the odds, not delete the roll; at 0.55
         * the shell still changes the shape of the fight without handing the arena a guarantee.
         */
        const val CHARGE_RANGE = 11f
        const val CHARGE_CD = 3.2f
        const val CHARGE_CHANCE = 0.30f
        const val PRESS_CHARGE_CHANCE = 0.55f
        const val CHARGE_T = 2.4f
        const val CHARGE_SPEED = 1.30f
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

        // ------------------------------------------------------------------ [THE SCATTER]
        /**
         * LOSING A LIFE HAS TO BUY AN ESCAPE, NOT A DELAY.
         *
         * Measured across three runs before this existed, the fatal hit landed 0.11 s, 0.23 s and
         * 0.30 s after the previous invulnerability lapsed. The cause was structural rather than a
         * number being wrong: during the 2.6 s of grace the other machines never stopped locking,
         * never stopped counting down [Recognizer.fireCd] and never stopped throwing, so whatever
         * was in the air connected on the frame the tank became vulnerable again. The tank came
         * back inside the same crossfire that had just killed it, with a hull that had not moved.
         *
         * So a life lost now COSTS THE ARENA SOMETHING TOO. Every hostile disc in flight is cut;
         * every machine within [SCATTER_R] is thrown into the reeling state it already has an
         * animation for, its next throw pushed out to [SCATTER_FIRE_CD], its lock sting re-armed so
         * the next engagement has to earn its WARNING again — and it BACKS OFF, physically, through
         * the maze's own collision. Nothing new is drawn and no camera moves: the machines simply
         * do the thing they do after a capture, all at once, and the 2.6 s of grace becomes 2.6 s
         * in which running actually works.
         *
         * It is also the fiction: they have just derezzed something and they regroup. The pilot
         * says so once, which is how the player learns the window is real.
         */
        const val SCATTER_R = 13f
        /**
         * THE NUMBERS ARE SET AGAINST THE GRACE, NOT PICKED. Measured on the glasses at the first
         * attempt (STAGGER 1.5, FIRE_CD 2.2): a scattered machine stopped reeling at 1.5 s, locked
         * again at 1.5 s and could throw at 2.2 s — and a disc takes about half a second to cross
         * the gap, so it landed at ~2.7 s against 2.6 s of invulnerability. That is the same
         * cascade one beat further out, which is not a fix.
         *
         * At 3.2 s the earliest possible throw lands about 3.7 s in — a clear second AFTER the
         * player is vulnerable again, and a second in which they were already free to move. The
         * reeling is shorter than the cooldown on purpose: the machines get up before they get
         * their aim back, so what the player sees is an arena that recovers, not one that is
         * switched off.
         */
        const val SCATTER_STAGGER = 1.8f
        const val SCATTER_FIRE_CD = 3.2f
        /** How far a scattered machine is pushed away from the wreck, in units. */
        const val SCATTER_PUSH = 3.2f
        /** Range at which the Bit counts as "in your lap" — the top of the proximity ramp. */
        const val BIT_CLOSE = 3f
        /** How long the control card stays on the glass at the start of a played game. */
        const val CARD_T = 9f
        /** How long FIND THE BIT holds the objective band when the Bit announces itself. */
        const val BIT_HINT_T = 5f

        // ------------------------------------------------------------------ [THE ENERGY ECONOMY]
        /**
         * PROGRAMS ON THE GRID RUN ON ENERGY. They drink it from pools; starved of it they derez.
         * In the fiction a pool is not a coin, it is a PLACE — somewhere a program goes to be
         * restored — and that is what it is here: each maze has one, it stands where it stands for
         * the life of the maze, and a tank that stands in it long enough to DRAW comes away with
         * its shell of light RESTORED TO FULL. The shell takes hits so the hull does not; the pool
         * heals it. That is the whole loop, and every number below exists to keep it from switching
         * the threat off.
         *
         * [SHIELD_MAX] = 3 — one per band of [ShieldModel], so the shell IS the readout. Three is
         * three hits, which is a whole life more than the tank itself carries between deaths, and
         * that is deliberate: a pool has to be worth abandoning a corridor and crossing the arena
         * for, or nobody will ever go and the whole system is decoration.
         *
         * [SHIELD_IFRAME] against the 2.6 s a real hit buys. THIS IS THE BALANCE. Losing a life
         * makes you briefly untouchable, which is mercy; the shield does not, which is the price
         * of it. Stand in a Recognizer's fire lane wearing three charges and they are gone in a
         * few seconds — the shell absorbs MISTAKES, it does not license standing still.
         *
         * THE SHELL NEVER REGENERATES ON ITS OWN, and never stacks past the cap: drawing again
         * while shielded RESTORES to three rather than adding. Energy that came back by itself
         * would stop being worth crossing for, and energy that banked would let a careful player
         * walk into wave six with six charges — flattening the difficulty curve at exactly the
         * point it is supposed to bite. Charges DO survive a wave boundary, because taking them
         * off you for clearing a wave would be a punishment for winning.
         *
         * THE POOL IS PERSISTENT, AND IT REFILLS — SLOWLY. A draw drains it (the column collapses)
         * and it takes [POOL_REFILL_T] seconds to stand again; until then standing in it does
         * nothing, and the column climbing back is the only progress bar. This is what makes it a
         * place rather than a pickup: a corner of the maze you LEARN, and can retreat to when the
         * wave has stripped you — at the cost of crossing the arena to it, standing still in a
         * known spot for [POOL_DRAW_T] while the machines BFS to your cell, and coming out of it
         * shielded, which is exactly the state the machines PRESS (below). Twenty-odd seconds is
         * set against how long a wave runs: a pool that refilled inside a firefight would be a
         * regenerator with extra steps; one that refills once or twice a wave is a decision.
         * A full shell does not drink: the pool holds its energy for when it is needed.
         *
         * [POOL_DRAW_T] = 0.9 s of continuous presence. A pickup you drive over is a coin; a pool
         * you have to STAND IN while the machines close is a decision, and it is also the image the
         * fiction wants — a program kneeling to drink. Step out and the draw bleeds back at
         * [POOL_DRAW_DECAY] times the fill rate, which forgives a bump off a wall and does not
         * forgive fleeing.
         *
         * The pool is scored at ZERO on purpose. The Bit is points and a life; the pool is survival.
         * Pay for both in the same currency and the choice between them collapses into "take the
         * Bit first, then the pool" — priced differently, they pull you two ways at once, which is
         * the only reason to have two objectives in one arena. The Bit is placed far from the pool
         * each wave for the same reason: no one route sweeps them both.
         */
        const val SHIELD_MAX = ShieldModel.BANDS
        /** The shell's radius about the hull: outside the tank, inside the cannon's reach. */
        const val SHIELD_R = 2.15f
        /**
         * How far BELOW the periscope the shell's centre sits — the hull, not the eye. It is the
         * whole reason the bubble reads as a bubble (see GLRenderer.buildShield), and the derez has
         * to burst from the same centre or the shell you were looking at is not the one that broke.
         */
        const val SHIELD_DROP = 0.85f
        /**
         * Untouchable time bought by a shield charge — deliberately far short of the 2.6 s a real
         * hit buys, but not as short as it first was. At 0.8 s a RAM ate the whole shell in a
         * second and a half, measured on the glasses: three flares, three sounds and a derez inside
         * two seconds, which is not a buffer, it is a shell that evaporates on contact and that the
         * player never sees the middle state of. At 1.15 s a machine riding you down still strips
         * three charges in under three and a half seconds — fast enough that closing to ram is the
         * right answer for THEM — while each charge lasts long enough to register as a thing that
         * just saved you and to give you time to break away on.
         */
        const val SHIELD_IFRAME = 1.15f
        /** How close the tank must be to the pool's centre to be drinking from it. */
        const val POOL_R = 2.2f
        /** Seconds of continuous presence to take a full shell. */
        const val POOL_DRAW_T = 0.9f
        /** Leave the pool and progress bleeds back at this multiple of the fill rate. */
        const val POOL_DRAW_DECAY = 1.5f
        /** Seconds between the pool's climbing "pull" blips while you drink. */
        const val POOL_SIP_T = 0.3f
        /** Seconds for a drained pool to stand again — see [THE ENERGY ECONOMY]. */
        const val POOL_REFILL_T = 24f
        /**
         * THE MACHINES PRESS A SHIELDED TANK. Standing off six units is what a Recognizer does to
         * something it can kill from there; a program carrying the Protocol's own energy gets
         * closed on and stripped. So while [shield] is up they hold four units instead of six, fire
         * on a cooldown scaled by [PRESS_FIRE] and chase [PRESS_SPEED] faster.
         *
         * This is what stops the pickup being a rest. The shell does not make the wave quieter, it
         * changes its SHAPE: the fight comes to close quarters, where their bolts spread less and
         * their ram is a constant threat, and where your own cannon barely has to lead them. Drink
         * and push, or leave the pool where it is and keep playing the patient stand-off game — and
         * the moment the last charge goes the machines fall back to six units again, which is a
         * shift you can see and hear happen.
         */
        const val PRESS_STANDOFF = 4f
        const val PRESS_FIRE = 0.7f
        const val PRESS_SPEED = 1.28f
        /** Above this proximity a chirp may become an actual YES: about two cells out. */
        const val BIT_EXCITED = 0.72f
        val INTRO = listOf("intro_1", "intro_2", "intro_3", "intro_4", "intro_5", "intro_6", "intro_7", "intro_8")
        /**
         * The lore, as it is spoken. A `|` is a HAND-SET LINE BREAK for the two lines too long to
         * fit the band — set by hand because the villain's name is the thing the owner insisted on
         * and an automatic wrap put it across two lines ("THE MONOPOLY / CONTROL PROTOCOL"). A
         * measured break is also free to be a better one: the name stays whole and "ON ITS BONES"
         * gets the second line to itself, which is where the sentence turns anyway.
         */
        val INTRO_TEXT = listOf(
            "GREETINGS, PROGRAM.",
            "A PROGRAMMER WROTE A GAME IN A BASEMENT.",
            "THE GAME WAS STOLEN.",
            "THE THIEF BUILT THE MONOPOLY CONTROL PROTOCOL|ON ITS BONES.",
            "NOW THE RECOGNIZERS HUNT|WHOEVER REMEMBERS THE ORIGINAL CODE.",
            "YOU REMEMBER.",
            "FIND THE BIT. SURVIVE THE WAVES.",
            "END OF LINE.",
        )
        /**
         * THE TWO LINES THE PILOT GETS IN THE INTRO, and why these two.
         *
         * The lore is eight lines of a machine reciting a theft. Read straight through it is a
         * paragraph; the thing that makes it a STORY is that somebody in it answers back. So the
         * pilot speaks exactly twice, and both times the system has just handed it a cue:
         *
         *  - the machine names the MONOPOLY CONTROL PROTOCOL, and the program whose code it was
         *    built on says whose maze this actually is;
         *  - the machine says "YOU REMEMBER", the camera puts a shell through a Recognizer, and the
         *    pilot says what it remembers.
         *
         * The captions are the EXACT text those clips were rendered from (tools/generate_hero_voice.py),
         * so the glass never says a word the voice did not.
         */
        const val PILOT_MCP = "hero_mcp"
        const val PILOT_KILL = "hero_kill_streak"
        /**
         * EVERY PILOT LINE, VERBATIM FROM THE SCRIPT IT WAS RENDERED FROM (tools/generate_hero_voice.py).
         *
         * It used to hold the intro's two lines and nothing else, which meant the film captioned its
         * voice and the GAME DID NOT CAPTION ITS OWN: twenty-one fish.audio lines delivered as audio
         * only, under io_tower.mp3, over thirty-odd synthesised cues, out of open-ear waveguide
         * speakers in whatever room the player happens to be sitting in. That is the difference
         * between shipping the fiction and shipping the audio file of the fiction — and on a
         * head-worn display with no headphones it is most of the difference.
         *
         * The play HUD draws these low and left, under the pilot's own tag, for as long as the clip
         * runs (see GLRenderer.buildPilotCaption). They are not subtitles for the machine: the
         * SYSTEM voice stays uncaptioned in play on purpose, because it is a machine reciting status
         * and the HUD already prints every fact it states. The pilot is the only thing in here that
         * says something the glass does not.
         */
        val PILOT_TEXT = mapOf(
            "hero_start" to "I'M IN. LET'S SEE WHAT THEY BUILT ON MY CODE.",
            "hero_wave" to "ANOTHER WAVE.",
            "hero_wave_late" to "THEY JUST KEEP COMING.",
            "hero_kill_1" to "DEREZZED.",
            "hero_kill_2" to "THAT'S ONE.",
            "hero_kill_3" to "DOWN YOU GO.",
            PILOT_KILL to "I REMEMBER EVERY CORNER OF THIS MAZE.",
            "hero_hit" to "I'M HIT.",
            "hero_hit_bad" to "HULL'S FAILING.",
            "hero_last_life" to "ONE LIFE LEFT. MAKE IT COUNT.",
            "hero_shield_up" to "ENERGY. SHIELDS HOLDING.",
            "hero_shield_hit" to "SHIELD'S TAKING IT.",
            "hero_shield_down" to "SHIELD'S GONE.",
            "hero_bit_near" to "THE BIT'S CLOSE.",
            "hero_bit_get" to "THERE YOU ARE.",
            "hero_wave_clear" to "SECTOR CLEAR.",
            "hero_quiet" to "THE GRID'S QUIET. FOR NOW.",
            PILOT_MCP to "THE PROTOCOL DOESN'T OWN THIS MAZE.",
            "hero_derez" to "NO. NOT LIKE THIS.",
            "hero_game_over" to "THEY CAN STEAL THE GAME.|THEY CAN'T STEAL THE CODE.",
            "hero_high_score" to "A NEW RECORD.",
            "hero_scatter" to "THEY'RE FALLING BACK. MOVE.",
            "hero_disc_cut" to "NOT TODAY.",
            "hero_protocol" to "IT KNOWS WE'RE HERE.",
        )

        /**
         * THE PROTOCOL NOTICES YOU — the one piece of lore in this game that is a SYSTEM rather
         * than a sentence, and the reason the arena's colour means something.
         *
         * [wallTint] already drifts the whole world from phosphor green toward the late waves'
         * white-cyan, and until now that drift was unexplained decoration: the most visible change
         * in the game had no cause. It has one now. On the waves where the tint measurably moves —
         * and they are the same waves that add a machine, and at six the two-hit shells — the
         * SYSTEM voice, the flat indifferent thing that has been narrating at you since the intro,
         * states three words about it, and the pilot answers once. Nothing is drawn, no loop stops,
         * no card appears; the world simply acquires an owner who is keeping score of you.
         *
         * That is the MONOPOLY CONTROL PROTOCOL as a presence rather than a name in a crawl, and it
         * is period-exact: a cabinet that comments on your progress in three words is 1982 to the
         * letter. The lines are on [PROTOCOL_WAVES], never more than three a game, and the last one
         * is the only one the pilot gets to answer.
         */
        val PROTOCOL_WAVES = intArrayOf(3, 5, 7)
        val PROTOCOL_LINES = arrayOf("protocol_1", "protocol_2", "protocol_3")
        val PROTOCOL_TEXT = arrayOf(
            "PROTOCOL ATTENTION RISING.",
            "YOUR SIGNATURE IS LOGGED.",
            "PROTOCOL OVERRIDE. ALL UNITS.",
        )
    }

    // ------------------------------------------------------------------ state
    // VOLATILE because the input thread reads them through [holdDriveArmed] to decide whether a
    // gesture that is still in progress may drive. Nothing else crosses threads here.
    @Volatile var state = State.TITLE; private set
    var time = 0f; private set
    var stateT = 0f; private set
    @Volatile var menuOpen = false; private set
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
    /** +1 while the pad is HELD forward, -1 held back, 0 coasting. See [driveStart]. */
    var driveDir = 0; private set
    /** Seconds the current sustained drive has run — drives the thrust ramp and the HUD ladder. */
    private var driveT = 0f
    private var driveLogT = 0f
    private var bumpCd = 0f
    /** 0…1 of [DRIVE_RAMP]: how far the throttle has come up. The HUD's only reason to exist. */
    val driveThrottle get() = if (driveDir == 0) 0f else min(1f, driveT / DRIVE_RAMP)
    /**
     * May a gesture that is STILL DOWN drive the tank? Read from the input thread, and the reason
     * the settings menu stays strictly one-swipe-one-step: off the arena, a held pad is classified
     * the old way — once, on finger-up — so a hold cannot walk the menu.
     */
    val holdDriveArmed: Boolean get() = state == State.PLAY && !menuOpen && !caught
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
    /** Some Recognizer is FACING you with the shot line — it can throw now. WARNING, and the brackets go red. */
    var lockedOn = false; private set
    /** Some Recognizer has the shot line and is still bringing its cab round — TRACKING. Move. */
    var tracking = false; private set
    var bonusText = ""; private set
    /**
     * Seconds left on the FIND THE BIT prompt. The objective band is the one imperative sentence on
     * the glass, and it used to read "FIND THE BIT" for the whole of every wave — sending a
     * first-time player hunting a hidden diamond across an 8×8 maze while three machines they have
     * not been taught to fight hunted them, when the wave-clear condition is `recognizersLeft == 0`
     * and the Bit is optional. The band now states the condition that ends the wave, and the Bit
     * gets the band only in the moments it has actually announced itself: when its chirps first
     * come up close, and when the pilot calls it. See [objectiveText].
     */
    var bitHint = 0f; private set
    /**
     * Seconds left on the control card — see [CARD_T]. A 1982 cabinet had an instruction card
     * bolted to the glass and a control panel you could look down at; this has neither, and the
     * only statement of the verbs lived at the very end of an attract loop most players skip. So
     * the first wave of a game states them once, low on the sight, and fades them out.
     */
    var cardT = 0f; private set

    // ------------------------------------------------------------------ the sight, reacting
    /**
     * THE TANK IS HELD. Volatile because the input thread reads it through [holdDriveArmed]: a
     * finger on the pad while the legs are closing must not drive, and the classifier has to know
     * that on the ACTION_MOVE, not a frame later. [crusher] is the machine doing it.
     */
    @Volatile var caught = false; private set
    var crusher: Recognizer? = null; private set
    /** Periscope judder from the crush — the lunge, the landing, the strain — on top of the damage shake. */
    var crushShake = 0f; private set
    /**
     * The sight's brackets KICKED: positive is a punch outward (something landed on the glass),
     * negative is the clamp closing in. Decays fast; the renderer displaces the brackets by it.
     */
    var sightKick = 0f; private set
    /** Seconds of static left in the periscope after a hull hit. */
    var staticT = 0f; private set
    /** Rings on the glass — see [Impact]. Drained by age. */
    val impacts = ArrayList<Impact>()

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

    // ------------------------------------------------------------------ energy and the shield
    var poolX = 0f; var poolZ = 0f; var poolActive = false; private set
    var poolT = 0f; private set
    /** The pool's share of the sight ramp, exactly as the Bit's. */
    var poolVis = 0f; private set
    /** 0 … 1 across [POOL_DRAW_T] while the tank is standing in the pool. */
    var poolDraw = 0f; private set
    /** 1 → 0 while a drained pool folds itself away — the renderer's collapse. */
    var poolCollapse = 0f; private set
    /** 0 drained … 1 standing. The column's height, and whether a draw is possible at all. */
    var poolLevel = 0f; private set
    /** The tank is in the pool and there is nothing to restore — the HUD says so rather than staying mute. */
    var poolFullHint = false; private set
    /** Which maze the pool was placed in — it is a feature of the maze, placed once per maze. */
    private var poolMaze: Maze? = null
    private var poolSipCd = 0f
    /** Charges left on the shell, 0 … [SHIELD_MAX]. The one number the whole system is about. */
    var shield = 0; private set
    /** 1 → 0 after the shell eats a hit: the cyan frame pulse and the bubble's own flare. */
    var shieldFlash = 0f; private set
    /** Where the hit came FROM, as a unit vector, so the shell brightens on the side that took it. */
    var shieldHitX = 0f; var shieldHitY = 0f; var shieldHitZ = 0f; private set

    // ------------------------------------------------------------------ title / attract
    /**
     * The demo the game plays to itself — see [Attract]. It is built once when the title is entered
     * and lives until a game starts, so its maze, its route and its cast survive the loop; only the
     * timeline is re-armed each time round.
     */
    var attract: Attract? = null; private set
    private var attractLoops = -1
    /** Which system lore line is on the glass, -1 for none, and how long it has been there. */
    var loreIdx = -1; private set
    var loreAge = 0f; private set
    var loreHold = 0f; private set
    /** The pilot's answer: the caption, verbatim from the clip's own script. */
    var pilotText = ""; private set
    var pilotAge = 0f; private set
    var pilotHold = 0f; private set
    /** The Protocol's own line on the glass in play — see [maybeProtocol]. */
    var protocolText = ""; private set
    var protocolAge = 0f; private set
    var protocolHold = 0f; private set
    var showTap = false; private set
    /** When each of the eight lore lines is spoken, and when each of the two pilot answers lands. */
    private var loreT = FloatArray(0)
    private var pilotT = FloatArray(2)
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
        6 -> when (store.difficulty) { 0 -> "EASY"; 2 -> "HARD"; else -> "NORMAL" }
        else -> if (resetArmed) "TAP AGAIN TO CONFIRM" else ""
    }

    // ------------------------------------------------------------------ [THE THREE SETTINGS]
    /**
     * EVERY DIFFICULTY DIFFERENCE IN ONE PLACE, so the three are readable against each other rather
     * than scattered through the AI as `if (hard)`. The dial used to run NORMAL / HARD — the only
     * control a struggling player could reach made the game harder — and NORMAL itself was tuned
     * for someone who already knew the maze.
     *
     * EASY is not a different game: same machines, same crush, same economy, same waves. It gives
     * the player TIME — a longer dwell between the lock and the throw, a longer wait between
     * throws, one fewer machine, and no two-hit shells until the maze has already been re-drawn
     * twice. Everything that makes this game what it is happens at every setting; only the pace at
     * which it happens moves. The score multiplier is the honest price.
     */
    private val diff get() = store.difficulty.coerceIn(0, 2)
    /** Machines in wave [w]. Wave one is two on every setting: a wave is where you learn a wave. */
    private fun waveCount(w: Int): Int = when (diff) {
        0 -> min(1 + w, 6)
        2 -> min(2 + w, 9)
        else -> min(1 + w, 8)
    }
    /** From which wave a Recognizer takes two shells. */
    private val armourWave get() = when (diff) { 0 -> 9; 2 -> 1; else -> 6 }
    /** The dwell between the eye finding you and the disc leaving it. */
    private val dwell get() = when (diff) { 0 -> 0.95f; 2 -> 0.45f; else -> LOCK_DWELL }
    /** Multiplies the settled fire cooldown: EASY throws two thirds as often. */
    private val fireRate get() = when (diff) { 0 -> 1.55f; 2 -> 0.82f; else -> 1f }
    /** Added to the base chase speed. */
    private val speedBonus get() = when (diff) { 0 -> -0.5f; 2 -> 0.8f; else -> 0f }
    /** Score is paid for the risk taken: three quarters on EASY, half again on HARD. */
    private val scoreMul get() = when (diff) { 0 -> 0.75f; 2 -> 1.5f; else -> 1f }

    private val rng = Random(System.nanoTime())
    private val rnd: () -> Float = { rng.nextFloat() }
    private val tmp = FloatArray(2)
    private val tmp3 = FloatArray(3)
    private var lastKillSay = -99f
    private var humLevel = 0f
    /** The pool is placed but not yet standing — wave one holds it back until half the wave is down. */
    private var poolPending = false
    /** Set only while [debugStart] is going through [startGame], so the games counter stays honest. */
    private var debugLaunch = false
    /**
     * THIS WHOLE GAME IS A VERIFICATION LAUNCH and writes NOTHING to the records.
     *
     * The device's own statistics were the strongest single piece of evidence in the last review
     * and they were partly fiction: `store.games` was incremented by [debugStart] as well as by a
     * played start, so an unknown share of 161 recorded games were `am start --ei wave 6` launches
     * on a developer's hardware. A record that mixes the two cannot be reasoned from — by a
     * reviewer or by anybody else — so a debug game now leaves the counters exactly as it found
     * them, for the whole game and not merely for its first frame.
     */
    private var debugGame = false
    /** How many of [PROTOCOL_LINES] the Protocol has spent this game. */
    private var protocolIdx = 0
    /** Which line it just spoke, 1-based, so the wave's own exchange can answer the last of them. */
    private var protocolSaid = 0
    /** A life was lost under the clamp: the arena falls back when the legs open, not before. */
    private var pendingScatter = false
    /** 1 → 0 over the beat the arena falls back after a death — the sight's own cyan-white flare. */
    var scatterFlash = 0f; private set

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
    /** The Bit is holding its breath: some Recognizer has the shot line on you. See [updateBitVoice]. */
    private var bitHushed = false
    /** How many quick chirps it owes you for having got out of sight. */
    private var bitRelief = 0

    /**
     * No two pilot lines closer together than this, ever.
     *
     * IT WAS SEVEN, AND THE GATES WERE TUNED AGAINST A RUN NOBODY WAS HAVING. Twenty-one lines
     * behind a 7 s floor and 22–45 s per-line cooldowns is a beautiful mix for a game whose runs
     * last several minutes; measured runs lasted thirteen to thirty-seven seconds, and a player
     * heard three or four distinct lines in a whole game. Five seconds, with the per-line locks
     * scaled by [PILOT_CD] below, gets a two-minute run to six or seven — still a person who
     * occasionally speaks rather than a commentary track, which is the whole point of the system,
     * but a person you actually meet.
     */
    private val PILOT_GAP = 5f
    /** Every per-line cooldown is scaled by this: repetition is still the enemy, at two thirds the lock. */
    private val PILOT_CD = 0.66f

    private fun pilot(id: String, gap: Float = PILOT_GAP, cd: Float = 24f, chance: Float = 1f,
                      once: Boolean = false, delay: Float = 0f, patience: Long = 1500L): Boolean {
        if (!store.voice) return false
        if (once && id in pilotOnce) return false
        if (time - pilotLastAny < gap) return false
        if (time - (pilotLast[id] ?: -999f) < cd * PILOT_CD) return false
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
     * THE SHIELD'S THREE BEATS, and its two conversations.
     *
     * The draw and the collapse are EVENTS — they change what the wave is, so the machine states
     * them and the pilot answers, on the same delayed-cue pattern as the wave announcement and the
     * death. The middle beat is not an event, it is a hit taken, and it happens up to three times
     * in a few seconds: a system line there would be a stuck record, so it gets sound only, and the
     * pilot remarks on it less than half the time behind the usual global gap.
     *
     * The draw is the one place the pilot is allowed to jump a short gap ([gap] 3 s rather than the
     * standing 7): crossing the arena for the pool is a deliberate act several seconds long, and a
     * "shields holding" that arrives after the next thing has already shot at you is worthless.
     */
    private fun onShieldUp(topUp: Boolean) {
        shield = SHIELD_MAX
        host.sfx(com.x3paranoids.audio.Sfx.SHIELD_UP)
        host.say("energy", urgent = true)
        pilot("hero_shield_up", gap = 3f, cd = 40f, chance = if (topUp) 0.45f else 0.9f,
            delay = after("energy"), patience = 3000L)
    }

    private fun onShieldHit() {
        host.sfx(com.x3paranoids.audio.Sfx.SHIELD_HIT, 0.94f + rng.nextFloat() * 0.14f)
        pilot("hero_shield_hit", gap = 8f, cd = 25f, chance = 0.45f, patience = 1500L)
    }

    private fun onShieldDown() {
        host.sfx(com.x3paranoids.audio.Sfx.SHIELD_DOWN)
        // The shell comes apart into the segments it was drawn from, rushing outward past the
        // periscope — [Derez.seedShield], the same machinery a Recognizer dies on.
        while (derezzes.size >= MAX_DEREZ) derezzes.removeAt(0)
        derezzes += Derez(px, EYE_H - SHIELD_DROP, pz, yaw, SHIELD_R, 0f, false, shield = true).also { it.seedShield(rnd) }
        burst(px, EYE_H, pz, 14, 0.55f, 0.95f, 1f)
        host.say("shield_down", urgent = true)
        pilot("hero_shield_down", gap = 0f, cd = 30f, chance = 0.9f, delay = after("shield_down"), patience = 2500L)
    }

    // ------------------------------------------------------------------ boot / title
    fun boot() { enterTitle() }

    private fun enterTitle() {
        state = State.TITLE; stateT = 0f
        recognizers.clear(); shots.clear(); sparks.clear(); derezzes.clear(); bitActive = false
        deathSink = 0f
        releasePlayer(); impacts.clear(); crushShake = 0f; sightKick = 0f; staticT = 0f
        poolActive = false; poolMaze = null
        clearCues()
        host.stopHero(); host.stopVoice()
        val plan = composeAttract()
        attract = Attract(System.nanoTime(), plan)
        attractLoops = 0
        armAttract()
    }

    /**
     * THE INTRO'S SCHEDULE, DERIVED FROM THE VOICE ITSELF.
     *
     * Every beat below is stated as "when the last one has finished speaking, plus air", never as a
     * stopwatch reading — the clip lengths come out of the manifests through [after]. So the demo
     * cannot drift out of time with its own narration, and re-rendering a line does not require
     * re-timing the film. Three of the gaps are the whole point of doing it this way:
     *
     *  - after "THE GAME WAS STOLEN" the machine shuts up for a beat and the CAMERA MOVES. The
     *    world starting to slide on the silence after the theft is the one edit in here that has to
     *    be exact, and it is the only place in the intro where nothing is said at all.
     *  - the pilot answers the MONOPOLY CONTROL PROTOCOL only once the machine has finished naming
     *    it. Overlapped, the two voices are mud; sequenced, they are an argument.
     *  - "YOU REMEMBER" is followed by a shell, not by another sentence.
     */
    private fun composeAttract(): AttractPlan {
        fun d(i: Int) = max(400, host.voiceDurationMs(INTRO[i])) / 1000f
        fun h(id: String) = max(400, host.heroDurationMs(id)) / 1000f
        val t = FloatArray(8)
        t[0] = 0.95f                                   // GREETINGS, PROGRAM — over the power-up
        t[1] = t[0] + d(0) + 0.45f                     // a programmer wrote a game
        t[2] = t[1] + d(1) + 0.55f                     // THE GAME WAS STOLEN
        val flight = t[2] + d(2) + 0.30f               // ...and the camera moves, into the silence
        t[3] = t[2] + d(2) + 1.25f                     // the thief built the Protocol on its bones
        val p0 = t[3] + d(3) + 0.35f                   // PILOT: the Protocol doesn't own this maze
        t[4] = p0 + h(PILOT_MCP) + 0.60f               // now the Recognizers hunt... (and one crosses,
                                                       // on the loop's own clock — see Attract.seedPatrol)
        t[5] = t[4] + d(4) + 0.50f                     // YOU REMEMBER.
        val aim = t[5] + d(5) + 0.40f                  // the machine at the end turns red
        val fire = aim + 1.25f
        val p1 = fire + 1.55f                          // PILOT: I remember every corner of this maze
        val bit = p1 + 0.90f
        t[6] = p1 + h(PILOT_KILL) + 0.50f              // FIND THE BIT. SURVIVE THE WAVES.
        val settle = t[6] + d(6) + 0.40f               // the title comes back; INSERT COIN
        t[7] = settle + 0.55f                          // END OF LINE.
        val end = t[7] + d(7) + 4.60f                  // and hold, so the invitation can be read
        loreT = t
        pilotT = floatArrayOf(p0, p1)
        android.util.Log.i("X3Paranoids", "attract plan: flight=%.1f aim=%.1f fire=%.1f bit=%.1f settle=%.1f end=%.1f"
            .format(flight, aim, fire, bit, settle, end))
        return AttractPlan(trace = 1.85f, flight = flight, aim = aim,
            fire = fire, bit = bit, settle = settle, end = end)
    }

    /** Lay the loop's cues out again — once when the title is entered, then once per loop. */
    private fun armAttract() {
        clearCues()
        host.stopHero(); host.stopVoice()
        loreIdx = -1; loreAge = 0f; loreHold = 0f
        pilotText = ""; pilotAge = 0f; pilotHold = 0f
        showTap = false
        for (i in INTRO.indices) cue(loreT[i]) { sayLore(i) }
        cue(pilotT[0]) { sayPilot(PILOT_MCP) }
        cue(pilotT[1]) { sayPilot(PILOT_KILL) }
        attract?.let { a -> cue(a.plan.settle) { showTap = true } }
    }

    private fun sayLore(i: Int) {
        loreIdx = i; loreAge = 0f
        loreHold = max(400, host.voiceDurationMs(INTRO[i])) / 1000f + 0.9f
        host.say(INTRO[i], patienceMs = 4000L)
    }

    private fun sayPilot(id: String) {
        pilotText = PILOT_TEXT[id] ?: ""; pilotAge = 0f
        pilotHold = max(400, host.heroDurationMs(id)) / 1000f + 0.9f
        host.hero(id, 3000L)
    }

    /**
     * From the voice thread: a lore line actually began. The crawl is SCHEDULED, but it belongs to
     * the clip — so if the bus made the machine wait its turn, the line on the glass waits with it.
     */
    fun onVoiceLineStart(id: String) {
        if (state != State.TITLE) return
        val i = INTRO.indexOf(id)
        if (i < 0) return
        loreIdx = i; loreAge = 0f
        loreHold = max(400, host.voiceDurationMs(id)) / 1000f + 0.9f
    }
    fun onVoiceLineEnd(id: String) {}

    /**
     * From the pilot's own voice thread: a hero line ACTUALLY BEGAN, so caption it — in the title
     * and in the arena alike (see [PILOT_TEXT]).
     *
     * Captioning on the start callback rather than where the line is requested is the only version
     * of this that cannot lie. A pilot line is queued behind a [VoiceBus] that may make it wait, and
     * may drop it entirely once its patience runs out; text raised at the request would appear for
     * lines that never played and appear early for lines that did. This appears exactly when the
     * clip does and holds for exactly as long as it runs.
     */
    fun onHeroLineStart(id: String) {
        val s = PILOT_TEXT[id] ?: return
        pilotText = s; pilotAge = 0f
        pilotHold = max(400, host.heroDurationMs(id)) / 1000f + 0.75f
    }

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
        // CLAMPED. The hull is between a Recognizer's legs; the pad does nothing to it until they
        // open. The periscope still looks, the cannon still fires — see [updateCrush].
        if (caught) return
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

    /**
     * THE PAD CROSSED THE THRESHOLD VERTICALLY AND IS STILL DOWN — start driving and keep driving.
     *
     * The DASH goes in here, immediately, exactly as [swipe] would have applied it on finger-up:
     * a gesture that lifts a moment later has therefore had precisely today's behaviour and nothing
     * else, which is the whole trick that lets one gesture be two. Everything the HOLD adds is
     * applied per-frame in [updatePlay].
     *
     * Called again with the other direction when the finger drags back past the hysteresis, and
     * that is a plain re-dash: the new impulse is one [IMPULSE] against a hull doing at most
     * [MAX_SPEED], so the instantaneous change is exactly the size of a dash from standstill — the
     * comfort budget this game already spends — and the sustained thrust turns the tank round
     * inside about a sixth of a second rather than the two directions fighting.
     */
    fun driveStart(forward: Boolean, source: String) {
        if (!holdDriveArmed) return
        val sign = if (forward) 1 else -1
        if (driveDir == sign) return
        val flip = driveDir != 0
        driveDir = sign; driveT = 0f; driveLogT = 0f
        val fx = sin(yaw); val fz = -cos(yaw)
        impulse(fx * sign, fz * sign)
        android.util.Log.i("X3Paranoids", "DRIVE %s dir=%s src=%s p=(%.2f,%.2f) yaw=%.0f v=%.2f".format(
            if (flip) "flip" else "start", if (forward) "FWD" else "REV", source, px, pz,
            yaw * 57.2958f, hypot(vx, vz)))
    }

    /** The finger lifted (or the gesture was cancelled). The existing damping does the stopping. */
    fun driveEnd(source: String) {
        if (driveDir == 0) return
        android.util.Log.i("X3Paranoids", "DRIVE end dir=%s src=%s held=%.2fs p=(%.2f,%.2f) v=%.2f".format(
            if (driveDir > 0) "FWD" else "REV", source, driveT, px, pz, hypot(vx, vz)))
        driveDir = 0; driveT = 0f
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
            // three settings now, so it STEPS rather than toggles — one swipe, one notch, and it
            // wraps at the top so EASY is never more than a swipe away from wherever you are
            6 -> store.difficulty = (store.difficulty + (if (d >= 0) 1 else 2)) % 3
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
        // THE DEMO IS DROPPED WHERE IT STANDS. A tap during the attract loop is a player who has
        // seen enough, and the one thing they must not get is a frame of somebody else's camera in
        // their game: the loop's world goes now, and host.recentreHead() below makes the azimuth
        // they were sitting at when they tapped the direction they are facing.
        attract = null; attractLoops = -1
        loreIdx = -1; pilotText = ""; showTap = false
        protocolText = ""; protocolAge = 0f; protocolHold = 0f
        mazeSeed = System.nanoTime(); maze = Maze(8, 8, mazeSeed)
        lives = 3; score = 0; wave = 0; elapsed = 0f; kills = 0; invuln = 0f; damageFlash = 0f
        vx = 0f; vz = 0f; hullYaw = 0f; hullTarget = 0f; turnBlend = 0f; newHigh = false
        driveDir = 0; driveT = 0f; driveLogT = 0f; bumpCd = 0f
        derezzes.clear(); deathSink = 0f
        pilotLast.clear(); pilotOnce.clear(); pilotLastAny = -99f; pilotStreak = 0; pilotKillIdx = 0
        lastKillT = -99f; lastKillSay = -99f
        hitsRecent = 0; lastHitT = -99f; bitNearSaid = false; bitChirpCd = 1.4f; bitNoCd = 0f
        shield = 0; shieldFlash = 0f; poolActive = false; poolDraw = 0f; poolCollapse = 0f; poolVis = 0f
        poolLevel = 0f; poolMaze = null; poolFullHint = false; poolPending = false
        releasePlayer(); impacts.clear(); crushShake = 0f; sightKick = 0f; staticT = 0f
        lockedOn = false; tracking = false
        bitHint = 0f; cardT = CARD_T; protocolIdx = 0; protocolSaid = 0; scatterFlash = 0f
        pendingScatter = false; bitHushed = false; bitRelief = 0
        debugGame = debugLaunch
        if (!debugGame) store.games = store.games + 1
        placePlayer(maze.cols / 2, maze.rows / 2)
        host.recentreHead()
        host.sfx(com.x3paranoids.audio.Sfx.START)
        nextWave()
    }

    private fun placePlayer(c: Int, r: Int) { px = maze.cellX(c); pz = maze.cellZ(r); vx = 0f; vz = 0f }

    /**
     * DEBUGGABLE BUILDS ONLY (MainActivity gates it): start a game at [atWave] wearing [withShield]
     * charges — `am start -n com.x3paranoids/.MainActivity --ei wave 2 --ei shield 3`. It exists
     * because the things worth verifying on the glasses — the pool, a shielded capture, wave-six
     * armour — live several minutes of play past the title, and a hull that turns in quarter
     * steps cannot be driven there over adb in any reasonable time. It goes through [startGame]
     * and [nextWave] exactly as a played game does; only the counter is advanced first.
     */
    fun debugStart(atWave: Int, withShield: Int, atPool: Boolean = false) {
        if (state != State.TITLE) return
        // AND IT DOES NOT COUNT AS A GAME. `store.games` used to be incremented here as well as in
        // a played start, so an unknown share of the device's recorded games were `--ei wave 6`
        // verification launches on a developer's own hardware. A statistic that mixes the two is
        // not evidence about anything.
        debugLaunch = true
        startGame()
        debugLaunch = false
        cardT = 0f
        if (atWave > 1) { wave = atWave - 1; nextWave() }
        shield = withShield.coerceIn(0, SHIELD_MAX)
        // [atPool] stands the tank in the pool's cell, a few units short of the rings, facing them
        if (atPool && poolActive) {
            placePlayer(maze.colOf(poolX), maze.rowOf(poolZ))
            maze.move(px, pz, 0f, 3.2f, PLAYER_R, tmp); px = tmp[0]; pz = tmp[1]
            hullYaw = 0f; hullTarget = 0f
        }
        android.util.Log.i("X3Paranoids", "DEBUG start wave=$wave shield=$shield atPool=$atPool p=(%.1f,%.1f)".format(px, pz))
    }

    private fun nextWave() {
        wave++
        state = State.PLAY; stateT = 0f
        shots.clear(); recognizers.clear()
        // a machine that was holding you when the wave rolled over (it cannot: the crusher is the
        // last thing alive, and a dead crusher releases) — belt and braces, the clamp opens
        releasePlayer()
        if (wave > 1 && (wave - 1) % 3 == 0) { mazeSeed += 7919L; maze = Maze(8, 8, mazeSeed); placePlayer(maze.cols / 2, maze.rows / 2) }
        if (!debugGame) store.bestWaveReached = wave
        val n = waveCount(wave)
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
            rec.hp = if (wave >= armourWave) 2 else 1
            // THE OPENING SPREAD, pushed out from 2–4 s to 3–5. The first half-minute of a wave is
            // where the player finds out where the machines are; a wave whose first discs are in
            // the air before the sight has finished settling teaches nothing except that it is
            // unfair. WAVE ONE GETS ANOTHER TWO SECONDS on top of that — it is the only wave that
            // is somebody's first, it is the whole of this game's tutorial, and it is where every
            // recorded run on this device ended.
            rec.fireCd = 3f + rng.nextFloat() * 2f + (if (wave == 1) 2f else 0f)
            recognizers += rec
        }
        // THE POOL IS A FEATURE OF THE MAZE — placed once when the maze is, at the cell furthest by
        // BFS from where the tank stands, and left there for the life of the maze so it is a place
        // you learn and can go back to (see [THE ENERGY ECONOMY]). It refills on its own clock, so
        // whatever it held at the end of the last wave it still holds now.
        //
        // NOT AT THE START OF WAVE ONE — but not withheld until wave two either. Wave one is where
        // the game teaches the base loop, and a second cyan objective in the arena on the first
        // screen you have ever seen competes for attention a new player has none of. But a device
        // whose records showed wave two had never been CLEARED was a device on which most players
        // never met the energy economy at all: the pool, the shell, the press, the whole system
        // sat behind a wave nobody finished. So it arrives LATE IN WAVE ONE, once half the wave is
        // down — by then the loop has been taught, the first shell has probably been wanted, and
        // the column standing up at the far end of the maze is a reveal rather than a distraction.
        poolDraw = 0f; poolSipCd = 0f; poolFullHint = false
        poolPending = false
        if (poolMaze !== maze) {
            var best: IntArray? = null; var bestD = -1
            for (cell in far) { val dd = dist[cell[0]][cell[1]]; if (dd > bestD) { bestD = dd; best = cell } }
            best?.let {
                poolX = maze.cellX(it[0]); poolZ = maze.cellZ(it[1])
                poolLevel = 1f; poolCollapse = 0f; poolT = 0f; poolVis = 0f
                poolMaze = maze
                if (wave == 1) { poolActive = false; poolPending = true } else poolActive = true
            }
        }
        // Every later wave in the same maze simply finds it standing where it was left.
        if (wave >= 2 && poolMaze === maze) poolActive = true
        // THE BIT GOES AS FAR FROM THE POOL AS THE MAZE ALLOWS, and that placement is the whole
        // reason there are two objectives. Both are already far from the player; putting the Bit
        // at the cell of maximum BFS distance FROM THE POOL means no single route sweeps them both,
        // so every wave asks the same question — energy first and hunt the Bit shielded, or the Bit
        // first and take the wave bare. The player's own distance only breaks ties. Before the pool
        // exists (wave one) the Bit simply hides somewhere far.
        val bitCells = far.filter { dist[it[0]][it[1]] >= 3 }
        var bc = if (bitCells.isNotEmpty()) bitCells[rng.nextInt(bitCells.size)] else far[0]
        if (poolActive || poolPending) {
            val fromPool = maze.distances(maze.colOf(poolX), maze.rowOf(poolZ))
            var best: IntArray? = null; var bestScore = -1
            for (cell in bitCells) {
                val dp = fromPool[cell[0]][cell[1]]
                if (dp < 3) continue
                val sc = dp * 16 + dist[cell[0]][cell[1]] + rng.nextInt(6)
                if (sc > bestScore) { bestScore = sc; best = cell }
            }
            best?.let { bc = it }
        }
        bitX = maze.cellX(bc[0]); bitZ = maze.cellZ(bc[1]); bitActive = true; bitT = 0f
        bitNearSaid = false; bitChirpCd = 2.2f; bitNoCd = 3f
        host.sfx(com.x3paranoids.audio.Sfx.WAVE)
        val waveId = if (wave <= 12) "wave_$wave" else "wave_more"
        // THE PROTOCOL SPEAKS FIRST — see [maybeProtocol]. It goes AHEAD of the wave announcement,
        // not behind it, and that placement was measured rather than chosen: scheduled after the
        // wave line and the pilot's answer, the line landed five or six seconds in, by which time
        // the arena is live and an urgent TANK HIT had already cleared it off the queue. The
        // opening beat of a wave is the only reliable quiet in this game — no machine can throw for
        // at least three and a half seconds — and it is where a sentence that only happens three
        // times a game belongs. It also reads better: the thing that owns the maze speaks, and only
        // then does its machine announce the wave.
        val preT = protocolOpening()
        if (preT <= 0f) host.say(waveId, urgent = true) else cue(preT) { host.say(waveId, urgent = true) }
        cue(preT) { host.say("incoming") }
        // THE FIRST CONVERSATION. The system announces the wave and says INCOMING; the pilot answers
        // it once the machine has finished talking. Wave one is the opening statement and always
        // lands; after that the answer is occasional, and from wave six it is the tired one.
        val answerAt = preT + after(waveId, "incoming")
        val answered = when {
            wave == 1 -> pilot("hero_start", gap = 0f, once = true, delay = answerAt, patience = 6000L)
            wave >= 6 -> pilot("hero_wave_late", gap = 0f, cd = 50f, chance = 0.55f, delay = answerAt, patience = 5000L)
            else -> pilot("hero_wave", gap = 0f, cd = 45f, chance = 0.40f, delay = answerAt, patience = 5000L)
        }
        // Only when the wave line did NOT fire: the villain gets named out loud, once a game and
        // never early. A flourish stops being one the moment it is on a schedule.
        if (!answered && wave >= 4) pilot("hero_mcp", gap = 0f, chance = 0.22f, once = true, delay = answerAt, patience = 5000L)
        // and the pilot answers the LAST of the Protocol's three lines — the one that takes the
        // machines off the leash — after the wave's own exchange has finished
        if (protocolSaid == PROTOCOL_LINES.size) {
            protocolSaid = -1
            pilot("hero_protocol", gap = 0f, cd = 0f, once = true, delay = answerAt + 1.4f, patience = 5000L)
        }
    }

    /**
     * THE PROTOCOL SPEAKS — see [PROTOCOL_WAVES]. Called at the very top of a wave, BEFORE the wave
     * announcement, and returns how long the rest of the wave's audio must wait for it (0 on the
     * waves it says nothing).
     *
     * It is the SYSTEM voice, because the thing that owns this maze has been the voice narrating it
     * since GREETINGS, PROGRAM, and the line is captioned for the length of the clip, low on the
     * sight where the intro's crawl sat. That is the ONE place in play the system gets glass, and
     * it gets it because these three sentences are the only ones it ever says that are not already
     * printed somewhere on the HUD.
     */
    private fun protocolOpening(): Float {
        if (protocolIdx >= PROTOCOL_LINES.size) return 0f
        if (wave != PROTOCOL_WAVES[protocolIdx]) return 0f
        val i = protocolIdx++
        val id = PROTOCOL_LINES[i]
        host.say(id, urgent = true, patienceMs = 5000L)
        protocolText = PROTOCOL_TEXT[i]; protocolAge = 0f
        protocolHold = max(400, host.voiceDurationMs(id)) / 1000f + 1.4f
        protocolSaid = i + 1
        android.util.Log.i("X3Paranoids", "PROTOCOL wave=$wave line=$id \"${PROTOCOL_TEXT[i]}\"")
        return after(id)
    }

    private fun waveCleared() {
        state = State.WAVE_CLEAR; stateT = 0f
        val bonus = 250 * wave
        score += bonus; bonusText = "BONUS $bonus"
        if (!debugGame) store.bestWave = wave
        host.sfx(com.x3paranoids.audio.Sfx.CLEAR)
        host.say("wave_clear", urgent = true)
        // THE BIT WAS LEFT BEHIND. It has been chirping at you for a whole wave; if you never came,
        // it says so — the one reaction that makes it a character with an opinion about you rather
        // than a pickup you happened not to collect.
        if (bitActive) {
            bitActive = false
            cue(after("wave_clear") - 0.15f) { host.sfx(com.x3paranoids.audio.Sfx.BIT_LOSE, 1f, 0.75f) }
        }
        // THE POOL STAYS. It is a feature of the maze, not of the wave; it keeps refilling through
        // the clear and is there for the next one. Only a draw in progress is abandoned. Charges
        // already drawn STAY — see [THE ENERGY ECONOMY].
        poolDraw = 0f
        // The pilot answers the clear; failing that, it sometimes just thinks out loud in the quiet.
        val at = after("wave_clear")
        pilot("hero_wave_clear", gap = 5f, cd = 40f, chance = 0.70f, delay = at, patience = 4000L) ||
            pilot("hero_quiet", gap = 5f, cd = 90f, chance = 0.45f, delay = at, patience = 4000L)
    }


    /**
     * [srcX]/[srcZ] is where the hit came FROM — a bolt's own position, or the Recognizer that rode
     * you down. The hull does not care, but the SHELL does: the bubble brightens on the side that
     * took it, which is the difference between "something hit me" and "something hit me from
     * there". Defaulting to the tank's own position gives a hit no direction, and the shell simply
     * flares evenly, which is the honest thing to draw when nothing knows better.
     */
    private fun damagePlayer(srcX: Float = px, srcZ: Float = pz, srcY: Float = EYE_H, force: Boolean = false) {
        // [force] is the crush landing: a capture only ever BEGINS on a tank with no grace left,
        // and nothing else can touch a held tank, so this is belt and braces — the landing beat
        // must never be a beat on which nothing happened.
        if ((invuln > 0f && !force) || state != State.PLAY) return
        // THE SHELL EATS IT FIRST, and buys only [SHIELD_IFRAME] of grace rather than the 2.6 s a
        // real hit does. That asymmetry is the whole economy: the shield stops you dying for a
        // mistake, it does not stop you being under fire.
        if (shield > 0) {
            shield--
            invuln = SHIELD_IFRAME
            shieldFlash = 1f
            vx *= 0.55f; vz *= 0.55f
            var dx = srcX - px; var dy = srcY - EYE_H; var dz = srcZ - pz
            val dl = sqrt(dx * dx + dy * dy + dz * dz)
            if (dl > 0.05f) { shieldHitX = dx / dl; shieldHitY = dy / dl; shieldHitZ = dz / dl }
            else { shieldHitX = 0f; shieldHitY = 0f; shieldHitZ = 0f }
            if (shield > 0) onShieldHit() else onShieldDown()
            return
        }
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
            // THE ARENA FALLS BACK — see [THE SCATTER]. A tank held between a machine's legs cannot
            // use a window it spends clamped, so a capture defers the scatter to the beat the legs
            // open ([open]); anything else gets it now, on the frame the life is lost.
            if (caught) pendingScatter = true else scatter()
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
     * THE SCATTER — the whole of [THE SCATTER], applied. Called on the frame a life is lost, or at
     * the moment the clamp opens if one was lost under it.
     *
     * Every hostile disc in the air is cut (they were thrown at a tank that no longer exists, and
     * they are the specific thing that used to land 0.11 s into the next life). Every machine
     * within [SCATTER_R] is reeled, its throw pushed out, its charge cancelled, its lock sting
     * re-armed, and shoved [SCATTER_PUSH] units back through the maze's own collision so the
     * recovery is SEEN and not merely timed. Machines further off are untouched — the arena is
     * shocked, not reset.
     */
    private fun scatter() {
        pendingScatter = false
        var cut = 0; var reeled = 0
        // THE DISCS ARE FLAGGED, NOT REMOVED — see [Shot.dead], and this is not a style choice.
        // The commonest way to lose a life is a disc landing, which means [scatter] is usually
        // reached from inside [discAtTank], which is itself inside updateWorld's iterator over this
        // very list. Removing here invalidated that iterator and the next frame's `si.next()` threw
        // ConcurrentModificationException on the GL thread — caught on the glasses, a hard crash on
        // the first death that happened to have a second disc in the air:
        //   java.util.ConcurrentModificationException at Game.updateWorld
        // Flagging leaves the list's structure alone; the shot loop drains dead discs at the top of
        // its next pass and nothing acts on one in between.
        for (s in shots) {
            if (s.friendly || s.dead) continue
            s.dead = true
            burst(s.x, s.y, s.z, 5, 1f, 0.4f, 0.3f); cut++
        }
        for (r in recognizers) {
            if (r.hp <= 0) continue
            val d = hypot(r.x - px, r.z - pz)
            if (d > SCATTER_R) continue
            reeled++
            r.stagger = max(r.stagger, SCATTER_STAGGER)
            r.fireCd = max(r.fireCd, SCATTER_FIRE_CD)
            r.crushCd = max(r.crushCd, CRUSH_CD)
            r.charge = 0f; r.chargeCd = CHARGE_CD
            r.lockSaid = false; r.trackSaid = false
            r.reel = rng.nextFloat() * 6.2832f
            r.noLineT = 0f; r.stuckT = 0f
            // AND THEY LOSE YOU. `chasing` outlives line of sight by five seconds so a machine that
            // watched you round a corner still comes after you — correct behaviour, and exactly the
            // wrong behaviour on the one beat the player has been given to disappear. Clearing the
            // memory means a scattered machine that cannot actually SEE you goes back on its rounds
            // rather than pathing to the wreck, so breaking the line during the window really does
            // end the engagement.
            r.seenT = -99f; r.targetC = -1; r.reroute = 0f
            // shoved back along the line from the wreck, wall-clipped like everything else
            var bx = r.x - px; var bz = r.z - pz
            val bl = hypot(bx, bz)
            if (bl < 0.05f) { bx = -sin(r.yaw); bz = cos(r.yaw) } else { bx /= bl; bz /= bl }
            maze.move(r.x, r.z, bx * SCATTER_PUSH, bz * SCATTER_PUSH, Recognizer.RADIUS, tmp)
            r.x = tmp[0]; r.z = tmp[1]
        }
        // AND THE TANK IS THROWN CLEAR. The review's words were "losing a life does not move you or
        // scatter them"; scattering them is above, and this is the other half. Not a teleport —
        // a teleport on a head-worn display is the one thing this game must never do — but a KICK,
        // exactly one [IMPULSE] directly away from the machines that were on you, which is the same
        // shove a dash gives and therefore a motion the player has already felt a hundred times.
        // It carries the hull about four units, which is most of a cell: enough to be out of the
        // lane you died in, and it goes through the maze's own collision like any other movement.
        // When nothing was near enough to scatter there is no crowd to be thrown clear of, and the
        // hull simply keeps the stop the hit already gave it.
        if (reeled > 0) {
            var ax = 0f; var az = 0f
            for (r in recognizers) {
                if (r.hp <= 0) continue
                val dd = hypot(r.x - px, r.z - pz)
                if (dd > SCATTER_R || dd < 0.05f) continue
                ax += (px - r.x) / dd; az += (pz - r.z) / dd
            }
            val al = hypot(ax, az)
            if (al > 0.05f) { vx = ax / al * IMPULSE; vz = az / al * IMPULSE }
        }
        scatterFlash = 1f
        lockedOn = false; tracking = false
        host.sfx(com.x3paranoids.audio.Sfx.SCATTER)
        host.sfx(com.x3paranoids.audio.Sfx.THRUST, 0.75f, 0.7f)
        // The pilot names the window ONCE a game — that is how the player learns the 2.6 s is real
        // and worth running in, rather than a red flash they sit through.
        pilot("hero_scatter", gap = 0f, cd = 0f, once = true, delay = 0.85f, patience = 2500L)
        android.util.Log.i("X3Paranoids", "SCATTER discs=%d reeled=%d/%d invuln=%.2f".format(cut, reeled, recognizers.size, invuln))
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
        newHigh = !debugGame && score > store.highScore && score > 0
        if (!debugGame) store.highScore = score
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
        // A drive belongs to a LIVE ARENA. The menu freezing the world, the hull derezzing, a wave
        // ending — all of them drop the throttle, and a finger still on the pad has to be lifted
        // and put back to take it again. (The pad's own classifier stands down with it: see
        // [holdDriveArmed].)
        if (menuOpen) { driveEnd("menu"); return }
        if (state != State.PLAY) driveEnd("state")
        stateT += dt
        muzzle = max(0f, muzzle - dt * 9f)
        damageFlash = max(0f, damageFlash - dt * 1.6f)
        // Faster than the damage flash and never as red: a shield hit is a thing that DIDN'T happen
        // to you, and it must not read like one that did.
        shieldFlash = max(0f, shieldFlash - dt * 2.6f)
        // THE SCATTER'S FLARE RUNS FOR AS LONG AS THE WINDOW DOES. It began at 1.4 a second — seven
        // tenths of a second, a blink — which made it one more flash in a frame that already has a
        // red damage border in it. It is not a flash, it is a CLOCK: the one cue that says how long
        // the arena will stay off you, so it decays across the same 2.4 s the grace lasts and the
        // player can watch it run out. See [scatter] and [GLRenderer.buildPlayHud].
        scatterFlash = max(0f, scatterFlash - dt * 0.42f)
        poolCollapse = max(0f, poolCollapse - dt * 1.7f)
        // the captions on the glass age wherever they were raised — the title's and the arena's
        pilotAge += dt; protocolAge += dt
        // The sight's own reactions. The kick is a spring — it lands hard and is gone inside a
        // quarter of a second — and the crush judder is held up by the hold itself (see
        // updateCrush), so what decays here is only the tail after the legs open.
        sightKick *= exp(-dt / 0.13f)
        if (abs(sightKick) < 0.01f) sightKick = 0f
        crushShake *= exp(-dt / 0.18f)
        if (crushShake < 0.005f) crushShake = 0f
        staticT = max(0f, staticT - dt)
        if (impacts.isNotEmpty()) {
            val ii = impacts.iterator()
            while (ii.hasNext()) { val im = ii.next(); im.age += dt; if (im.age > Impact.LIFE) ii.remove() }
        }
        runCues(dt)
        // Derez runs outside the state machine: a machine that broke apart a moment before the wave
        // cleared, or the tank's own hull leaving the seat, has to finish falling wherever it is.
        if (state != State.TITLE) updateDerez(dt)
        when (state) {
            State.TITLE -> updateAttract(dt)
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

    /**
     * The attract loop runs itself; this is only the plumbing round it. The loop owns its own
     * world and its own clock, so all the game does is hand it time, MAKE ITS NOISES — the sound
     * belongs to the frame it happens on, not to a cue that guessed when the shell would land —
     * and re-arm the narration when it comes round again.
     */
    private fun updateAttract(dt: Float) {
        val a = attract ?: return
        a.update(dt)
        if (a.events.isNotEmpty()) {
            for (e in a.events) host.sfx(e[0].toInt(), e[1], e[2])
            a.events.clear()
        }
        loreAge += dt
        if (a.loops != attractLoops) { attractLoops = a.loops; armAttract() }
    }

    private fun updatePlay(dt: Float) {
        elapsed += dt
        fireCd = max(0f, fireCd - dt)
        invuln = max(0f, invuln - dt)
        bumpCd = max(0f, bumpCd - dt)
        bitHint = max(0f, bitHint - dt)
        cardT = max(0f, cardT - dt)
        // THE POOL STANDS UP — see [nextWave]. Half of wave one is down, the loop has been taught,
        // and the column comes up at the far end of the maze with the sound it makes when it fills.
        //
        // OR THE CLOCK GETS THERE FIRST, and that branch matters more than the kill one. Wave one
        // is two machines, so "half the wave" is a single kill — and measured on the glasses the
        // second kill followed the first by under a second, which made the reveal a blink. The
        // player who most needs to be shown the energy economy is the one who is NOT killing
        // things, so twenty-five seconds into a first wave the pool stands up regardless.
        if (poolPending && waveTotal > 0 && (kills * 2 >= waveTotal || stateT > 25f)) {
            poolPending = false; poolActive = true; poolLevel = 1f; poolVis = 0f; poolT = 0f
            host.sfx(com.x3paranoids.audio.Sfx.POOL_SIP, 1.35f, 0.5f)
            host.say("energy_pool")
            android.util.Log.i("X3Paranoids", "POOL revealed on wave 1 at (%.1f,%.1f)".format(poolX, poolZ))
        }
        // THE HELD PAD, ONE FRAME'S WORTH. Along [yaw] — head plus hull — because that is the one
        // true heading in this game: where a dash drives, where the cannon points and where you are
        // looking are the same ray, and a cruise that ran down a second, different forward would be
        // a lie the minimap could not draw. Look off-axis while cruising and you lean on the
        // corridor wall, which the slide below turns into a graze rather than a stop.
        // HELD. Between a Recognizer's legs the hull goes nowhere: no thrust, no coasting, and a
        // pad still down is dropped so that letting go and pressing again after the release is a
        // fresh, deliberate drive rather than a stale one resuming.
        if (caught) { driveEnd("caught"); vx = 0f; vz = 0f }
        if (driveDir != 0) {
            driveT += dt
            val a = DRIVE_ACCEL * (DRIVE_FLOOR + (1f - DRIVE_FLOOR) * min(1f, driveT / DRIVE_RAMP)) * driveDir
            vx += sin(yaw) * a * dt; vz += -cos(yaw) * a * dt
            driveLogT += dt
            if (driveLogT >= 0.25f) {
                driveLogT = 0f
                android.util.Log.i("X3Paranoids", "DRIVE hold dir=%s t=%.2f p=(%.2f,%.2f) v=%.2f".format(
                    if (driveDir > 0) "FWD" else "REV", driveT, px, pz, hypot(vx, vz)))
            }
        }
        // hull physics: impulses decay, the maze walls slide
        val damp = exp(-dt / DRIVE_DAMP)
        vx *= damp; vz *= damp
        var speed = hypot(vx, vz)
        // The clamp used to live only in [impulse]; sustained thrust needs it every frame. It never
        // actually bites during a cruise — DRIVE_ACCEL asymptotes to MAX_SPEED from below — so it
        // is the ram shove and the dash-onto-a-cruise that it is here for.
        if (speed > MAX_SPEED) { vx *= MAX_SPEED / speed; vz *= MAX_SPEED / speed; speed = MAX_SPEED }
        if (speed > 0.02f) {
            val wantX = vx * dt; val wantZ = vz * dt
            val bumped = maze.move(px, pz, wantX, wantZ, PLAYER_R, tmp)
            val gotX = tmp[0] - px; val gotZ = tmp[1] - pz
            px = tmp[0]; pz = tmp[1]
            if (bumped) {
                // PRESS AND SLIDE. [Maze.move] resolves x and z separately, so the axis that was
                // stopped is the one whose travel came up short; kill only THAT one and the
                // component running along the wall survives. The old blanket 0.35 scrub took the
                // sliding component with it, which was survivable when a bump could only ever
                // happen once per gesture and is not now: a held drive re-presses every frame, so
                // scrubbing both axes turned leaning on a wall into a stutter that also refused to
                // let you slide off it.
                if (abs(gotX) < abs(wantX) - 1e-4f) vx = 0f
                if (abs(gotZ) < abs(wantZ) - 1e-4f) vz = 0f
                if (speed > 3f && bumpCd <= 0f) {
                    bumpCd = BUMP_CD
                    host.sfx(com.x3paranoids.audio.Sfx.BUMP, 0.9f + rng.nextFloat() * 0.2f, min(1f, speed / 9f))
                }
            }
        }
        updateWorld(dt, true)
        updatePool(dt)
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
                bitHint = BIT_HINT_T
                pilot("hero_bit_near", cd = 35f, chance = 0.7f)
            }
            // AND WHEN THE CHIRPS GET EXCITED. The objective band belongs to the wave; the Bit
            // borrows it for a few seconds at the two moments it has genuinely announced itself —
            // this is the one that needs no line of sight, so a Bit chirping hard round a corner
            // still gets its name on the glass.
            if (bitHint <= 0f && proximity(bd) > BIT_EXCITED) bitHint = BIT_HINT_T
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

    /**
     * THE POOL, AND DRINKING FROM IT.
     *
     * Presence is the whole mechanic: be inside [POOL_R] of the centre and the draw fills over
     * [POOL_DRAW_T]; be outside it and the draw bleeds back. There is no "press to collect" because
     * there is no button free and, more to the point, because a pool you have to STAY in is the
     * only version of this that costs anything — the arena's machines do not stop while you drink.
     *
     * It is forgiving in exactly one direction. Bleeding back at [POOL_DRAW_DECAY] rather than
     * resetting means a ram that shoves you off the rim, or a wall you clipped on the way in, costs
     * you a fraction of a second rather than the whole approach — while actually turning and
     * leaving still throws the draw away. And the tank coasts: [MAX_SPEED] carries you across a 4.4
     * unit pool in half a second, so a draw genuinely has to be committed to, not driven through.
     */
    private fun updatePool(dt: Float) {
        poolFullHint = false
        if (!poolActive) { poolDraw = max(0f, poolDraw - dt * 3f); return }
        poolT += dt
        val seen = maze.lineOfSight(poolX, poolZ, px, pz)
        val step = dt * VIS_RATE
        poolVis = if (seen) min(1f, poolVis + step) else max(0f, poolVis - step)
        poolSipCd = max(0f, poolSipCd - dt)
        // THE REFILL, on its own clock — through a fight, through a wave clear, whether or not
        // anyone is watching. The moment it stands again is worth one quiet blip: a player
        // fighting at the far end of the maze learns the retreat is open without looking at the
        // plate. Quiet, and only ever while the tank is in play; the pool is seen and not heard.
        if (poolLevel < 1f) {
            poolLevel = min(1f, poolLevel + dt / POOL_REFILL_T)
            if (poolLevel >= 1f && state == State.PLAY) host.sfx(com.x3paranoids.audio.Sfx.POOL_SIP, 1.45f, 0.30f)
        }
        val inPool = hypot(px - poolX, pz - poolZ) < POOL_R
        val ready = poolLevel >= 1f
        if (inPool && ready && shield >= SHIELD_MAX) poolFullHint = true
        if (inPool && ready && shield < SHIELD_MAX && state == State.PLAY) {
            poolDraw = min(1f, poolDraw + dt / POOL_DRAW_T)
            // one climbing blip per POOL_SIP_T of dwell — it stops dead when you step out, which a
            // single long clip started on entry could not do
            if (poolSipCd <= 0f) {
                poolSipCd = POOL_SIP_T
                host.sfx(com.x3paranoids.audio.Sfx.POOL_SIP, 0.85f + 0.55f * poolDraw, 0.55f)
            }
            if (poolDraw >= 1f) {
                // RESTORED. The shell comes back to full, whatever it had; the pool is spent and
                // folds away, and starts the long climb back. The place stays.
                val topUp = shield > 0
                poolDraw = 0f; poolCollapse = 1f; poolLevel = 0f
                burst(poolX, 1.2f, poolZ, 26, 0.55f, 0.95f, 1f)
                onShieldUp(topUp)
                android.util.Log.i("X3Paranoids", "POOL drawn: shield=%d (topUp=%b) refill in %.0fs".format(shield, topUp, POOL_REFILL_T))
            }
        } else {
            poolDraw = max(0f, poolDraw - dt * POOL_DRAW_DECAY / POOL_DRAW_T)
        }
    }

    /** Enemies, shots and sparks — also runs (frozen player) during wave-clear and death. */
    private fun updateWorld(dt: Float, hostile: Boolean) {
        val speedBase = (3.0f + 0.25f * wave + speedBonus).coerceAtMost(6.5f)
        /** The tank is wearing the Protocol's energy — see [PRESS_STANDOFF]. They come and take it back. */
        val pressed = hostile && shield > 0 && state == State.PLAY
        var nearest = 999f
        lockedOn = false; tracking = false
        val it = recognizers.iterator()
        while (it.hasNext()) {
            val r = it.next()
            if (r.hp <= 0) { it.remove(); continue }
            r.hitFlash = max(0f, r.hitFlash - dt * 6f)
            r.crushCd = max(0f, r.crushCd - dt)
            r.stagger = max(0f, r.stagger - dt)
            val bobY = 1.6f + 0.3f * sin(time * 2.1f + r.phase)
            val ddx = px - r.x; val ddz = pz - r.z
            val d = hypot(ddx, ddz)
            nearest = min(nearest, d)
            // CAN THE PERISCOPE SEE IT? The renderer draws nothing it cannot, so this is the whole of
            // the wall occlusion for entities. Three samples across the machine's own width — axle
            // and both ends of the cross-bar in its current heading — because it is 3.7 units wide
            // and a test on the axle alone would blink the thing out while a third of it is still
            // round the corner in plain sight. The local +x axis maps to world (cos yaw, sin yaw)
            // — RecognizerModel.toWorld, the one transform — so the samples sit on the drawn bar.
            val ec = cos(r.yaw) * Recognizer.HALF_W; val es = sin(r.yaw) * Recognizer.HALF_W
            val seen = maze.lineOfSight(r.x, r.z, px, pz) ||
                maze.lineOfSight(r.x + ec, r.z + es, px, pz) ||
                maze.lineOfSight(r.x - ec, r.z - es, px, pz)
            val visStep = dt * VIS_RATE
            r.vis = if (seen) min(1f, r.vis + visStep) else max(0f, r.vis - visStep)
            val los = d < 34f && maze.lineOfSight(r.x, r.z, px, pz)
            if (los) r.seenT = time
            // SEEING YOU AND HAVING THE SHOT ARE TWO DIFFERENT TESTS. Sight is measured axle to
            // axle, so a Recognizer whose body is still mostly behind a corner has a centre that
            // can already see round it — and a disc thrown from there leaves the eye inside the
            // wall and reads, fairly, as shooting through it. The shot gate re-runs the same test
            // with every wall grown by [FIRE_PAD], so it must be genuinely clear of the corner
            // before it will take the shot, while it still hunts you the moment it spots you.
            val fireLos = los && maze.lineOfSight(r.x, r.z, px, pz, FIRE_PAD)
            // The plate's spur means "this one has the shot line" — it is bright once it is also
            // FACING you (see below), dim while it is still bringing its cab round. Stepping
            // behind a wall must visibly switch it off.
            r.hasLos = hostile && fireLos
            val chasing = hostile && (los || time - r.seenT < 5f)
            r.hunting = chasing
            r.alert += ((if (chasing) 1f else 0f) - r.alert) * (1f - exp(-dt / 0.45f))
            // THE CRUSH OWNS THE MACHINE while it runs: no hunting, no patrol, no throwing. It has
            // its own clock and its own outcome — see [updateCrush].
            if (r.crush != Recognizer.CRUSH_NONE) {
                r.facing = false; r.aimErr = 0f
                r.lock = max(0f, r.lock - dt * 4f)
                updateCrush(r, dt)
                continue
            }
            // Off the clamp the hover eases back to its bob (a thrown machine comes down out of
            // the air on this), and any fold left in the legs relaxes out.
            r.y += (bobY - r.y) * (1f - exp(-dt / 0.28f))
            if (r.fold != 0f) { r.fold *= exp(-dt / 0.22f); if (abs(r.fold) < 0.005f) r.fold = 0f }
            var mx = 0f; var mz = 0f
            var canFire = false
            if (chasing && los) {
                // stand off at ~6 u — or close to four and strip the shell, if there is one. A
                // machine REELING from a release does neither: it drifts back and wanders.
                val reeling = r.stagger > 0f
                // THE CHARGE — see [CHARGE_RANGE]. Decided on a clock, not a frame, so it is a
                // choice the machine visibly makes and holds.
                r.charge = max(0f, r.charge - dt); r.chargeCd = max(0f, r.chargeCd - dt)
                if (hostile && !reeling && r.charge <= 0f && r.chargeCd <= 0f && d < CHARGE_RANGE && fireLos && r.crushCd <= 0f) {
                    r.chargeCd = CHARGE_CD
                    if (rng.nextFloat() < (if (pressed) PRESS_CHARGE_CHANCE else CHARGE_CHANCE)) {
                        r.charge = CHARGE_T
                        android.util.Log.i("X3Paranoids", "CHARGE d=%.1f pressed=%b".format(d, pressed))
                    }
                }
                r.noLineT = if (fireLos) 0f else r.noLineT + dt
                val closing = r.noLineT > 0.8f && !reeling
                val charging = r.charge > 0f || closing
                val want = if (pressed) PRESS_STANDOFF else 6f
                val towards = if (reeling) -0.45f else if (charging) 1f else if (d > want + 1f) 1f else if (d < want - 1.5f) -0.6f else 0f
                val nx = ddx / max(d, 0.01f); val nz = ddz / max(d, 0.01f)
                val side = if (reeling || charging) 0f else sin(time * 0.7f + r.phase)
                mx = nx * towards + (-nz) * side * 0.5f
                mz = nz * towards + nx * side * 0.5f
                // WEDGED ON A CORNER — see [Recognizer.stuckT]: walk the maze toward you instead
                r.reroute = max(0f, r.reroute - dt)
                if (r.reroute > 0f && towards > 0f) {
                    val step = maze.stepToward(maze.colOf(r.x), maze.rowOf(r.z), maze.colOf(px), maze.rowOf(pz))
                    if (step != null) {
                        val dx = maze.cellX(step[0]) - r.x; val dz = maze.cellZ(step[1]) - r.z
                        val l = hypot(dx, dz).coerceAtLeast(0.01f)
                        mx = dx / l; mz = dz / l
                    }
                }
                // THE TURN TO FACE — see [FACING BEFORE FIRING]. The bearing is a target the cab
                // swings toward at a rate, not a value it is set to; a reeling machine's target
                // wanders off the tank and it turns at less than half speed.
                var target = atan2(ddx, -ddz)
                if (reeling) target += 0.9f * sin(time * 5.3f + r.reel) * (r.stagger / STAGGER_T)
                r.aimErr = turnToward(r, target, if (reeling) TURN_HUNT * 0.4f else TURN_HUNT, dt)
                r.facing = r.aimErr < FIRE_ARC && fireLos && !reeling
                if (hostile) {
                    r.fireCd -= dt
                    canFire = r.facing && d < FIRE_RANGE
                    if (canFire) {
                        lockedOn = true
                        // THE LOCK is the moment it is looking at you with the line clear — the
                        // sting and WARNING fire on that beat, which is the beat before the throw.
                        if (!r.lockSaid) {
                            r.lockSaid = true; host.sfx(com.x3paranoids.audio.Sfx.LOCK, 1f, 0.7f)
                            if (time - lastKillSay > 3f) host.say("lockon")
                            // the eye finds you, THEN the disc: never both on one frame
                            r.fireCd = max(r.fireCd, dwell)
                            android.util.Log.i("X3Paranoids", "LOCK d=%.1f aimErr=%.1f deg dwell=%.2f".format(d, r.aimErr * 57.2958f, dwell))
                        }
                        if (r.fireCd <= 0f) {
                            // The press multiplies the SETTLED cooldown rather than the raw one, so it
                            // is a real 30% more fire at every wave instead of being swallowed by the
                            // floor once the wave scaling has already reached it.
                            r.fireCd = (2.6f - 0.15f * wave).coerceAtLeast(1.1f) * fireRate *
                                (if (pressed) PRESS_FIRE else 1f)
                            throwDisc(r, d)
                        }
                    } else if (fireLos && d < FIRE_RANGE && !reeling) {
                        // it has the line and is bringing the eye round: the beat to move on
                        tracking = true
                        // AND IT MAKES A SOUND NOW — see [Recognizer.trackSaid]. A quiet rising
                        // servo whine, pitched by how far round the cab still has to come, so the
                        // player HEARS the swing complete and learns to move on a sound rather
                        // than on reading a small amber word in a corner of the sight. Softer and
                        // lower than the LOCK sting it precedes, because it is the warning before
                        // the warning: the beat you can still do something about.
                        if (!r.trackSaid) {
                            r.trackSaid = true
                            val closeness = (1f - (r.aimErr / 1.4f)).coerceIn(0f, 1f)
                            host.sfx(com.x3paranoids.audio.Sfx.TRACKING, 0.78f + 0.34f * closeness,
                                (0.30f + 0.22f * closeness) * (1f - d / (FIRE_RANGE * 1.4f)).coerceIn(0.35f, 1f))
                            android.util.Log.i("X3Paranoids", "TRACK d=%.1f aimErr=%.1f deg".format(d, r.aimErr * 57.2958f))
                        }
                    }
                }
            } else {
                // patrol the corridors by cell; a chaser that lost sight paths to the tank's last cell
                r.aimErr = 0f; r.facing = false
                val c = maze.colOf(r.x); val rr = maze.rowOf(r.z)
                if (chasing) { r.targetC = maze.colOf(px); r.targetR = maze.rowOf(pz) }
                if (r.targetC < 0 || (c == r.targetC && rr == r.targetR)) { r.targetC = rng.nextInt(maze.cols); r.targetR = rng.nextInt(maze.rows) }
                val step = maze.stepToward(c, rr, r.targetC, r.targetR)
                if (step != null) {
                    val tx = maze.cellX(step[0]); val tz = maze.cellZ(step[1])
                    val dx = tx - r.x; val dz = tz - r.z; val l = hypot(dx, dz).coerceAtLeast(0.01f)
                    mx = dx / l; mz = dz / l
                    turnToward(r, atan2(dx, -dz), TURN_PATROL, dt)
                } else { r.targetC = -1 }
            }
            // the lock sting re-arms once the shot line is lost, not merely when the eye drifts
            // off you for a frame — a machine tracking a dodging tank does not sting on every arc
            if (!fireLos) { r.lockSaid = false; r.trackSaid = false }
            r.lock += ((if (canFire) 1f else 0f) - r.lock) * (1f - exp(-dt / (if (canFire) 0.07f else 0.20f)))
            val sp = speedBase * (if (chasing) (if (pressed) PRESS_SPEED else 1.15f) else 0.8f) *
                (if (r.stagger > 0f) 0.7f else if (r.charge > 0f) CHARGE_SPEED else 1f)
            if (mx != 0f || mz != 0f) {
                val ox = r.x; val oz = r.z
                maze.move(r.x, r.z, mx * sp * dt, mz * sp * dt, Recognizer.RADIUS, tmp); r.x = tmp[0]; r.z = tmp[1]
                // did the step go anywhere? A chase pressing on a wall corner accumulates here.
                val wanted = sp * dt * hypot(mx, mz)
                if (chasing && los && r.reroute <= 0f && wanted > 1e-4f && hypot(r.x - ox, r.z - oz) < 0.3f * wanted) {
                    r.stuckT += dt
                    if (r.stuckT > 0.45f) { r.stuckT = 0f; r.reroute = 2f; android.util.Log.i("X3Paranoids", "REROUTE d=%.1f".format(d)) }
                } else r.stuckT = 0f
            }
            // CONTACT. A Recognizer that touches the tank CAPTURES it — see [THE CRUSH] — unless
            // the tank is inside a grace window, another machine already has it, or this one is
            // still recovering from its last capture. Those cases fall back to the old separating
            // shove, so a machine can never stand inside the hull waiting for the grace to lapse.
            val d2 = hypot(px - r.x, pz - r.z)
            if (hostile && d2 < RAM_D && state == State.PLAY) {
                if (!caught && crusher == null && invuln <= 0f && r.crushCd <= 0f && r.stagger <= 0f) beginCrush(r)
                else shove(r)
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
            if (s.dead) { si.remove(); continue }
            s.life -= dt
            val nx = s.x + s.vx * dt; val ny = s.y + s.vy * dt; val nz = s.z + s.vz * dt
            val t = maze.rayHit(s.x, s.z, nx, nz)
            if (s.life <= 0f || t <= 1f || ny < 0f || ny > Maze.WALL_H + 2f) {
                if (t <= 1f) { burst(s.x + (nx - s.x) * t, ny, s.z + (nz - s.z) * t, 6, if (s.friendly) 1f else 1f, if (s.friendly) 0.9f else 0.3f, 0.3f); if (s.friendly) host.sfx(com.x3paranoids.audio.Sfx.RICOCHET, 1f, 0.5f) }
                si.remove(); continue
            }
            s.x = nx; s.y = ny; s.z = nz
            if (s.friendly && cutDisc(s, si)) continue
            if (s.friendly) {
                for (r in recognizers) if (r.hp > 0 && hypot(s.x - r.x, s.z - r.z) < 1.9f && abs(s.y - (r.y + 1.3f)) < 2.2f) {
                    r.hp--; r.hitFlash = 1f
                    if (r.hp <= 0) {
                        kills++
                        score += (100 * wave * scoreMul).toInt()
                        // SHOT WHILE IT HAD YOU. The clamp is broken with the machine: the tank is
                        // let go on this frame, and the derez below carries the fold it died in.
                        if (r.crush != Recognizer.CRUSH_NONE) {
                            android.util.Log.i("X3Paranoids", "CRUSH killed mid-sequence phase=%d fold=%.2f".format(r.crush, r.fold))
                            if (crusher === r) { releasePlayer(); sightKick = 0.7f }
                        }
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
            } else if (hostile && state == State.PLAY) {
                discAtTank(s, si)
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

    // ------------------------------------------------------------------ the Recognizer's craft
    /**
     * Swing the cab toward [target] at up to [rate] radians a second, easing over the last
     * [TURN_EASE] radians so it settles rather than stops. Returns how far off it still is.
     */
    private fun turnToward(r: Recognizer, target: Float, rate: Float, dt: Float): Float {
        var d = target - r.yaw
        while (d > PI.toFloat()) d -= 2f * PI.toFloat()
        while (d < -PI.toFloat()) d += 2f * PI.toFloat()
        val ad = abs(d)
        val step = rate * dt * (ad / TURN_EASE).coerceIn(0.35f, 1f)
        if (ad <= step) { r.yaw = target; return 0f }
        r.yaw += if (d > 0f) step else -step
        while (r.yaw > PI.toFloat()) r.yaw -= 2f * PI.toFloat()
        while (r.yaw < -PI.toFloat()) r.yaw += 2f * PI.toFloat()
        return ad - step
    }

    /**
     * THE DISC LEAVES THE EYE. The origin is the model's own eye point put through the model's
     * own transform, so the thing you watched turn to face you is the thing the disc comes out of
     * — from the cab, four units up, so it comes DOWN at the periscope. The aim carries the wave's
     * spread; the disc's spin phase is randomised so two in the air never turn in step.
     */
    private fun throwDisc(r: Recognizer, d: Float) {
        val spread = (0.10f - 0.008f * wave).coerceAtLeast(0.03f)
        val aimX = px + (rng.nextFloat() - 0.5f) * spread * d
        val aimZ = pz + (rng.nextFloat() - 0.5f) * spread * d
        r.eye(tmp3)
        val ox = tmp3[0]; val oy = tmp3[1]; val oz = tmp3[2]
        val ax = aimX - ox; val ay = EYE_H - 0.15f - oy; val az = aimZ - oz
        val al = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(0.01f)
        shots += Shot(ox, oy, oz, ax / al * BOLT_SPEED, ay / al * BOLT_SPEED, az / al * BOLT_SPEED, false)
            .also { it.spin = rng.nextFloat() * 6.2832f }
        host.sfx(com.x3paranoids.audio.Sfx.ENEMY_FIRE, 0.9f + rng.nextFloat() * 0.2f, (1f - d / 40f).coerceIn(0.3f, 1f))
        android.util.Log.i("X3Paranoids", "THROW d=%.1f aimErr=%.1f deg from eye=(%.1f,%.1f,%.1f)".format(d, r.aimErr * 57.2958f, ox, oy, oz))
    }

    /**
     * A DISC AT THE TANK: it lands, or it goes past. Landing is the old hit box; the shell takes
     * it first, and either way the SIGHT reacts — an [Impact] ring at the point it struck, the
     * brackets punched outward, and for a hull hit a moment of static in the periscope. A disc
     * that comes inside [NEAR_MISS_D] and then starts to recede without landing is a NEAR MISS,
     * called once at its closest point: the whoosh, and a fainter ring out at the edge of the
     * glass on the side it passed. A held tank is not hit by discs — the machine on top of it is
     * in the way, and the crush is already the event.
     */
    private fun discAtTank(s: Shot, si: MutableIterator<Shot>) {
        val dp = hypot(s.x - px, s.z - pz)
        if (!caught && dp < 1.15f && abs(s.y - EYE_H) < 1.6f) {
            si.remove()
            if (invuln > 0f) {
                // inside the grace after a hit: it glances off — a ring, no punch, no damage
                impacts += Impact(s.x, s.y, s.z, Impact.NEAR)
                host.sfx(com.x3paranoids.audio.Sfx.DISC_PASS, 1.2f, 0.4f)
                return
            }
            val shielded = shield > 0
            impacts += Impact(s.x, s.y, s.z, if (shielded) Impact.SHIELD else Impact.HULL)
            if (shielded) {
                sightKick = 0.55f
                host.sfx(com.x3paranoids.audio.Sfx.DISC_HIT, 1.15f, 0.55f)
            } else {
                sightKick = 1f; staticT = 0.32f
                host.sfx(com.x3paranoids.audio.Sfx.DISC_HIT, 0.95f + rng.nextFloat() * 0.1f, 1f)
            }
            damagePlayer(s.x, s.z, s.y)
            return
        }
        if (!s.passed && s.prevD < 900f && dp > s.prevD && s.prevD < NEAR_MISS_D) {
            s.passed = true
            val close = (1f - s.prevD / NEAR_MISS_D).coerceIn(0f, 1f)
            impacts += Impact(s.x, s.y, s.z, Impact.NEAR)
            host.sfx(com.x3paranoids.audio.Sfx.DISC_PASS, 0.9f + 0.3f * close, 0.45f + 0.55f * close)
            android.util.Log.i("X3Paranoids", "NEAR MISS closest=%.2f".format(s.prevD))
        }
        s.prevD = dp
    }

    /**
     * A SHELL MEETS A DISC — the one new verb in this round, and the answer to the complaint the
     * rest of the threat work would otherwise leave standing.
     *
     * WARNING turning the sight red and a chevron on the rim tell you where death is coming from.
     * Without this they leave you with no move that is not "look away from it": the cannon, the
     * periscope and the drive are the same ray, so turning to face a threat is also turning to
     * drive INTO it, and turning away costs you the target, the shot and your only bearing. So
     * facing the threat is now itself the counter — turn onto the chevron, fire, and the disc
     * bursts short of the hull.
     *
     * It is the film's own move, it uses no gesture the game did not already have, and it makes the
     * 0.62 s dwell a decision instead of a countdown. The reward is deliberately small in points:
     * the disc not landing is the reward.
     *
     * Returns true when [s] was spent on a disc, in which case the caller must not process it
     * further. Only the disc is flagged (see [Shot.dead]); the shell is removed by the caller,
     * which owns the iterator.
     */
    private fun cutDisc(s: Shot, si: MutableIterator<Shot>): Boolean {
        // THE SEARCH IS INDEXED, and the removal happens after it, because the caller is already
        // iterating this same list — see [scatter] for what that costs when it goes wrong.
        var hit: Shot? = null
        for (i in shots.indices) {
            val o = shots[i]
            if (o.friendly || o.dead) continue
            val hd = hypot(o.x - s.x, o.z - s.z); val vd = abs(o.y - s.y)
            if (CUT_TRACE && hd < 6f) android.util.Log.i("X3Paranoids", "cut? h=%.2f v=%.2f".format(hd, vd))
            if (hd > DISC_CUT_R || vd > DISC_CUT_Y) continue
            hit = o; break
        }
        val o = hit ?: return false
        o.dead = true
        si.remove()
        score += DISC_CUT_SCORE
        burst(o.x, o.y, o.z, 16, 1f, 0.55f, 0.35f)
        impacts += Impact(o.x, o.y, o.z, Impact.NEAR)
        host.sfx(com.x3paranoids.audio.Sfx.DISC_CUT, 0.95f + rng.nextFloat() * 0.12f)
        pilot("hero_disc_cut", gap = 9f, cd = 30f, chance = 0.35f, patience = 1500L)
        android.util.Log.i("X3Paranoids", "DISC CUT at %.1f units from the hull".format(hypot(o.x - px, o.z - pz)))
        return true
    }

    /** The contact that cannot become a capture: separate the pair, Recognizer first, tank for the remainder. */
    private fun shove(r: Recognizer) {
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

    // ------------------------------------------------------------------ [THE CRUSH]
    /** It has you. The lunge begins: the hull is held, the servos spin up, the machine says so. */
    private fun beginCrush(r: Recognizer) {
        r.crush = Recognizer.CRUSH_LUNGE; r.crushT = 0f; r.crushY0 = r.y; r.fold = 0f; r.thrown = false
        r.charge = 0f
        r.reel = rng.nextFloat() * 6.2832f
        // the line it came down: it lands CRUSH_STAND short of the eye along this, and it is fixed
        // now because the hull cannot move for the rest of the sequence
        var ux = px - r.x; var uz = pz - r.z
        val ul = hypot(ux, uz)
        if (ul < 0.05f) { ux = -sin(r.yaw); uz = cos(r.yaw) } else { ux /= ul; uz /= ul }
        r.crushUx = ux; r.crushUz = uz
        caught = true; crusher = r
        driveEnd("caught"); vx = 0f; vz = 0f
        crushShake = max(crushShake, 0.18f); sightKick = -0.3f
        host.sfx(com.x3paranoids.audio.Sfx.CRUSH_ARM)
        host.say("captured", urgent = true)
        android.util.Log.i("X3Paranoids", "CRUSH begin r=(%.1f,%.1f) p=(%.1f,%.1f) shield=%d lives=%d".format(r.x, r.z, px, pz, shield, lives))
    }

    /** Slide the gantry to its landing point over the hull's nose — see [CRUSH_STAND] — through the walls' own collision. */
    private fun converge(r: Recognizer, dt: Float) {
        val tx = px - r.crushUx * CRUSH_STAND; val tz = pz - r.crushUz * CRUSH_STAND
        val dx = tx - r.x; val dz = tz - r.z
        val l = hypot(dx, dz)
        if (l < 0.02f) return
        val stepL = min(l, CRUSH_CLOSE_SPEED * dt)
        maze.move(r.x, r.z, dx / l * stepL, dz / l * stepL, Recognizer.RADIUS, tmp)
        r.x = tmp[0]; r.z = tmp[1]
    }

    /**
     * The capture's clock — the beats are laid out under [THE CRUSH]. Runs in every state: a
     * machine holding a dying tank keeps holding it, and one mid-release when the wave clears
     * finishes opening. Only the OUTCOME needs the arena live, and it is applied at the landing.
     */
    private fun updateCrush(r: Recognizer, dt: Float) {
        r.crushT += dt
        when (r.crush) {
            Recognizer.CRUSH_LUNGE -> {
                val u = (r.crushT / CRUSH_LUNGE_T).coerceIn(0f, 1f)
                val e = 1f - (1f - u) * (1f - u)
                r.y = r.crushY0 + (CRUSH_RISE_Y - r.crushY0) * e
                r.fold = -0.18f * e
                converge(r, dt)
                crushShake = max(crushShake, 0.10f)
                if (u >= 1f) { r.crush = Recognizer.CRUSH_DROP; r.crushT = 0f }
            }
            Recognizer.CRUSH_DROP -> {
                val u = (r.crushT / CRUSH_DROP_T).coerceIn(0f, 1f)
                r.y = CRUSH_RISE_Y + (CRUSH_LAND_Y - CRUSH_RISE_Y) * u * u
                r.fold = -0.18f + 1.18f * u * u * u
                converge(r, dt)
                if (u >= 1f) { r.y = CRUSH_LAND_Y; r.fold = 1f; land(r) }
            }
            Recognizer.CRUSH_HOLD -> {
                r.fold = 1f
                r.y = CRUSH_LAND_Y + 0.03f * sin(r.crushT * 61f)
                crushShake = max(crushShake, if (state == State.DYING) 0.08f else 0.24f)
                // a shell under the clamp STRAINS: it flickers hard, lit from above, for the hold
                if (shield > 0) { shieldFlash = max(shieldFlash, 0.55f + 0.45f * abs(sin(r.crushT * 31f))); shieldHitX = 0f; shieldHitY = 1f; shieldHitZ = 0f }
                if (state != State.DYING && r.crushT >= r.holdT) open(r)
            }
            Recognizer.CRUSH_RELEASE -> {
                val u = (r.crushT / CRUSH_RELEASE_T).coerceIn(0f, 1f)
                val eo = 1f - (1f - u) * (1f - u)
                if (r.thrown) {
                    // flung: the legs snap open past straight, it is thrown up and away
                    r.fold = 1f - 1.35f * min(1f, u * 1.7f)
                    r.y = CRUSH_LAND_Y + (2.9f - CRUSH_LAND_Y) * eo
                } else {
                    // let go: the legs open, then it lifts off
                    r.fold = 1f - eo
                    r.y = CRUSH_LAND_Y + (1.6f - CRUSH_LAND_Y) * u * u
                }
                // and backs away from the hull, wall-clipped, over the whole release
                val away = if (r.thrown) CRUSH_THROW else CRUSH_BACK_OFF
                var bx = r.x - px; var bz = r.z - pz
                val bl = hypot(bx, bz)
                if (bl < 0.05f) { bx = -sin(r.yaw); bz = cos(r.yaw) } else { bx /= bl; bz /= bl }
                maze.move(r.x, r.z, bx * away * dt / CRUSH_RELEASE_T, bz * away * dt / CRUSH_RELEASE_T, Recognizer.RADIUS, tmp)
                r.x = tmp[0]; r.z = tmp[1]
                if (u >= 1f) {
                    r.crush = Recognizer.CRUSH_NONE; r.crushT = 0f
                    r.stagger = STAGGER_T; r.crushCd = CRUSH_CD
                    if (!r.thrown) r.fold = 0f
                }
            }
        }
    }

    /**
     * THE LANDING BEAT. The slam, the sight kicked inward, the judder — and the outcome. With a
     * shell up the legs close on the SHELL: it flares under them and the strain begins, and what
     * the strain ends in is decided at [open]. Bare, the hull is crushed here and now: a life, or
     * the derez, with the clamp held through it.
     */
    private fun land(r: Recognizer) {
        r.crush = Recognizer.CRUSH_HOLD; r.crushT = 0f
        crushShake = 1f; sightKick = -1f
        host.sfx(com.x3paranoids.audio.Sfx.CRUSH_SLAM)
        cue(0.10f) { if (r.crush == Recognizer.CRUSH_HOLD) host.sfx(com.x3paranoids.audio.Sfx.CRUSH_GRIND, 1f, 0.9f) }
        if (shield > 0) {
            r.holdT = CRUSH_HOLD_SHELL_T
            shieldFlash = 1f; shieldHitX = 0f; shieldHitY = 1f; shieldHitZ = 0f
            host.sfx(com.x3paranoids.audio.Sfx.SHIELD_HIT, 0.72f, 0.9f)
            android.util.Log.i("X3Paranoids", "CRUSH landed on shell=%d".format(shield))
        } else {
            r.holdT = CRUSH_HOLD_T
            staticT = 0.30f
            burst(px, EYE_H + 0.4f, pz, 10, 1f, 0.4f, 0.3f)
            android.util.Log.i("X3Paranoids", "CRUSH landed on hull lives=%d".format(lives))
            damagePlayer(r.x, r.z, r.y + 2.2f, force = true)
            // THE GRACE HAS TO OUTLAST THE CLAMP. A capture spends the first 1.2 s of the 2.6 s
            // untouchable window HELD — the hull cannot move, so that part of it is not mercy, it
            // is a countdown running while you sit still. The window is extended by exactly the
            // hold and the release, so what the player actually gets is the full 2.6 s from the
            // moment the legs let go — which, with [scatter] landing on the same beat, is a
            // genuine escape rather than a longer look at the thing that is about to finish you.
            if (state == State.PLAY) invuln = max(invuln, r.holdT + CRUSH_RELEASE_T * 0.5f + 2.6f)
        }
    }

    /**
     * THE CLAMP OPENS. If it was straining on a shell, the shell DISCHARGES: it spends
     * [CRUSH_CHARGES] and throws the machine off, open and reeling; if that was the last of it,
     * the shell derezzes outward past the periscope on the same frame. Otherwise the machine
     * simply lets go and lifts off. Either way the tank is released now — as the legs start to
     * open, not when they finish — so the player has the whole release to get clear on.
     */
    private fun open(r: Recognizer) {
        r.crush = Recognizer.CRUSH_RELEASE; r.crushT = 0f
        if (shield > 0) {
            r.thrown = true
            val taken = min(shield, CRUSH_CHARGES)
            shield -= taken
            shieldFlash = 1f; shieldHitX = 0f; shieldHitY = 1f; shieldHitZ = 0f
            invuln = SHIELD_IFRAME + CRUSH_GRACE
            burst(px, EYE_H + 0.6f, pz, 18, 0.55f, 0.95f, 1f)
            host.sfx(com.x3paranoids.audio.Sfx.CRUSH_OPEN, 1.15f, 1f)
            if (shield > 0) onShieldHit() else onShieldDown()
            sightKick = 0.8f
            android.util.Log.i("X3Paranoids", "CRUSH shell discharged: took %d, shield=%d".format(taken, shield))
        } else {
            r.thrown = false
            host.sfx(com.x3paranoids.audio.Sfx.CRUSH_OPEN)
            sightKick = 0.45f
            android.util.Log.i("X3Paranoids", "CRUSH released lives=%d".format(lives))
        }
        releasePlayer()
        // A life lost under the clamp scatters the arena HERE, on the beat the hull can move again.
        if (pendingScatter && state == State.PLAY) scatter()
    }

    /** The hull is free. Idempotent; the crusher's own clock carries on without it. */
    private fun releasePlayer() {
        if (!caught && crusher == null) return
        caught = false; crusher = null
    }

    // ------------------------------------------------------------------ derez
    /**
     * Overload, then fracture, then the grid. The sequence is described in [Derez] and its physics
     * lives in [updateDerezList], out at file scope — because the ATTRACT LOOP kills a Recognizer
     * too, and the death it advertises has to be the death you get.
     */
    private fun updateDerez(dt: Float) = updateDerezList(derezzes, maze, dt, tmp, FRAG_G, rnd)

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
        derezzes += Derez(r.x, r.y, r.z, r.yaw, 1f, r.alert, false, fold = r.fold)
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
    /**
     * 0 across the arena … 1 on top of the Bit — THE NUMBER THE WHOLE PROXIMITY CUE RIDES ON, and
     * the one that has to be derived from the maze rather than guessed.
     *
     * It used to be `1 - (d - 3) / 28`, which reaches zero at 31 units. That would be right for a
     * small arena; this one is [Maze.CELL] × 8 = 72 units on a side and about 102 across the
     * diagonal, and [nextWave] deliberately hides the Bit in a cell at least three BFS steps away.
     * Measured on the glasses, the actual range to the Bit ran 35–61 units for an entire wave and
     * `near` was PINNED AT 0.00 for every single chirp — so the interval sat at a flat ~4.8 s no
     * matter where the player went. The Bit was not getting more excited as you closed in; it was
     * a metronome, and the one objective the HUD states out loud ("FIND THE BIT") had no cue at all.
     *
     * So the far end is taken from the maze itself and the near end is where the Bit is effectively
     * in your lap. Anything that resizes the maze re-tunes this for free.
     */
    private fun proximity(d: Float): Float {
        val far = 0.75f * maze.cols * Maze.CELL      // 54 units on the standard 8×8 board
        return ((far - d) / (far - BIT_CLOSE)).coerceIn(0f, 1f)
    }

    /**
     * THE BIT GOES QUIET WHEN YOU ARE SEEN.
     *
     * One behaviour, fifteen lines, against a test the game already runs every frame: while any
     * Recognizer holds the shot line on the tank, the Bit stops chirping — and the moment the line
     * breaks it comes straight back in, twice, quickly.
     *
     * It is characterisation and a mechanic in one stroke, which is the only kind of lore worth
     * adding to a game like this. As character it is the plainest thing the Bit could possibly do:
     * it is frightened of them, and the one thing in the arena that is on your side goes silent
     * when the arena is looking at you. As a mechanic it is a THREAT READOUT ON THE ONLY CHANNEL
     * THAT REACHES A HEADSET PLAYER REGARDLESS OF WHERE THEY ARE LOOKING — the chatter you have
     * been half-hearing for a minute stops, which is louder than any sound could be — and, better,
     * it makes BREAKING LINE OF SIGHT AUDIBLE. Duck behind a wall and the Bit starts again. That is
     * exactly the feedback the escape needs, delivered by a character rather than by a HUD element.
     */
    private fun bitSeesDanger(): Boolean {
        for (r in recognizers) if (r.hp > 0 && r.hasLos) return true
        return false
    }

    private fun updateBitVoice(dt: Float, d: Float) {
        bitChirpCd -= dt
        bitNoCd -= dt
        val hunted = bitSeesDanger()
        if (hunted != bitHushed) {
            bitHushed = hunted
            // coming out of hiding: it does not wait out the interval, it says so at once
            if (!hunted) { bitChirpCd = min(bitChirpCd, 0.18f); bitRelief = 2 }
        }
        if (hunted) return
        if (bitChirpCd <= 0f) {
            val near = proximity(d)
            bitChirpCd = 4.6f - 3.85f * near + rng.nextFloat() * 0.6f
            // the two relieved chirps after a line breaks come close together — that pattern IS the
            // signal that you are out of sight, and it has to be distinguishable from the metronome
            if (bitRelief > 0) { bitRelief--; bitChirpCd = 0.3f + rng.nextFloat() * 0.15f }
            if (!host.voiceBusy()) {
                if (near > BIT_EXCITED && rng.nextFloat() < 0.32f) host.sfx(com.x3paranoids.audio.Sfx.BIT_YES, 0.95f + 0.15f * near, 0.22f + 0.26f * near)
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
    /**
     * THE ONE IMPERATIVE SENTENCE ON THE GLASS, and it now names the thing that ends the wave.
     *
     * It used to read "FIND THE BIT" for the whole of every wave in which the Bit was still out
     * there — which is every wave, until you collect it. The wave-clear condition is
     * `recognizersLeft == 0`; the Bit is optional, worth points and a life. So the single line of
     * instruction the game gives a first-time player sent them hunting a hidden diamond across an
     * 8×8 maze while machines they had not been taught to fight hunted them — and then the game
     * killed them for it, in twenty seconds, over and over.
     *
     * The band states the wave's actual condition. The Bit gets it only in the moments the Bit has
     * ANNOUNCED ITSELF — see [bitHint]: when its chirps first tighten up close, and when the pilot
     * calls it. That keeps the objective honest and keeps the Bit a discovery rather than a chore.
     */
    fun objectiveText(): String = when {
        bitHint > 0f && bitActive -> "FIND THE BIT"
        recognizersLeft == 1 -> "ONE RECOGNIZER LEFT"
        recognizersLeft > 0 -> "DESTROY $recognizersLeft RECOGNIZERS"
        else -> "WAVE $wave CLEAR"
    }
    /** True while the objective band is the Bit's — the renderer paints that line in the Bit's cyan. */
    fun objectiveIsBit(): Boolean = bitHint > 0f && bitActive
    fun waveProgress(): Float = if (waveTotal == 0) 0f else kills.toFloat() / waveTotal
    /**
     * THE ARENA'S COLOUR, AND WHO OWNS IT. The grid drifts from phosphor green toward a colder
     * white-cyan as the waves climb — and that drift is the MONOPOLY CONTROL PROTOCOL taking an
     * interest, which is a thing the game now says out loud on the waves the tint measurably moves
     * (see [maybeProtocol]). The world's most visible change has a cause.
     *
     * THE DRIFT IS SHORTER THAN IT WAS. Measured on the glasses, wave 6 in tight quarters averaged
     * RGB (112,141,127) across its bright strokes — which is grey, not phosphor — because the tint
     * and the near-field beam gain were pulling the same way. The endpoint comes back from
     * (0.80, 1, 0.90) to (0.62, 1, 0.78): still unmistakably colder than wave one, still reading as
     * the world going wrong, but a green-white rather than a white. The other half of that fix is
     * in the renderer, where the near lift now raises brightness without dragging hue (see
     * GLRenderer.nearHue).
     */
    fun wallTint(): FloatArray {
        val k = ((wave - 2) / 4f).coerceIn(0f, 1f)
        return floatArrayOf(0.25f + 0.37f * k, 1f, 0.45f + 0.33f * k)
    }
}
