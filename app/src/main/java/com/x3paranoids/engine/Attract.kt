package com.x3paranoids.engine

import com.x3paranoids.audio.Sfx
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * WHEN THE BEATS FALL, in seconds from the top of the attract loop.
 *
 * Every one of these is computed by [Game.attractPlan] from the VOICE CLIPS' OWN DURATIONS, which
 * the manifests carry. That is the difference between a demo that is timed and one that merely
 * runs: the camera starts into the maze on the silence after "THE GAME WAS STOLEN", the pilot
 * answers the Protocol once the machine has finished naming it, and the shell is fired into the
 * gap after "YOU REMEMBER" — and if a clip is ever re-rendered a second longer, all of that still
 * lands, because none of it is a hard-coded stopwatch reading.
 */
class AttractPlan(
    /** The title starts drawing itself on. */
    val trace: Float,
    /** The camera stops idling and drives into the maze. */
    val flight: Float,
    /** The camera reaches the firing position; the machine at the end of the corridor sees it. */
    val aim: Float,
    /** The shell leaves the barrel. */
    val fire: Float,
    /** The Bit rises into view. */
    val bit: Float,
    /** The flight is over: the title comes back and INSERT COIN takes the frame. */
    val settle: Float,
    /** The loop fades to black and starts again. */
    val end: Float,
)

/**
 * THE ATTRACT LOOP — the game playing itself, which is the only advertisement it has.
 *
 * The old title screen was a plate: a fixed camera, a rolling grid, one Recognizer turning on the
 * spot. Everything this game learned since then is a thing that plate could not show. So this is
 * the arena, for real — a generated [Maze], the same walls with the same near-field solidity, the
 * same Recognizers under the same per-object occlusion, the same Bit, and the same [Derez]. The
 * camera flies a route through it, takes its corners the way the tank does, and kills something.
 *
 * THE ROUTE IS FOUND, NOT AUTHORED. A hand-placed camera path would have to be re-authored for
 * every maze seed, so instead the flight is a BFS walk between two cells about [TARGET_CELLS]
 * apart, chosen out of eighty candidates by a score for what a poster needs: a LONG STRAIGHT, since
 * the arena's best frames are corridors running away from you; about three CORNERS, since a route
 * with none is a tunnel and not a maze; a CLEAR RUN at the far end, because the last thing the
 * camera does is shoot down it; and a JUNCTION IT CAN STAGE THE CROSSING AT ([crossingWorth]).
 * Position rides the polyline exactly, so the camera is always on the corridor's centreline and can
 * never clip a wall; only the heading is eased, and it aims [LOOK_AHEAD] units up the path so a turn
 * BEGINS before the corner and finishes after it.
 *
 * THE CAMERA'S ARC IS ANALYTIC — [arcAt] — and that is load-bearing rather than tidy. The demo has
 * to know where the camera will be SECONDS BEFORE IT GETS THERE: the crossing is timed backwards
 * from the moment the camera reaches a junction ([crossTime]), and the route is scored on that same
 * timing before it is chosen. A stateful camera cannot be asked either question.
 *
 * NOTHING HERE IS THE GAME'S STATE. The arena's Game keeps its own maze, its own Recognizers and
 * its own score; this owns a parallel set, so a tap can drop the whole thing on the floor mid-frame
 * and start a real game with nothing to unwind.
 */
class Attract(seed: Long, val plan: AttractPlan) {

    companion object {
        /** The camera flies a little lower than the tank sits — a shade more corridor overhead. */
        const val EYE = 1.45f
        /** How far up the path the heading is taken from, so corners are anticipated, not reacted to. */
        const val LOOK_AHEAD = 3.2f
        /** The idle drift before the flight: enough to say the camera is alive, not enough to travel. */
        const val CREEP = 0.5f
        /** A shade over a wave-one patrol's own pace — a machine on its way somewhere. */
        const val PATROL_SPEED = 3.4f
        const val VICTIM_SPEED = 1.3f
        /** How long a route to look for, in cells. Three or four corners at a walkable speed. */
        const val TARGET_CELLS = 7
        /** How long the world takes to fall back into black at the end of a loop. */
        const val FADE = 0.9f
        /** The corridor run the camera wants at the end of the route, to shoot down. */
        const val RUN_WANT = 16f
    }

    val maze = Maze(8, 8, seed)
    private val rng = Random(seed xor 0x51ed2701L)
    private val tmp = FloatArray(2)
    private val tmp2 = FloatArray(2)

    var t = 0f; private set
    var loops = 0; private set

    // the periscope
    var camX = 0f; private set
    var camZ = 0f; private set
    var camY = EYE; private set
    var yaw = 0f; private set
    var pitch = 0.035f; private set
    var muzzle = 0f; private set
    /** 0..1 — the world's own brightness, so the loop opens out of black and falls back into it. */
    var gain = 0f; private set

