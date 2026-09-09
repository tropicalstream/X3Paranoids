package com.x3paranoids.engine

import kotlin.math.cos
import kotlin.math.sin

/**
 * THE RECOGNIZER'S SILHOUETTE, WRITTEN DOWN ONCE — 60 line segments in the machine's own frame,
 * at scale 1, origin on the axle, +y up, and the EYE SLIT FACES LOCAL -z. Local -z is the machine's
 * FORWARD, and it maps to the same world heading the engine steers by (see [toWorld]).
 *
 * It exists because three different things need to know what a Recognizer is MADE of, and they
 * must never disagree:
 *
 *  - [com.x3paranoids.gl.GLRenderer.buildRecognizer] draws it.
 *  - The DEREZ (see [Derez]) takes it apart. A program does not explode, it loses cohesion and
 *    comes apart into the geometry it was made of — so the fragments ARE these segments, detached
 *    where they stood, and for the first beat of the death the shape is still legible enough to
 *    read WHAT died.
 *  - The CRUSH folds it. A Recognizer captures the way it does in the film: it comes down over its
 *    target and its two legs swing INWARD beneath the bar until the feet meet, closing on whatever
 *    is between them. [segment] hands out every segment already posed for a given [fold], so the
 *    renderer, the derez and the collider all see the same legs in the same place.
 *
 * The same lesson as [Recognizer.RADIUS]: the drawing and the thing that reasons about the drawing
 * were once written out separately, and drifted. There is no longer a second copy to drift from.
 *
 * LOCAL → WORLD, and the mirror that used to be in it. A heading `yaw` in this game means the unit
 * vector (sin yaw, −cos yaw) — that is the periscope's forward, the direction a dash drives, and
 * the value the AI writes into [Recognizer.yaw] with atan2(dx, −dz). The renderer and the derez
 * rotated the model by the STANDARD y-rotation instead, which sends local −z to (−sin yaw, −cos yaw):
 * the x-mirror of the intended heading. Nobody noticed for as long as the machine was a symmetric
 * gantry that snapped to face you the frame it saw you — at 0 and 180 degrees the two agree, and
 * at every other heading the bar was merely drawn skewed. The moment the Recognizer has to TURN
 * TOWARD you visibly and fire from its eye, the mirror is fatal: a machine at 45 degrees to your
 * right would draw its eye looking 90 degrees away from where the game says it is aiming. So the
 * transform is written down here, once, the right way round, and every reader uses it:
 *      wx = x + ( ox·cos yaw − oz·sin yaw)·sc
 *      wy = y +   oy·sc
 *      wz = z + ( ox·sin yaw + oz·cos yaw)·sc
 * Local +x — the bar — is the machine's RIGHT, (cos yaw, sin yaw), which is also the periscope's.
 */
object RecognizerModel {

    /** Structure: the bar, the cab, the legs, the feet. Drawn in the machine's mood colour. */
    const val BODY = 0
    /** The three short ribs on the cross-bar — dimmer, they are detail rather than frame. */
    const val RIB = 1
    /** The eye slit. Always red, and it is the last thing to go out. */
    const val EYE = 2

    /** Six floats per segment: x0,y0,z0, x1,y1,z1 — UNFOLDED. Use [segment] for a posed one. */
    val seg: FloatArray
    /** What each segment IS, so a fragment can keep its own colour on the way down. */
    val kind: IntArray
    /** Which leg a segment hangs from: −1 the left (x < 0), +1 the right, 0 for the bar and cab. */
    val leg: IntArray
    /**
     * True for the four strokes of a FOOT PLATE. They flare as the leg swings — see [FOOT_SPREAD] —
     * which is the detail that makes the clamp read as a mechanism rather than as two sticks
     * pivoting. The owner asked for it by name.
     */
    val foot: BooleanArray
    val count: Int

    /**
     * THE HINGE. Each leg pivots where it meets the underside of the bar — the bar's box spans
     * y 1.7 … 2.3 and the leg's spans 0 … 1.7, so 1.7 is the seam — at the leg's own x. A fold of
     * 1 swings each leg [FOLD_ANGLE] inward about the z axis, which brings the two leg centrelines
     * to within a hand's width of each other at the bottom and lays the feet's inner corners across
     * one another: the clamp is CLOSED, and what was standing between the legs is not any more.
     * At 47 degrees the outer flares come within about 0.3 units of touching, so the whole clamp
     * is visibly shut without the two feet drawing through each other into a knot of lines.
     *
     * A fold below zero splays the legs OUTWARD — the anticipation beat before the drop, the
     * gantry opening its hands.
     */
    const val LEG_PIVOT_Y = 1.7f
    const val FOLD_ANGLE = 0.82f

