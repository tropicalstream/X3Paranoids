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
    /** The SHIELD bursting rather than a machine dying — see [seedShield]. Drawn cyan, not green. */
    val shield: Boolean = false,
    /**
     * How far the legs were folded when it died — see [RecognizerModel.segment]. A machine shot
     * while it is clamped round the tank overloads and fractures with its legs still closed; the
     * pieces it breaks into are the pose you were looking at, not the pose it was drawn in.
     */
    val fold: Float = 0f,
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
     *
     * THE SHIELD HAS NONE EITHER, for the opposite reason: a Recognizer's overload is anticipation
     * you watch happen to somebody else, and there is no watching a thing that is wrapped around
     * your own head. The bolt lands and the shell is gone on that frame. Its drama is in the
     * fragments rushing OUTWARD past the periscope.
     */
    val overload get() = if (player || shield) 0f else 0.16f
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
        val m = RecognizerModel
        val local = FloatArray(6); val pa = FloatArray(3); val pb = FloatArray(3)
        for (i in 0 until m.count) {
            // the same posed segment and the same local→world the renderer drew a frame ago
            m.segment(i, fold, local)
            m.toWorld(ox, oy, oz, yaw, sc, local[0], local[1], local[2], pa)
            m.toWorld(ox, oy, oz, yaw, sc, local[3], local[4], local[5], pb)
            val ax0 = pa[0]; val ay0 = pa[1]; val az0 = pa[2]
            val bx0 = pb[0]; val by0 = pb[1]; val bz0 = pb[2]
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

    /**
     * THE SHELL BURSTS. The last charge goes and the bubble comes apart into the very segments it
     * was drawn from a frame earlier — [ShieldModel]'s equator and its meridians, the bands that
     * were still lit at one charge — scaled to [radius] about the periscope and thrown RADIALLY
     * OUTWARD, hard.
     *
     * Outward is the whole difference between this and every other derez in the game. A Recognizer
     * opens along its own shape and falls; the tank's hull leaves you at walking pace so you can
     * watch it go. The shield is a thing you are INSIDE, so it fails by rushing past your head and
     * out into the arena — fast (5–11 u/s against the Recognizer's 2.4–5.8) and short-lived (under
     * a second and a half), because a shell that lingers is a shell you might still be behind.
     *
     * There is deliberately no upward bias in the throw: the hull's derez adds one so the pieces
     * arc and you read the gravity, but a shield that popped upward would read as a bubble rising
     * away rather than as cohesion failing all at once.
     */
    fun seedShield(rnd: () -> Float) {
        broken = true
        val radius = sc
        val m = ShieldModel
        for (i in 0 until m.count) {
            val b = i * 6
            if (m.band[i] > 0) continue          // only what was still drawn at the last charge
            val ax0 = ox + m.seg[b] * radius; val ay0 = oy + m.seg[b + 1] * radius; val az0 = oz + m.seg[b + 2] * radius
            val bx0 = ox + m.seg[b + 3] * radius; val by0 = oy + m.seg[b + 4] * radius; val bz0 = oz + m.seg[b + 5] * radius
            val cx = (ax0 + bx0) * 0.5f; val cy = (ay0 + by0) * 0.5f; val cz = (az0 + bz0) * 0.5f
            var dx = cx - ox; var dy = cy - oy; var dz = cz - oz
            val dl = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01f)
            dx /= dl; dy /= dl; dz /= dl
            val sp = 5f + rnd() * 6f
            add(cx, cy, cz, ax0 - cx, ay0 - cy, az0 - cz,
                dx * sp + (rnd() - 0.5f) * 1.2f,
                dy * sp * 0.55f + (rnd() - 0.5f) * 1.2f,
                dz * sp + (rnd() - 0.5f) * 1.2f,
                0.75f + rnd() * 0.65f, RecognizerModel.BODY, rnd)
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

/**
 * Overload, then fracture, then the grid. [Derez] describes the shape of the sequence; this is only
 * its physics, and it lives out here rather than inside [Game] because the ATTRACT LOOP kills a
 * Recognizer too. A death that looked subtly different in the demo than in the game would be a
 * lie told by the poster, so both arenas run this exact function over their own list and their own
 * maze — there is no second copy to drift.
 *
 * FRAGMENTS OBEY THE WALLS, like everything else in this game: the centre goes through [Maze.move]
 * on a small radius, so a Recognizer that derezzes against a wall throws its pieces back off it
 * instead of through it, and the debris of a death round a corner stays round the corner. The
 * renderer runs its own sight test per fragment, so it is never DRAWN through one either.
 *
 * The landing is the part that ties the death to the room. A piece bounces once or twice with most
 * of its energy gone, and from the first touch it is [Frag.down]: its own length rotates down into
 * the horizontal (at constant length — it lies flat, it does not shrink), its spin bleeds off, and
 * it slides to a stop on the floor grid it will fade into.
 */
fun updateDerezList(list: ArrayList<Derez>, maze: Maze, dt: Float, tmp: FloatArray, gravity: Float, rnd: () -> Float) {
    if (list.isEmpty()) return
    val di = list.iterator()
    while (di.hasNext()) {
        val d = di.next()
        d.t += dt
        if (!d.broken) {
            if (d.t >= d.overload) {
                if (d.shield) d.seedShield(rnd) else if (d.player) d.seedPlayer(rnd) else d.seedRecognizer(rnd)
            }
            continue
        }
        val fi = d.frags.iterator()
        while (fi.hasNext()) {
            val f = fi.next()
            f.life -= dt
            if (f.life <= 0f) { fi.remove(); continue }
            f.vy -= gravity * dt
            // tumble: Rodrigues about the fragment's own axis
            if (kotlin.math.abs(f.w) > 0.01f) {
                val th = f.w * dt
                val ct = kotlin.math.cos(th); val st = kotlin.math.sin(th)
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
            val low = f.cy - kotlin.math.abs(f.ey)
            if (low < 0.05f) {
                f.cy += 0.05f - low
                if (f.vy < 0f) { f.vy = -f.vy * 0.30f; f.vx *= 0.62f; f.vz *= 0.62f; f.w *= 0.5f }
                if (kotlin.math.abs(f.vy) < 0.7f) f.vy = 0f
                f.down = true
            }
            if (f.down) {
                // settle flat onto the grid, at constant length
                val l0 = kotlin.math.sqrt(f.ex * f.ex + f.ey * f.ey + f.ez * f.ez)
                f.ey *= kotlin.math.max(0f, 1f - dt * 3.4f)
                val l1 = kotlin.math.sqrt(f.ex * f.ex + f.ey * f.ey + f.ez * f.ez).coerceAtLeast(1e-4f)
                val k = l0 / l1
                f.ex *= k; f.ey *= k; f.ez *= k
                val fr = kotlin.math.max(0f, 1f - dt * 1.7f)
                f.vx *= fr; f.vz *= fr; f.w *= kotlin.math.max(0f, 1f - dt * 2.4f)
            }
        }
        if (d.frags.isEmpty()) di.remove()
    }
}
