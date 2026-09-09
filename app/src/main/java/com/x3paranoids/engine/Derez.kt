package com.x3paranoids.engine

/**
 * THE DEREZ.
 *
 * In this fiction a program does not explode. It loses COHESION: for a moment it overloads and
 * cannot hold its own shape, then the shape fails along its own edges and the pieces — still lit,
 * still the geometry they always were — tumble out and fall into the floor grid. A fireball would
 * be somebody else's game. This one is made of lines, so it dies as lines.
 *
 * The sequence has three named beats, and the middle one is the whole trick:
 *
 *  1. OVERLOAD ([Derez.overload] seconds). The machine is still WHOLE and still drawn from
 *     [RecognizerModel] — but it flares white-hot, judders in place at about 45 Hz, and swells a
 *     few percent. Anticipation is what makes a death read as authored rather than as a thing that
 *     merely stopped existing. It is short: long enough to see, too short to wait through.
 *  2. FRACTURE. Every segment of the model detaches where it stood. Each becomes a [Frag] with its
 *     own drift, its own tumble about its own axis, and its own life. For the first third of a
 *     second the pieces are still near enough to their old places that the SILHOUETTE IS LEGIBLE —
 *     you can see it was a Recognizer that died, and which way it was facing when it did.
 *  3. GRID-SCATTER. Gravity, walls, and a floor. Pieces bounce once or twice, then lie flat
 *     ([Frag.down] — the segment rotates its own length down into the horizontal), slide, and fade
 *     into the floor grid they landed on. The death is tied to the room it happened in.
 *
 * Colour drains the whole way: hunting red → white at the overload, then washing out through the
 * arena's own phosphor green as cohesion goes, then to nothing. [Frag] carries no colour of its
 * own; the renderer derives it from age, which keeps the drain in one place.
 *
 * THE PLAYER'S DEREZ is the same machinery with [player] set — see [Derez.seedPlayer]. There is no
 * tank model to break (you are inside it), so instead a shell of structure blows OUTWARD PAST THE
 * PERISCOPE: you watch your own hull leave you. It runs slower and longer, and the sight failing
 * on top of it is the renderer's half of the job.
 *
 * BUDGET. A Recognizer derez is exactly [RecognizerModel.count] = 60 line segments and the game
 * holds at most [Game.MAX_DEREZ] of them, so the worst case a wave can produce is a few hundred
 * extra vertices in a batch that caps at 40000. A death that drops the frame rate would be a bad
 * trade; this one is not close to the edge.
 */
class Frag(
    /** Centre of the segment, in world space. */
    var cx: Float, var cy: Float, var cz: Float,
    /** Half-vector centre→endpoint. The segment is (c−e, c+e); tumbling rotates this. */
    var ex: Float, var ey: Float, var ez: Float,
    var vx: Float, var vy: Float, var vz: Float,
    /** Unit spin axis and rate (rad/s). */
    var ax: Float, var ay: Float, var az: Float, var w: Float,
    var life: Float,
    val maxLife: Float,
    /** [RecognizerModel] kind, so the eye slit stays red on the way down. */
    val kind: Int,
) {
    /** Has it touched the floor? Once it has, it settles flat and skitters instead of tumbling. */
    var down = false
}