    /**
     * THE FOOT PLATES FLARE AS THEY ROTATE. Each plate opens front-to-back with the fold, so the
     * two flat feet visibly SPREAD as they swing inward and close under whatever is between them.
     *
     * It flares in DEPTH rather than across the machine, and that is a load-bearing choice: the
     * fold is a rotation in the x-y plane, so widening a plate in z is the one axis that cannot
     * push a foot corner through the floor or out past the collision radius at any fold angle. It
     * costs nothing and it is the difference between "the legs moved" and "the clamp closed".
     */
    const val FOOT_SPREAD = 0.55f

    /**
     * HOW FAR THE CLAMP ACTUALLY SHUTS ON A TANK — and it is nowhere near [FOLD_ANGLE], because
     * there is a tank in the way.
     *
     * A fold of 1 brings the two feet across one another beneath the cab: the clamp closed on
     * nothing. That was fine while the sequence was watched from inside the tank, where the legs
     * pass either side of an eye that cannot see its own hull. The moment the lens pulls back
     * (see [com.x3paranoids.engine.Game.THE LENS]) it stops being fine, because the legs would
     * visibly sweep straight through the tank they are supposed to be gripping.
     *
     * So the drop closes to here instead, and the geometry is worth stating because it is what the
     * tank's own width was then chosen to fit ([TankModel.HALF_W] = 0.60). At this fold the leg's
     * inner face comes to x = ±0.645 — a few centimetres off the hull's flank, gripping it — while
     * the foot plate's inner corner swings to x = ∓0.38 at y = 0.02, which is UNDER the tank's
     * belly. The feet do not meet each other; they meet beneath the hull and cradle it. That is
     * both what the canon describes and the reason the machine can then lift the thing.
     */
    const val FOLD_GRIP = 0.30f
    /** And how far they SPLAY on the way in: the anticipation, the gantry opening its hands. */
    const val FOLD_SPLAY = -0.30f

    init {
        val s = ArrayList<Float>(); val k = ArrayList<Int>(); val l = ArrayList<Int>()
        val ft = ArrayList<Boolean>()

        fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, kd: Int, lg: Int = 0,
                 isFoot: Boolean = false) {
            s.add(x0); s.add(y0); s.add(z0); s.add(x1); s.add(y1); s.add(z1); k.add(kd); l.add(lg)
            ft.add(isFoot)
        }
        /** The 12 edges of an axis-aligned box in the machine's frame. */
        fun box(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, kd: Int, lg: Int = 0) {
            val xs = floatArrayOf(cx - hx, cx + hx)
            val zs = floatArrayOf(cz - hz, cz + hz)
            val ys = floatArrayOf(cy - hy, cy + hy)
            for (y in ys) {
                line(xs[0], y, zs[0], xs[1], y, zs[0], kd, lg)
                line(xs[1], y, zs[0], xs[1], y, zs[1], kd, lg)
                line(xs[1], y, zs[1], xs[0], y, zs[1], kd, lg)
                line(xs[0], y, zs[1], xs[0], y, zs[0], kd, lg)
            }
            for (x in xs) for (z in zs) line(x, ys[0], z, x, ys[1], z, kd, lg)
        }

