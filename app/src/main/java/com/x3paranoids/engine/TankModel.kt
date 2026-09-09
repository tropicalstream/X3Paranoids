package com.x3paranoids.engine

import kotlin.math.cos
import kotlin.math.sin

/**
 * THE PLAYER'S TANK, WRITTEN DOWN — and until today it did not exist.
 *
 * This game has only ever been first person. Nothing was ever drawn at the player's position,
 * because the player's position was the camera and you cannot see your own eye. The moment the
 * capture pulls the lens back off the hull ([Game.CIN_BACK]) that stops being true: a camera five
 * units behind the tank filming an empty patch of floor with a Recognizer stamping on it is a bug,
 * not a cinematic. So there is a tank now.
 *
 * IT HAS TO AGREE WITH WHAT THE FIRST PERSON ALWAYS IMPLIED. Three facts were already on screen for
 * every minute anyone has ever played, and the model is built from them rather than invented
 * alongside them:
 *
 *  - THE EYE IS AT [Game.EYE_H] = 1.5. That is where the camera has always been, so that is where
 *    the periscope head is centred — origin in x and z, because the camera has always sat exactly
 *    over the tank's own centre and the return at the end of the cinematic has to land on it.
 *  - THE SHELL LEAVES BELOW AND AHEAD OF THE EYE. [Game.fire] spawns it at `EYE_H − 0.45` and 1.2
 *    units forward, so the barrel's bore runs at y = 1.05 and its muzzle is at z = −1.3: the round
 *    now visibly comes out of the end of a gun rather than out of the air.
 *  - THE HEAD AIMS AND THE HULL DRIVES, and they are two different angles ([Game.yaw] is the head
 *    plus the hull; [Game.hullYaw] is the hull alone). So the model is in TWO PARTS: a chassis that
 *    holds [Game.hullYaw], and a sight-and-gun assembly on top of it that holds the full [Game.yaw]
 *    and elevates with the player's pitch. From outside, the player's own head visibly slews the
 *    turret. That is the single detail that makes the pull-back read as YOUR tank rather than a
 *    prop that happens to be parked there.
 *
 * THE IDIOM IS THE ARENA'S. Glowing strokes on black, no fills, one colour per role — the same
 * rules [RecognizerModel] is drawn under, because these two objects are about to be interlocked in
 * the same shot and anything drawn to different rules would read as pasted in.
 *
 * IT HAS TO SURVIVE BEING GRIPPED, and that is what set its width — see [HALF_W]. A Recognizer's
 * legs hang at x = ±1.35 and swing in to [RecognizerModel.FOLD_GRIP], bringing their inner faces to
 * ±0.645; the chassis is 1.2 wide so they close ON it rather than through it, and the foot plates
 * pass on underneath and cradle it. The deck is 0.88 high so the cross-bar clears it and comes down
 * over the periscope. The silhouette is deliberately LOW, LONG and horizontal against a machine
 * that is tall, narrow and vertical: two shapes that read apart at a glance in a wide shot, even as
 * one closes on the other.
 *
 * LOCAL → WORLD is [RecognizerModel]'s, exactly: local −z is forward and maps to the heading the
 * engine steers by. There is one transform in this codebase and both models use it.
 */
object TankModel {

    /** The chassis: hull plates, the track rails' frame. Drawn in the tank's phosphor green. */
    const val HULL = 0
    /** Running gear and panel lines — dimmer, detail rather than frame. */
    const val TRIM = 1
    /** The sight block and the barrel: the part the player IS. */
    const val SIGHT = 2
    /** The vision slit — the tank's own eye, and the counterpart to the Recognizer's red one. */
    const val SLIT = 3

    /** Segments that belong to the chassis and turn with [Game.hullYaw]. */
    const val PART_HULL = 0
    /** Segments on the sight assembly: they turn with the full [Game.yaw] and elevate with pitch. */
    const val PART_TURRET = 1