    val recognizers = ArrayList<Recognizer>()
    val derezzes = ArrayList<Derez>()
    val shots = ArrayList<Shot>()
    val sparks = ArrayList<Spark>()
    var bitX = 0f; private set
    var bitZ = 0f; private set
    var bitOn = false; private set
    var bitT = 0f; private set
    var bitVis = 0f; private set

    /** Sounds the loop wants made — {id, pitch, volume}, drained by [Game] every frame. */
    val events = ArrayList<FloatArray>()

    // ------------------------------------------------------------------ the route
    private val ptX = ArrayList<Float>()
    private val ptZ = ArrayList<Float>()
    private val cells = ArrayList<IntArray>()
    /** Cumulative arc length at each vertex; [cum].last is the whole route. */
    private val cum = ArrayList<Float>()
    private var pathLen = 0f
    /** The heading the camera arrives on, and shoots down. */
    private var endHx = 0f
    private var endHz = -1f
    private var victimD = 12f

    private var victim: Recognizer? = null
    private var patrol: Recognizer? = null
    private val patrolPath = ArrayList<FloatArray>()
    private var patrolStart = 0f
    private var patrolIdx = 0

    private var fired = false
    private var lockSaid = false
    private var bitYes = false
    private var chirpCd = 0f

    init {
        buildRoute()
        restart()
    }

    // ------------------------------------------------------------------ route finding

    /** Walk a shortest path cell by cell; null when the two cells are not usefully apart. */
    private fun walk(sc: Int, sr: Int, tc: Int, tr: Int): ArrayList<IntArray>? {
        val out = ArrayList<IntArray>()
        var c = sc; var r = sr
        out += intArrayOf(c, r)
        var guard = 0
        while ((c != tc || r != tr) && guard++ < 96) {
            val s = maze.stepToward(c, r, tc, tr) ?: return null
            c = s[0]; r = s[1]
            out += intArrayOf(c, r)
        }
        return if (out.size >= 4) out else null
    }

    /** How far a straight line runs on from the last cell, on the heading the camera arrives on. */
    private fun runAhead(p: List<IntArray>): Float {
        val n = p.size
        val ex = maze.cellX(p[n - 1][0]); val ez = maze.cellZ(p[n - 1][1])
        var hx = (p[n - 1][0] - p[n - 2][0]).toFloat(); var hz = (p[n - 1][1] - p[n - 2][1]).toFloat()
        val hl = hypot(hx, hz).coerceAtLeast(0.01f); hx /= hl; hz /= hl
        var d = 20f
        while (d >= 5f) {
            val x = ex + hx * d; val z = ez + hz * d
            if (!maze.inWall(x, z, Recognizer.RADIUS + 0.2f) && maze.lineOfSight(ex, ez, x, z)) return d
            d -= 1f
        }
        return 0f
    }

    private fun corners(p: List<IntArray>): Int {
        var n = 0
        for (i in 1 until p.size - 1) {
            val ax = p[i][0] - p[i - 1][0]; val az = p[i][1] - p[i - 1][1]
            val bx = p[i + 1][0] - p[i][0]; val bz = p[i + 1][1] - p[i][1]
            if (ax != bx || az != bz) n++
        }
        return n
    }