        // cross-bar
        box(0f, 2.0f, 0f, Recognizer.BAR_HW, 0.3f, Recognizer.HALF_D, BODY)
        // ribs on the bar
        for (i in -1..1) line(i * 0.8f, 1.7f, -0.5f, i * 0.8f, 2.3f, -0.5f, RIB)
        // cab
        box(0f, 2.62f, 0f, 0.52f, 0.32f, 0.45f, BODY)
        // eye slit
        line(-0.3f, 2.66f, -0.46f, 0.3f, 2.66f, -0.46f, EYE)
        // legs
        val lg = Recognizer.LEG_X
        box(-lg, 0.85f, 0f, 0.3f, 0.85f, 0.42f, BODY, -1)
        box(lg, 0.85f, 0f, 0.3f, 0.85f, 0.42f, BODY, 1)
        // feet, flaring outboard — the corner Recognizer.RADIUS is sized to contain
        val fl = Recognizer.FOOT_FLARE; val fd = Recognizer.HALF_D
        for (side in intArrayOf(-1, 1)) {
            val ox = side * lg
            line(ox - 0.3f, 0f, -0.42f, ox - fl, -0.28f, -fd, BODY, side, true)
            line(ox + 0.3f, 0f, -0.42f, ox + fl, -0.28f, -fd, BODY, side, true)
            line(ox - fl, -0.28f, -fd, ox + fl, -0.28f, -fd, BODY, side, true)
            line(ox - fl, -0.28f, fd, ox + fl, -0.28f, fd, BODY, side, true)
        }
        // THE HINGES, and they are the reason the fold reads as a MECHANISM. Two knuckles on the
        // underside of the bar, one over each leg, fixed to the BAR and not to the leg — so when
        // the legs swing they visibly swing ABOUT these, in unison, rather than merely appearing at
        // a new angle. From inside the tank nobody could ever see them; from outside they are the
        // first thing that tells you what kind of joint this is.
        for (side in intArrayOf(-1, 1)) {
            val ox = side * lg
            line(ox - 0.34f, LEG_PIVOT_Y, -0.30f, ox + 0.34f, LEG_PIVOT_Y, -0.30f, RIB)
            line(ox - 0.34f, LEG_PIVOT_Y, 0.30f, ox + 0.34f, LEG_PIVOT_Y, 0.30f, RIB)
            line(ox, LEG_PIVOT_Y - 0.14f, -0.30f, ox, LEG_PIVOT_Y - 0.14f, 0.30f, RIB)
        }

        seg = s.toFloatArray(); kind = k.toIntArray(); leg = l.toIntArray()
        foot = BooleanArray(ft.size) { ft[it] }; count = k.size
    }

    /**
     * Segment [i] in the machine's own frame, POSED: the legs swung in by [fold] (0 hanging
     * straight, 1 clamped shut, a little below 0 splayed). Fills [out] with x0,y0,z0,x1,y1,z1.
     * Bar and cab segments are returned as they are.
     */
    fun segment(i: Int, fold: Float, out: FloatArray) {
        val b = i * 6
        val side = leg[i]
        if (side == 0 || fold == 0f) {
            for (j in 0 until 6) out[j] = seg[b + j]
            return
        }
        // the left leg (side −1) swings toward +x, the right toward −x: a positive angle for the
        // left, negative for the right, in the x-y plane about the hinge at (side·LEG_X, 1.7).
        // BOTH legs take the same |fold| from the same clock, which is what "in unison" means here:
        // there is one number, and neither leg has a state of its own to drift with.
        val th = -side * fold * FOLD_ANGLE
        val c = cos(th); val s = sin(th)
        val px = side * Recognizer.LEG_X
        // and a foot plate OPENS as it swings — see [FOOT_SPREAD]
        val spread = if (foot[i]) 1f + FOOT_SPREAD * fold.coerceAtLeast(0f) else 1f
        for (e in 0 until 2) {
            val dx = seg[b + e * 3] - px
            val dy = seg[b + e * 3 + 1] - LEG_PIVOT_Y
            out[e * 3] = px + dx * c - dy * s
            out[e * 3 + 1] = LEG_PIVOT_Y + dx * s + dy * c
            out[e * 3 + 2] = seg[b + e * 3 + 2] * spread
        }
    }

    /** Local (ox, oy, oz) at a machine standing at (x, y, z) with heading [yaw] and scale [sc] — see the class note. */
    fun toWorld(x: Float, y: Float, z: Float, yaw: Float, sc: Float, ox: Float, oy: Float, oz: Float, out: FloatArray) {
        val c = cos(yaw); val s = sin(yaw)
        out[0] = x + (ox * c - oz * s) * sc
        out[1] = y + oy * sc
        out[2] = z + (ox * s + oz * c) * sc
    }

    /** The eye's own point light, in local coordinates — drawn, but never a fragment. */
    const val EYE_PT_X = 0f
    const val EYE_PT_Y = 2.66f
    const val EYE_PT_Z = -0.48f
    /** Roughly where the machine's mass is, and the point a derez blows outward FROM. */
    const val CORE_Y = 1.3f
}