    /** Six floats per segment: x0,y0,z0, x1,y1,z1, in the tank's own frame at scale 1. */
    val seg: FloatArray
    /** What each segment IS — [HULL], [TRIM], [SIGHT], [SLIT]. */
    val kind: IntArray
    /** Which assembly it rides — [PART_HULL] or [PART_TURRET]. */
    val part: IntArray
    val count: Int

    /**
     * THE TURRET RING. The sight assembly rotates about the vertical line through the tank's centre
     * and elevates about this height — which is the bore line, so the gun pivots where a gun pivots
     * rather than swinging its breech through the deck.
     */
    const val PIVOT_Y = 1.05f
    /** Where the periscope's eye sits: the camera's home, and the point the pull-back returns to. */
    const val EYE_Y = 1.5f
    /**
     * HALF-WIDTH OF THE CHASSIS, AND IT IS THE RECOGNIZER THAT CHOSE IT. At
     * [RecognizerModel.FOLD_GRIP] a leg's inner face comes to x = ±0.645, so a hull half a
     * centimetre narrower than that is gripped by the legs rather than passed through by them,
     * while the foot plates swing on underneath it. Every other dimension on this model is argued
     * from the first-person view; this one is argued from the thing that picks it up.
     */
    const val HALF_W = 0.60f

    init {
        val s = ArrayList<Float>(); val k = ArrayList<Int>(); val p = ArrayList<Int>()

        fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, kd: Int, pt: Int) {
            s.add(x0); s.add(y0); s.add(z0); s.add(x1); s.add(y1); s.add(z1); k.add(kd); p.add(pt)
        }
        fun box(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, kd: Int, pt: Int) {
            val xs = floatArrayOf(cx - hx, cx + hx)
            val zs = floatArrayOf(cz - hz, cz + hz)
            val ys = floatArrayOf(cy - hy, cy + hy)
            for (y in ys) {
                line(xs[0], y, zs[0], xs[1], y, zs[0], kd, pt)
                line(xs[1], y, zs[0], xs[1], y, zs[1], kd, pt)
                line(xs[1], y, zs[1], xs[0], y, zs[1], kd, pt)
                line(xs[0], y, zs[1], xs[0], y, zs[0], kd, pt)
            }
            for (x in xs) for (z in zs) line(x, ys[0], z, x, ys[1], z, kd, pt)
        }

        // ---------------------------------------------------------------- the chassis
        // A six-sided side profile rather than a box: a sloped glacis at the front and a cut-away
        // tail, which is what makes it read as ARMOUR at a glance and not as a crate. (z, y) round
        // the plate, nose first — local −z is forward.
        val prof = arrayOf(
            floatArrayOf(-1.30f, 0.58f),   // nose, top of the glacis
            floatArrayOf(-1.02f, 0.16f),   // nose, bottom
            floatArrayOf(1.06f, 0.16f),    // tail, bottom
            floatArrayOf(1.30f, 0.58f),    // tail, top
            floatArrayOf(1.12f, 0.88f),    // deck, rear
            floatArrayOf(-1.12f, 0.88f)    // deck, front
        )
        for (side in intArrayOf(-1, 1)) {
            val x = side * HALF_W
            for (i in prof.indices) {
                val a = prof[i]; val b = prof[(i + 1) % prof.size]
                line(x, a[1], a[0], x, b[1], b[0], HULL, PART_HULL)
            }
        }
        // and the ties across, so it is a solid rather than two flat plates hanging in space
        for (a in prof) line(-HALF_W, a[1], a[0], HALF_W, a[1], a[0], HULL, PART_HULL)

        // RUNNING GEAR. A rail down each flank with road-wheel ticks hanging off it: five short
        // strokes that say the thing rolls, at a third of the body's brightness so they stay detail.
        for (side in intArrayOf(-1, 1)) {
            val x = side * (HALF_W + 0.02f)
            line(x, 0.34f, -1.00f, x, 0.34f, 1.04f, TRIM, PART_HULL)
            for (i in 0 until 5) {
                val z = -0.92f + i * 0.46f
                line(x, 0.34f, z, x, 0.17f, z, TRIM, PART_HULL)
            }
        }