class Derez(
    val ox: Float, val oy: Float, val oz: Float,
    val yaw: Float, val sc: Float,
    /** The mood it died in, 0 patrol green … 1 hunting red — the colour the white drains back toward. */
    val alert: Float,
    val player: Boolean,
) {
    var t = 0f
    var broken = false
    val frags = ArrayList<Frag>()

    /**
     * The overload beat, before the shape gives way. THE PLAYER HAS NONE, and deliberately: there
     * is no hull model out in front of you to flare, so a pause here would be a pause on nothing.
     * The tank comes apart on the frame you die ([Game.damagePlayer] seeds it immediately) and the
     * slowness of that death lives where it can be seen — in the fragments' much longer life, in
     * the periscope sinking, and in the sight failing on top of both.
     */
    val overload get() = if (player) 0f else 0.16f
    /** 0..1 across the overload — the flare, the judder and the swell all ride this. */
    val flare get() = if (broken) 1f else (t / overload).coerceIn(0f, 1f)

    /** Still worth drawing? */
    val alive get() = !broken || frags.isNotEmpty()

    /**
     * The machine comes apart into the segments it was drawn from. Velocity is radial from the
     * core, so the thing OPENS along its own shape rather than being flung as a cloud — the feet
     * go down and out, the bar goes wide, the cab goes up. That is what keeps the silhouette
     * readable through the first beat of the break.
     */
    fun seedRecognizer(rnd: () -> Float) {
        broken = true
        val c = kotlin.math.cos(yaw); val s = kotlin.math.sin(yaw)
        val m = RecognizerModel
        for (i in 0 until m.count) {
            val b = i * 6
            val ax0 = ox + (m.seg[b] * c + m.seg[b + 2] * s) * sc
            val ay0 = oy + m.seg[b + 1] * sc
            val az0 = oz + (-m.seg[b] * s + m.seg[b + 2] * c) * sc
            val bx0 = ox + (m.seg[b + 3] * c + m.seg[b + 5] * s) * sc
            val by0 = oy + m.seg[b + 4] * sc
            val bz0 = oz + (-m.seg[b + 3] * s + m.seg[b + 5] * c) * sc
            val cx = (ax0 + bx0) * 0.5f; val cy = (ay0 + by0) * 0.5f; val cz = (az0 + bz0) * 0.5f
            var dx = cx - ox; val dy = cy - (oy + RecognizerModel.CORE_Y * sc); var dz = cz - oz
            val dl = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.25f)
            dx /= dl; dz /= dl
            val sp = 2.4f + rnd() * 3.4f
            add(cx, cy, cz, (ax0 - cx), (ay0 - cy), (az0 - cz),
                dx * sp + (rnd() - 0.5f) * 1.6f,
                (dy / dl) * sp * 0.8f + 1.6f + rnd() * 2.4f,
                dz * sp + (rnd() - 0.5f) * 1.6f,
                1.45f + rnd() * 0.95f, m.kind[i], rnd)
        }
    }

    /**
     * The tank's own death, from inside it. A shell of structure at 1.4–3.3 units, thrown outward
     * and past the periscope: the hull leaving you, seen from the seat. Segments are laid mostly
     * TANGENTIALLY — a shell of needles all pointing at your face reads as a hedgehog, not a hull.
     */
    fun seedPlayer(rnd: () -> Float) {
        broken = true
        for (i in 0 until 72) {
            val a = rnd() * 6.2832f
            val e = (rnd() - 0.5f) * 2.0f
            val rad = 1.4f + rnd() * 1.9f
            val ux = kotlin.math.cos(a) * kotlin.math.cos(e)
            val uy = kotlin.math.sin(e)
            val uz = kotlin.math.sin(a) * kotlin.math.cos(e)
            val cx = ox + ux * rad; val cy = oy + uy * rad * 0.8f; val cz = oz + uz * rad
            // a tangent to the shell at this point, so the piece lies across the sphere
            var tx = -kotlin.math.sin(a); var tz = kotlin.math.cos(a); var ty = (rnd() - 0.5f) * 1.2f
            val tl = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(0.01f)
            val half = (0.22f + rnd() * 0.55f) / tl
            tx *= half; ty *= half; tz *= half
            val sp = 2.8f + rnd() * 5.2f
            add(cx, cy, cz, tx, ty, tz,
                ux * sp, uy * sp * 0.7f + 1.2f + rnd() * 1.6f, uz * sp,
                2.3f + rnd() * 1.3f, RecognizerModel.BODY, rnd)
        }
    }

    private fun add(cx: Float, cy: Float, cz: Float, ex: Float, ey: Float, ez: Float,
                    vx: Float, vy: Float, vz: Float, life: Float, kind: Int, rnd: () -> Float) {
        var ax = rnd() * 2f - 1f; var ay = rnd() * 2f - 1f; var az = rnd() * 2f - 1f
        val al = kotlin.math.sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(0.01f)
        ax /= al; ay /= al; az /= al
        val w = (3f + rnd() * 11f) * (if (rnd() < 0.5f) -1f else 1f)
        frags += Frag(cx, cy, cz, ex, ey, ez, vx, vy, vz, ax, ay, az, w, life, life, kind)
    }
}