    /** The longest run of steps taken in one direction, in cells. */
    private fun longestStraight(p: List<IntArray>): Int {
        var best = 1; var run = 1
        for (i in 2 until p.size) {
            val ax = p[i - 1][0] - p[i - 2][0]; val az = p[i - 1][1] - p[i - 2][1]
            val bx = p[i][0] - p[i - 1][0]; val bz = p[i][1] - p[i - 1][1]
            run = if (ax == bx && az == bz) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /**
     * Forty candidate routes, scored, best one wins; every candidate is a legal BFS walk, so the
     * worst case is a duller demo and never a camera through a wall.
     *
     * THE SCORE IS THREE WANTS, and the first was learned from the frames. A route scored on corners
     * alone comes back with five of them in seven steps, and a camera that turns every second and a
     * half spends the flight looking at the wall it is about to turn away from — the arena's best
     * frames are its LONG ONES, a corridor running away from you with the wall panels stacking up in
     * perspective. So a long straight is worth the most; about three corners is worth the next most,
     * because a route with none is a corridor and not a maze; and the run at the end is what the
     * camera will shoot down.
     */
    private fun buildRoute() {
        var best: ArrayList<IntArray>? = null
        var bestScore = -1f
        for (attempt in 0 until 80) {
            val sc = rng.nextInt(maze.cols); val sr = rng.nextInt(maze.rows)
            val d = maze.distances(sc, sr)
            val goals = ArrayList<IntArray>()
            for (c in 0 until maze.cols) for (r in 0 until maze.rows) if (d[c][r] == TARGET_CELLS) goals += intArrayOf(c, r)
            if (goals.isEmpty()) continue
            val g = goals[rng.nextInt(goals.size)]
            val p = walk(sc, sr, g[0], g[1]) ?: continue
            val run = runAhead(p)
            val s = 1.2f * min(1f, longestStraight(p) / 3f) +
                (1f - abs(corners(p) - 3) / 4f).coerceIn(0f, 1f) +
                min(1f, run / RUN_WANT) +
                crossingWorth(p)
            if (s > bestScore) { bestScore = s; best = p }
            if (bestScore >= 4.4f) break
        }
        val p = best ?: walk(0, 0, maze.cols - 1, maze.rows - 1) ?: arrayListOf(intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(0, 3))
        cells.clear(); cells.addAll(p)
        ptX.clear(); ptZ.clear(); cum.clear()
        for (c in p) { ptX += maze.cellX(c[0]); ptZ += maze.cellZ(c[1]) }
        cum += 0f
        for (i in 1 until ptX.size) cum += cum[i - 1] + hypot(ptX[i] - ptX[i - 1], ptZ[i] - ptZ[i - 1])
        pathLen = cum.last()
        val n = p.size
        var hx = (p[n - 1][0] - p[n - 2][0]).toFloat(); var hz = (p[n - 1][1] - p[n - 2][1]).toFloat()
        val hl = hypot(hx, hz).coerceAtLeast(0.01f)
        endHx = hx / hl; endHz = hz / hl
        // The victim stands as far down the corridor as the corridor allows, up to a range where the
        // whole machine still reads and its derez has room to scatter. Too close and the fracture
        // fills the frame; too far and it is a green smudge coming apart.
        victimD = runAhead(p).coerceIn(0f, 15f).let { if (it < 5f) 6f else it - 1.5f }
        android.util.Log.i("X3Paranoids", "attract route: cells=$n len=%.1f corners=%d run=%.1f victimD=%.1f"
            .format(pathLen, corners(p), runAhead(p), victimD))
    }

    // ------------------------------------------------------------------ the camera's arc

    /**
     * Where the camera is along the route at time [tt], as an arc length. A slow idle drift under
     * the title, then the flight proper: [ease] is the integral of (1 − cos), blended 35 % with a
     * constant, which leaves the camera stationary at both ends of the flight and peaks at about
     * 1.65× the average — brisk in the middle, never a lurch at the start or a stop at the end.
     */
    fun arcAt(tt: Float): Float = arcAtFor(tt, pathLen)

    /** The same curve for a route of any length — so a CANDIDATE route can be asked the question too. */
    private fun arcAtFor(tt: Float, len: Float): Float {
        val creepAll = CREEP * plan.flight
        if (tt <= plan.flight) return CREEP * tt.coerceAtLeast(0f)
        val u = ((tt - plan.flight) / (plan.aim - plan.flight)).coerceIn(0f, 1f)
        val e = 0.35f * u + 0.65f * (u - sin(6.2832f * u) / 6.2832f)
        return creepAll + (len - creepAll) * e
    }

    private fun sample(s: Float, out: FloatArray) {
        val ss = s.coerceIn(0f, pathLen)
        var i = 0
        while (i < cum.size - 2 && cum[i + 1] < ss) i++
        val seg = (cum[i + 1] - cum[i]).coerceAtLeast(1e-4f)
        val f = ((ss - cum[i]) / seg).coerceIn(0f, 1f)
        out[0] = ptX[i] + (ptX[i + 1] - ptX[i]) * f
        out[1] = ptZ[i] + (ptZ[i + 1] - ptZ[i]) * f
    }

    /** The route's own direction at arc [s] — taken from the SEGMENT, so a corner is a clean flip. */
    private fun dirAt(s: Float, out: FloatArray) {
        val ss = s.coerceIn(0f, pathLen)
        var i = 0
        while (i < cum.size - 2 && cum[i + 1] < ss) i++
        var dx = ptX[i + 1] - ptX[i]; var dz = ptZ[i + 1] - ptZ[i]
        val l = hypot(dx, dz).coerceAtLeast(1e-4f)
        out[0] = dx / l; out[1] = dz / l
    }

    // ------------------------------------------------------------------ seeding

    private fun restart() {
        t = 0f
        recognizers.clear(); derezzes.clear(); shots.clear(); sparks.clear(); events.clear()
        bitOn = false; bitVis = 0f; bitT = 0f; bitYes = false; chirpCd = 0f
        muzzle = 0f; gain = 0f; fired = false; lockSaid = false
        sample(0f, tmp); camX = tmp[0]; camZ = tmp[1]
        dirAt(0f, tmp2); yaw = atan2(tmp2[0], -tmp2[1])
        camY = EYE; pitch = 0.035f
        seedVictim()
        seedPatrol()
    }

    /** The machine at the end of the corridor: asleep, facing away, until the camera is on top of it. */
    private fun seedVictim() {
        val ex = ptX.last(); val ez = ptZ.last()
        val v = Recognizer(ex + endHx * victimD, ez + endHz * victimD)
        v.yaw = atan2(endHx, -endHz)          // looking the way the camera is going: it has not seen you
        v.alert = 0f
        v.phase = 1.3f
        victim = v
        recognizers += v
    }

    /**
     * THE PATROL THAT CROSSES A JUNCTION AHEAD OF YOU AND IS GONE, and the timing of it is the
     * whole trick — this is the beat that shows the occlusion off, so it has to happen at RANGE.
     *
     * It is written the way round that cannot fail. The first version picked the junction by
     * distance — where the camera is at the crossing beat, plus a dozen units — and on the glasses
     * it put a Recognizer through the periscope: at that point in an eased flight the camera is
     * already 80 % of the way along its route, twelve units is past the end of it, the search
     * clamped to the last cell, and the machine crossed exactly where the camera was standing. The
     * frame is unmistakable: a three-metre gantry filling the entire sight, four units away.
     *
     * So the junction is chosen from the ROUTE — in its middle third, preferring one the camera
     * approaches down a straight run so it is in view from two cells back — and the crossing is
     * timed from it: [timeAtArc] says when the camera will arrive, and the machine sets off early
     * enough to be walking through the mouth of the corridor about two seconds before it does.
     * Distance is then a consequence rather than a wish, and it cannot land in your lap again.
     *
     * The perpendicular is what makes it vanish. It enters from a side corridor, crosses the mouth
     * of the camera's own, and leaves by the far side — so the wall between them takes it out of
     * sight on its own, with no scripting, and [Recognizer.vis] ramps the edge of the doorway.
     */
    private fun seedPatrol() {
        patrolPath.clear(); patrolIdx = 0; patrol = null; patrolStart = 1e9f
        val range = band(cells)
        if (range.isEmpty()) return
        val mid = range.first + ((range.last - range.first) * 0.55f).toInt()
        val near = range.sortedBy { abs(it - mid) }
        // THE CROSSING WANTS A CROSSROADS ON A STRAIGHT, and it wants it badly enough to ask for
        // one four times, giving a little away each pass. Watched on the glasses, a junction the route TURNS at puts the whole crossing in
        // the corner of the frame — the camera has already begun leaning into the new corridor by
        // the time the machine is in the old one, so it reads as something walking out of shot. A
        // junction the route runs straight through puts it dead ahead, sliding across the corridor
        // the camera is driving down, and the far wall takes it. That is the shot.
        //
        // IT ALWAYS LEAVES BY A SIDE CORRIDOR, and that is a safety property, not a preference.
        // The version that let it exit ALONG the camera's own corridor put a Recognizer walking
        // away down the passage the camera was flying into — and the camera is half again as fast
        // as a patrol, so it caught the thing up and drove through it, and the poster's middle
        // twenty seconds were a machine growing until it filled the frame. Every route below sends
        // it out perpendicular: out of the far side at a crossroads, or back the way it came where
        // there is only one side corridor. Both diverge from the camera by construction, so the two
        // cannot converge again however the maze is shaped.
        for (pass in 0..3) {
            for (j in near) {
                val ok = throughJunction(cells, j, 1) && when (pass) {
                    0 -> bothSides(cells, j) && throughJunction(cells, j, 2)
                    1 -> bothSides(cells, j)
                    2 -> throughJunction(cells, j, 2)
                    else -> true
                }
                if (ok && seedPatrolAt(j)) return
            }
        }
        android.util.Log.i("X3Paranoids", "attract patrol: no junction affords a crossing on this route")
    }

    /** Try to stage the crossing at route cell [j]; false when it has no side corridor to use. */
    private fun seedPatrolAt(j: Int): Boolean {
        val c = cells[j][0]; val r = cells[j][1]
        val dx = cells[j + 1][0] - cells[j][0]; val dz = cells[j + 1][1] - cells[j][1]
        // ACROSS WHAT? The corridor direction here is taken from the step the camera leaves ON, and
        // if the route TURNS at this cell that is not the corridor the camera arrives down — so its
        // "perpendicular" can be the camera's own approach, and the machine walks up it into the
        // periscope. That is not a hypothetical: the trace caught it at 2.4 units dead ahead, the
        // camera driving through a Recognizer coming the other way. The guard lives here, in the
        // function that uses the direction, rather than in the four callers that choose j.
        val ix = cells[j][0] - cells[j - 1][0]; val iz = cells[j][1] - cells[j - 1][1]
        if (ix != dx || iz != dz) return false
        // the two perpendiculars to the corridor at this cell
        val p1 = intArrayOf(-dz, dx); val p2 = intArrayOf(dz, -dx)
        val in1 = maze.passable(c, r, p1[0], p1[1])
        val in2 = maze.passable(c, r, p2[0], p2[1])
        if (!in1 && !in2) return false
        // Can this junction be staged at all — see [crossTime]. It is asked here in exactly the
        // form the route search asked it, so a route chosen FOR its crossing always gets one.
        val atJunction = crossTime(cells, j)
        if (atJunction < 0f) return false
        // In from one side and out of the other; where only one side is open it crosses the mouth
        // of the corridor and turns back into it, which is a machine looking down your passage and
        // moving on — and still ends with the wall between you.
        val entry = if (in1) p1 else p2
        val exit = if (in1 && in2) p2 else entry
        val nc = c + entry[0]; val nr = r + entry[1]
        val xc = c + exit[0]; val xr = r + exit[1]
        patrolPath += floatArrayOf(maze.cellX(c), maze.cellZ(r))
        patrolPath += floatArrayOf(maze.cellX(xc), maze.cellZ(xr))
        // and on, away into the maze, so it never stands still where you can see it
        var wc = xc
        var wr = xr
        val far = farCellFrom(cells.last()[0], cells.last()[1])
        for (k in 0 until 4) {
            val s = maze.stepToward(wc, wr, far[0], far[1]) ?: break
            wc = s[0]; wr = s[1]
            patrolPath += floatArrayOf(maze.cellX(wc), maze.cellZ(wr))
        }
        val p = Recognizer(maze.cellX(nc), maze.cellZ(nr))
        p.yaw = atan2(-entry[0].toFloat(), entry[1].toFloat())
        p.phase = 0.4f
        patrol = p
        recognizers += p
        // It walks a cell to reach the mouth, so it sets off that long before it is due there.
        patrolStart = max(0.4f, atJunction - Maze.CELL / PATROL_SPEED)
        android.util.Log.i("X3Paranoids", "attract patrol: junction=($c,$r) idx=$j from=($nc,$nr) both=${in1 && in2} atJunction=%.1f camArrives=%.1f walks=%.1f"
            .format(atJunction, timeAtArc(cum[j]), patrolStart))
        return true
    }

    /**
     * Does the camera run straight INTO cell [j] and straight OUT of it? Into, so the junction is
     * in view from two cells back and the crossing can be watched all the way; out of, so the
     * camera is not already leaning into a turn while it happens.
     */
    private fun throughJunction(p: List<IntArray>, j: Int, cellsBack: Int = 2): Boolean {
        if (j < cellsBack || j + 1 >= p.size) return false
        val cx = p[j + 1][0] - p[j][0]; val cz = p[j + 1][1] - p[j][1]
        for (k in 0 until cellsBack) {
            val ax = p[j - k][0] - p[j - k - 1][0]; val az = p[j - k][1] - p[j - k - 1][1]
            if (ax != cx || az != cz) return false
        }
        return true
    }

    /** Where the straight run into [j] begins — the cell the camera stops turning at. */
    private fun runStartFor(p: List<IntArray>, j: Int): Int {
        if (j < 1) return 0
        val dx = p[j][0] - p[j - 1][0]; val dz = p[j][1] - p[j - 1][1]
        var i = j - 1
        while (i >= 1) {
            val ax = p[i][0] - p[i - 1][0]; val az = p[i][1] - p[i - 1][1]
            if (ax != dx || az != dz) break
            i--
        }
        return i
    }

    /** Is the junction a true crossroads — open on BOTH sides, so the machine crosses and is gone? */
    private fun bothSides(p: List<IntArray>, j: Int): Boolean {
        if (j + 1 >= p.size) return false
        val c = p[j][0]; val r = p[j][1]
        val dx = p[j + 1][0] - c; val dz = p[j + 1][1] - r
        return maze.passable(c, r, -dz, dx) && maze.passable(c, r, dz, -dx)
    }

    /** The candidate cells a crossing may be staged at: the middle of the route, never its ends. */
    private fun band(p: List<IntArray>): IntRange = 2..(p.size - 3)

    /**
     * WHAT THIS ROUTE IS WORTH AS A PLACE TO STAGE THE CROSSING — and it is scored on the route
     * rather than settled for afterwards, because the shot needs a particular shape of maze and the
     * search is already looking at forty of them.
     *
     * Left to pick the junction after the fact, the demo takes whatever the chosen route happens to
     * offer, and on the glasses that produced the worst frame of the phase twice over: a junction
     * one cell after a corner, where the camera turns in to find the machine already past and to the
     * side, at bearing 79 degrees and nine units — a Recognizer sliding across your shoulder rather
     * than crossing the corridor ahead. A route that HAS a crossroads on a straight run is worth
     * more than a route that merely has good corners, so it is priced that way.
     */
    private fun crossingWorth(p: List<IntArray>): Float {
        var best = 0f
        for (j in band(p)) {
            if (crossTime(p, j) < 0f) continue                 // no room to stage it: worth nothing
            val w = when {
                // the route must run STRAIGHT through the cell, or "perpendicular" is not across it
                !throughJunction(p, j, 1) -> 0f
                bothSides(p, j) && throughJunction(p, j, 2) -> 1.9f   // straight run into a crossroads: the shot
                bothSides(p, j) -> 1.5f                               // crosses and is gone
                throughJunction(p, j, 2) -> 1.0f                      // in at the mouth and back out
                hasSide(p, j) -> 0.7f
                else -> 0f
            }
            if (w >= 1.9f) return w
            best = max(best, w)
        }
        return best
    }

    /** At least one side corridor to come out of. */
    private fun hasSide(p: List<IntArray>, j: Int): Boolean {
        if (j + 1 >= p.size) return false
        val c = p[j][0]; val r = p[j][1]
        val dx = p[j + 1][0] - c; val dz = p[j + 1][1] - r
        return maze.passable(c, r, -dz, dx) || maze.passable(c, r, dz, -dx)
    }

    /** When the camera reaches arc length [s] — [arcAt] run forwards, since it has no inverse. */
    private fun timeAtArc(s: Float, len: Float = pathLen): Float {
        var t = plan.flight
        while (t < plan.aim) { if (arcAtFor(t, len) >= s) return t; t += 0.05f }
        return plan.aim
    }

    /**
     * WHEN THE MACHINE SHOULD BE STANDING IN JUNCTION [j] — or −1 if this junction cannot be used.
     *
     * One function, asked twice: once of every candidate route while the route is being CHOSEN, and
     * again of the winner while the crossing is being STAGED. That is deliberate. Scored one way and
     * staged another, the search hands back a route whose best junction the stager then refuses, and
     * the demo quietly loses its crossing — or worse, takes it anyway at three units.
     *
     * The rule is a distance argument in disguise. The camera moves at about six units a second at
     * this point in the flight and a patrol walks at [PATROL_SPEED]; a machine that steps into the
     * junction with the camera two seconds out is only six units up the side corridor when the
     * camera gets there, and the frames of that are a gantry filling the sight. Give it three, and
     * it is a whole cell clear and well behind the shoulder by then, while the crossing itself —
     * the part worth watching — happens fifteen to twenty-five units ahead, where the machine reads
     * as a machine. So: as early as the camera's own alignment allows, at least 2.2 s of margin, and
     * a junction that cannot afford that is refused rather than squeezed.
     */
    private fun crossTime(p: List<IntArray>, j: Int): Float {
        val len = (p.size - 1) * Maze.CELL
        val arrive = timeAtArc(j * Maze.CELL, len)
        val align = timeAtArc(runStartFor(p, j) * Maze.CELL, len)
        val at = max(align + 0.35f, arrive - 3.2f)
        return if (at > arrive - 2.2f) -1f else at
    }

    /** Another few cells for the patrol, headed away from where the camera parks. */
    private fun extendPatrol(p: Recognizer) {
        var wc = maze.colOf(p.x); var wr = maze.rowOf(p.z)
        val far = farCellFrom(cells.last()[0], cells.last()[1])
        patrolPath.clear(); patrolIdx = 0
        for (k in 0 until 5) {
            val s = maze.stepToward(wc, wr, far[0], far[1]) ?: break
            wc = s[0]; wr = s[1]
            patrolPath += floatArrayOf(maze.cellX(wc), maze.cellZ(wr))
        }
        if (patrolPath.isEmpty()) patrolStart = 1e9f      // it is as far away as the maze allows
    }

    private fun farCellFrom(c: Int, r: Int): IntArray {
        val d = maze.distances(c, r)
        var bc = c; var br = r; var bd = -1
        for (cc in 0 until maze.cols) for (rr in 0 until maze.rows) if (d[cc][rr] > bd) { bd = d[cc][rr]; bc = cc; br = rr }
        return intArrayOf(bc, br)
    }

    // ------------------------------------------------------------------ update

    private fun ease(a: Float, b: Float, dt: Float, tau: Float): Float {
        var d = b - a
        while (d > PI.toFloat()) d -= 2f * PI.toFloat()
        while (d < -PI.toFloat()) d += 2f * PI.toFloat()
        return a + d * (1f - exp(-dt / tau))
    }

    private fun event(id: Int, pitch: Float, vol: Float) { events += floatArrayOf(id.toFloat(), pitch, vol) }

    /**
     * Per-half-second trace of the demo, OFF. Flip it to true for the camera's arc and heading and,
     * for each machine, its range, its bearing off the centreline and its sight ramp.
     *
     * It is here because this file's one real failure mode is invisible from the code: everything
     * is timed against an eased arc length, and a beat that reads wrong on the glasses ("the
     * Recognizer is in my face again") can be the camera being early, the machine being late, or
     * the junction being in the wrong place — and those three look identical in a screenshot. One
     * line per half second tells you which, in one run.
     */
    private val TRACE = false
    private var traceT = 0f

    fun update(dtRaw: Float) {
        val dt = min(dtRaw, 0.05f)
        t += dt
        if (TRACE) {
            traceT += dt
            if (traceT >= 0.5f) {
                traceT = 0f
                val sb = StringBuilder("att t=%.1f arc=%.1f cam=(%.1f,%.1f) yaw=%.0f".format(t, arcAt(t), camX, camZ, yaw * 57.2958f))
                for (r in recognizers) {
                    var b = (atan2(r.x - camX, -(r.z - camZ)) - yaw) * 57.2958f
                    while (b > 180f) b -= 360f
                    while (b < -180f) b += 360f
                    sb.append(" | %s d=%.1f bear=%.0f vis=%.2f".format(if (r === victim) "V" else "P",
                        hypot(r.x - camX, r.z - camZ), b, r.vis))
                }
                if (patrol != null) sb.append(" pIdx=$patrolIdx/${patrolPath.size}")
                android.util.Log.i("X3Paranoids", sb.toString())
            }
        }
        // out of black at the top of the loop, back into it at the end
        val up = ((t - 0.30f) / 1.35f).coerceIn(0f, 1f)
        val dn = ((t - plan.end) / FADE).coerceIn(0f, 1f)
        gain = up * (1f - dn)
        muzzle = kotlin.math.max(0f, muzzle - dt * 9f)

        // ---- camera: position rides the polyline, heading eases toward it
        val s = arcAt(t)
        sample(s, tmp); camX = tmp[0]; camZ = tmp[1]
        dirAt(s + LOOK_AHEAD, tmp2)
        yaw = ease(yaw, atan2(tmp2[0], -tmp2[1]), dt, 0.34f)
        // A hand on the periscope rather than a rail: a degree of sway, and a breath of pitch. Small
        // on purpose — this is a head-worn display and the camera is already moving on its own.
        camY = EYE + 0.045f * sin(t * 1.55f)
        pitch = 0.030f + 0.011f * sin(t * 0.47f)

        updateRecognizers(dt)
        updateShots(dt)
        updateSparks(dt)
        updateDerezList(derezzes, maze, dt, tmp, Game.FRAG_G, { rng.nextFloat() })
        updateBit(dt)

        if (t >= plan.end + FADE) { loops++; restart() }
    }

    private fun updateRecognizers(dt: Float) {
        val v = victim
        if (v != null) {
            // it wakes a beat and a half before the shell, so the red has time to be read
            val wake = t >= plan.aim - 1.5f
            v.hunting = wake
            v.alert += ((if (wake) 1f else 0f) - v.alert) * (1f - exp(-dt / 0.5f))
            // Asleep it SCANS — a slow sweep either side of the corridor it is watching. On a long
            // straight route the camera can see this machine from twenty-five units out for most of
            // the flight, and a Recognizer standing dead still for that long stops being a threat
            // and becomes scenery. It never moves off its mark; only its head turns.
            val face = if (wake) atan2(-endHx, endHz) else atan2(endHx, -endHz) + 0.42f * sin(t * 0.55f)
            v.yaw = ease(v.yaw, face, dt, 0.30f)
            if (wake && !fired) {
                // one slow step toward the camera: enough to say it is coming for you
                maze.move(v.x, v.z, -endHx * VICTIM_SPEED * dt, -endHz * VICTIM_SPEED * dt, Recognizer.RADIUS, tmp)
                v.x = tmp[0]; v.z = tmp[1]
                if (!lockSaid) { lockSaid = true; event(Sfx.LOCK, 1f, 0.6f) }
            }
        }
        val p = patrol
        if (p != null && t >= patrolStart) {
            // IT NEVER STOPS. Watched on the glasses: it ran out of waypoints in view of the final
            // camera position and stood in the corridor, motionless, for the six seconds the poster
            // is on screen — and a Recognizer that is not moving is a prop. When the route runs out
            // it gets a new one, always away from where the camera ends up, so it patrols off into
            // the maze instead of wandering back into the periscope.
            if (patrolIdx >= patrolPath.size) extendPatrol(p)
        }
        if (p != null && t >= patrolStart && patrolIdx < patrolPath.size) {
            val w = patrolPath[patrolIdx]
            var dx = w[0] - p.x; var dz = w[1] - p.z
            val dl = hypot(dx, dz)
            if (dl < 0.28f) patrolIdx++
            else {
                dx /= dl; dz /= dl
                p.x += dx * PATROL_SPEED * dt; p.z += dz * PATROL_SPEED * dt
                p.yaw = ease(p.yaw, atan2(dx, -dz), dt, 0.16f)
            }
        }

        for (r in recognizers) {
            r.y = 1.6f + 0.3f * sin(t * 2.1f + r.phase)
            // the same three-sample sight test the arena runs — see GLRenderer's OCCLUSION note
            val ec = cos(r.yaw) * Recognizer.HALF_W; val es = -sin(r.yaw) * Recognizer.HALF_W
            val seen = maze.lineOfSight(r.x, r.z, camX, camZ) ||
                maze.lineOfSight(r.x + ec, r.z + es, camX, camZ) ||
                maze.lineOfSight(r.x - ec, r.z - es, camX, camZ)
            val step = dt * Game.VIS_RATE
            r.vis = if (seen) min(1f, r.vis + step) else kotlin.math.max(0f, r.vis - step)
            r.hitFlash = kotlin.math.max(0f, r.hitFlash - dt * 6f)
            // the patrol only ever GLANCES: it warms toward amber while it can see you and cools off
            // again once the wall takes it. The red in this loop belongs to one machine only.
            if (r !== victim) r.alert += ((if (seen && r.vis > 0.5f) 0.5f else 0f) - r.alert) * (1f - exp(-dt / 0.7f))
        }
    }

    private fun updateShots(dt: Float) {
        if (!fired && t >= plan.fire) {
            fired = true; muzzle = 1f
            val cp = cos(pitch)
            val dx = sin(yaw) * cp; val dy = sin(pitch); val dz = -cos(yaw) * cp
            shots += Shot(camX + dx * 1.2f, camY - 0.45f + dy * 1.2f, camZ + dz * 1.2f,
                dx * Game.SHELL_SPEED, dy * Game.SHELL_SPEED, dz * Game.SHELL_SPEED, true)
            event(Sfx.FIRE, 1f, 1f)
        }
        val si = shots.iterator()
        while (si.hasNext()) {
            val s = si.next()
            s.life -= dt
            s.x += s.vx * dt; s.y += s.vy * dt; s.z += s.vz * dt
            if (s.life <= 0f) { si.remove(); continue }
            val v = victim
            if (v != null && hypot(s.x - v.x, s.z - v.z) < 2.1f && abs(s.y - (v.y + 1.3f)) < 2.4f) {
                si.remove()
                derezzes += Derez(v.x, v.y, v.z, v.yaw, 1f, v.alert, false)
                burst(v.x, v.y + 1.2f, v.z, 12, 0.9f, 1f, 0.85f)
                event(Sfx.DEREZ, 0.95f, 1f)
                recognizers.remove(v)
                victim = null
            }
        }
    }

    private fun updateSparks(dt: Float) {
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
            sparks += Spark(x, y, z, cos(a) * sin(e) * sp, cos(e) * sp * 0.8f + 2f, sin(a) * sin(e) * sp,
                0.5f + rng.nextFloat() * 0.7f, r, g, b)
        }
    }

    /**
     * The Bit comes up where the machine died, which is the whole objective stated as a picture:
     * clear the corridor, and the thing that is on your side is waiting in it. It chatters the way
     * it does in the arena, and says YES once, on the beat the title comes back.
     */
    private fun updateBit(dt: Float) {
        if (!bitOn) {
            if (t < plan.bit) return
            val ex = ptX.last(); val ez = ptZ.last()
            // OFF THE CENTRELINE, by about a quarter of the corridor. Dead ahead it lands under the
            // title and behind INSERT COIN, and the last beat of the loop is the one composition
            // that has to be clean; a couple of units to the side puts it in the room instead of in
            // the type. The offset is dropped rather than forced if the corridor is too tight.
            val rx = -endHz; val rz = endHx
            var d = 7f
            while (d > 3f) {
                for (off in floatArrayOf(2.1f, 0f)) {
                    val x = ex + endHx * d + rx * off; val z = ez + endHz * d + rz * off
                    if (!maze.inWall(x, z, 1.2f) && maze.lineOfSight(ex, ez, x, z)) {
                        bitX = x; bitZ = z; bitOn = true; bitT = 0f; chirpCd = 0.35f
                        return
                    }
                }
                d -= 1f
            }
            bitX = ex + endHx * 4f; bitZ = ez + endHz * 4f
            bitOn = true; bitT = 0f; chirpCd = 0.35f
        }
        bitT += dt
        val seen = maze.lineOfSight(bitX, bitZ, camX, camZ)
        val step = dt * Game.VIS_RATE
        bitVis = if (seen) min(1f, bitVis + step) else kotlin.math.max(0f, bitVis - step)
        chirpCd -= dt
        if (chirpCd <= 0f) {
            chirpCd = 1.5f + rng.nextFloat() * 0.7f
            event(Sfx.BIT_CHIRP, 1.25f, 0.28f)
        }
        if (!bitYes && t >= plan.settle) { bitYes = true; event(Sfx.BIT_YES, 1f, 0.5f) }
    }
}