        // ---------------------------------------------------------------- the sight assembly
        // The turret block the periscope stands on, seated on the deck at y 0.90.
        box(0f, 1.04f, 0.06f, 0.42f, 0.16f, 0.54f, SIGHT, PART_TURRET)
        // The periscope head, centred on the eye — this is the thing the player has been looking
        // out of for the whole game, and the camera goes home to the middle of it.
        box(0f, EYE_Y, -0.02f, 0.24f, 0.16f, 0.24f, SIGHT, PART_TURRET)
        // the neck, deck to head
        for (sx in intArrayOf(-1, 1)) line(sx * 0.16f, 1.20f, 0.06f, sx * 0.16f, 1.34f, 0.02f, TRIM, PART_TURRET)
        // THE SLIT. Bright, forward-facing, and the only stroke on the tank in its own colour: the
        // exact counterpart of the Recognizer's red eye, so in the wide shot the two machines are
        // looking at each other and you can see which way both of them face.
        line(-0.17f, 1.53f, -0.26f, 0.17f, 1.53f, -0.26f, SLIT, PART_TURRET)

        // THE BARREL, on the bore line at y = 1.05, out to the muzzle the shells already leave from.
        val br = 0.075f
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            line(sx * br, PIVOT_Y + sy * br, -0.50f, sx * br, PIVOT_Y + sy * br, -1.30f, SIGHT, PART_TURRET)
        }
        // the muzzle ring, and a mantlet collar where it leaves the turret
        for (z in floatArrayOf(-1.30f, -0.56f)) {
            line(-br, PIVOT_Y - br, z, br, PIVOT_Y - br, z, SIGHT, PART_TURRET)
            line(br, PIVOT_Y - br, z, br, PIVOT_Y + br, z, SIGHT, PART_TURRET)
            line(br, PIVOT_Y + br, z, -br, PIVOT_Y + br, z, SIGHT, PART_TURRET)
            line(-br, PIVOT_Y + br, z, -br, PIVOT_Y - br, z, SIGHT, PART_TURRET)
        }

        seg = s.toFloatArray(); kind = k.toIntArray(); part = p.toIntArray(); count = k.size
    }

    /**
     * Segment [i] placed in the world. [hullYaw] turns the chassis; [yaw] turns the sight assembly
     * and [pitch] elevates it about the bore line. Fills [out] with x0,y0,z0, x1,y1,z1.
     *
     * The two parts share an origin and a vertical axis, which is why the turret can be spun
     * against the hull without anything having to be re-seated: the ring is the line x = z = 0.
     */
    fun segment(i: Int, x: Float, y: Float, z: Float, hullYaw: Float, yaw: Float, pitch: Float,
                out: FloatArray) {
        val b = i * 6
        val turret = part[i] == PART_TURRET
        val a = if (turret) yaw else hullYaw
        val c = cos(a); val sn = sin(a)
        val cp = cos(pitch); val sp = sin(pitch)
        for (e in 0 until 2) {
            var ox = seg[b + e * 3]
            var oy = seg[b + e * 3 + 1]
            var oz = seg[b + e * 3 + 2]
            if (turret) {
                // elevate about the bore line before the yaw, so the gun rises in its own plane
                val dy = oy - PIVOT_Y
                val ny = PIVOT_Y + dy * cp - (-oz) * sp
                val nz = -((-oz) * cp + dy * sp)
                oy = ny; oz = nz
            }
            out[e * 3] = x + (ox * c - oz * sn)
            out[e * 3 + 1] = y + oy
            out[e * 3 + 2] = z + (ox * sn + oz * c)
        }
    }
}
