package com.x3paranoids.engine

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 * The arena: a C×C grid of cells, each CELL world units square, with tall wireframe walls on some
 * cell edges and a boundary wall all round. Generated as a perfect maze (recursive backtracker) and
 * then opened up — a share of the internal walls is removed so corridors loop and open halls
 * appear, which is what a tank arena wants (a perfect maze is a puzzle, not a hunting ground).
 *
 * Walls are axis-aligned segments, so collision and line-of-sight are cheap grid tests.
 */
class Maze(val cols: Int, val rows: Int, seed: Long) {

    companion object {
        const val CELL = 9f
        const val WALL_H = 6.5f
        const val THICK = 0.35f
    }

    class Wall(val x0: Float, val z0: Float, val x1: Float, val z1: Float) {
        val vertical get() = abs(x1 - x0) < 1e-3f   // runs along z
        val minX = minOf(x0, x1) - THICK; val maxX = maxOf(x0, x1) + THICK
        val minZ = minOf(z0, z1) - THICK; val maxZ = maxOf(z0, z1) + THICK
    }

    // east[c][r]: wall on the +x side of cell (c,r); south[c][r]: wall on the +z side
    private val east = Array(cols) { BooleanArray(rows) { true } }
    private val south = Array(cols) { BooleanArray(rows) { true } }
    val walls = ArrayList<Wall>()
    private val rng = Random(seed)

    val width get() = cols * CELL
    val depth get() = rows * CELL

    init {
        carve()
        open(0.30f)
        buildWalls()
    }

    private fun carve() {
        val visited = Array(cols) { BooleanArray(rows) }
        val stack = ArrayDeque<IntArray>()
        val sc = rng.nextInt(cols); val sr = rng.nextInt(rows)
        visited[sc][sr] = true; stack.push(intArrayOf(sc, sr))
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        while (stack.isNotEmpty()) {
            val cur = stack.peek()
            val c = cur[0]; val r = cur[1]
            val opts = dirs.filter { d ->
                val nc = c + d[0]; val nr = r + d[1]
                nc in 0 until cols && nr in 0 until rows && !visited[nc][nr]
            }
            if (opts.isEmpty()) { stack.pop(); continue }
            val d = opts[rng.nextInt(opts.size)]
            val nc = c + d[0]; val nr = r + d[1]
            when {
                d[0] == 1 -> east[c][r] = false
                d[0] == -1 -> east[nc][nr] = false
                d[1] == 1 -> south[c][r] = false
                else -> south[nc][nr] = false
            }
            visited[nc][nr] = true
            stack.push(intArrayOf(nc, nr))
        }
    }

    /** Remove a share of the remaining INTERNAL walls so the maze has loops and halls. */
    private fun open(share: Float) {
        for (c in 0 until cols) for (r in 0 until rows) {
            if (c < cols - 1 && east[c][r] && rng.nextFloat() < share) east[c][r] = false
            if (r < rows - 1 && south[c][r] && rng.nextFloat() < share) south[c][r] = false
        }
    }

    private fun buildWalls() {
        walls.clear()
        // boundary
        walls += Wall(0f, 0f, width, 0f)
        walls += Wall(0f, depth, width, depth)
        walls += Wall(0f, 0f, 0f, depth)
        walls += Wall(width, 0f, width, depth)
        for (c in 0 until cols) for (r in 0 until rows) {
            val x = c * CELL; val z = r * CELL
            if (c < cols - 1 && east[c][r]) walls += Wall(x + CELL, z, x + CELL, z + CELL)
            if (r < rows - 1 && south[c][r]) walls += Wall(x, z + CELL, x + CELL, z + CELL)
        }
    }

    fun cellX(c: Int) = (c + 0.5f) * CELL
    fun cellZ(r: Int) = (r + 0.5f) * CELL
    fun colOf(x: Float) = floor(x / CELL).toInt().coerceIn(0, cols - 1)
    fun rowOf(z: Float) = floor(z / CELL).toInt().coerceIn(0, rows - 1)

    /** Can you step from cell (c,r) in direction (dc,dr)? */
    fun passable(c: Int, r: Int, dc: Int, dr: Int): Boolean {
        val nc = c + dc; val nr = r + dr
        if (nc !in 0 until cols || nr !in 0 until rows) return false
        return when {
            dc == 1 -> !east[c][r]
            dc == -1 -> !east[nc][nr]
            dr == 1 -> !south[c][r]
            else -> !south[nc][nr]
        }
    }

    /** BFS distances (in cells) from (c,r); -1 = unreachable. */
    fun distances(c: Int, r: Int): Array<IntArray> {
        val d = Array(cols) { IntArray(rows) { -1 } }
        val q = ArrayDeque<IntArray>()
        d[c][r] = 0; q.add(intArrayOf(c, r))
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        while (q.isNotEmpty()) {
            val cur = q.poll(); val cc = cur[0]; val rr = cur[1]
            for (dir in dirs) if (passable(cc, rr, dir[0], dir[1])) {
                val nc = cc + dir[0]; val nr = rr + dir[1]
                if (d[nc][nr] < 0) { d[nc][nr] = d[cc][rr] + 1; q.add(intArrayOf(nc, nr)) }
            }
        }
        return d
    }

    /** Next cell on a shortest path from (c,r) toward (tc,tr), or null when already there / unreachable. */
    fun stepToward(c: Int, r: Int, tc: Int, tr: Int): IntArray? {
        if (c == tc && r == tr) return null
        val d = distances(tc, tr)
        if (d[c][r] < 0) return null
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        var best: IntArray? = null; var bestD = d[c][r]
        for (dir in dirs) if (passable(c, r, dir[0], dir[1])) {
            val nc = c + dir[0]; val nr = r + dir[1]
            if (d[nc][nr] in 0 until bestD) { bestD = d[nc][nr]; best = intArrayOf(nc, nr) }
        }
        return best
    }

    /** Slide a circle of radius `rad` from (x,z) by (dx,dz) against the walls; returns the new position and whether it bumped. */
    fun move(x: Float, z: Float, dx: Float, dz: Float, rad: Float, out: FloatArray): Boolean {
        var nx = x + dx; var nz = z
        var bumped = false
        for (w in walls) if (overlaps(nx, nz, rad, w)) {
            nx = if (dx > 0) w.minX - rad else w.maxX + rad
            bumped = true
        }
        nz = z + dz
        for (w in walls) if (overlaps(nx, nz, rad, w)) {
            nz = if (dz > 0) w.minZ - rad else w.maxZ + rad
            bumped = true
        }
        out[0] = nx; out[1] = nz
        return bumped
    }

    private fun overlaps(x: Float, z: Float, rad: Float, w: Wall) =
        x + rad > w.minX && x - rad < w.maxX && z + rad > w.minZ && z - rad < w.maxZ

    fun inWall(x: Float, z: Float, rad: Float): Boolean { for (w in walls) if (overlaps(x, z, rad, w)) return true; return false }

    /**
     * True when the segment (x0,z0)→(x1,z1) crosses no wall.
     *
     * [pad] grows every wall in all four directions, which at a corner — where the growth wraps the
     * wall's END — is the useful part: it is the margin by which the line has to CLEAR the corner
     * before this counts as a sight line. Firing uses a padded test so a Recognizer edging round a
     * corner cannot loose a bolt that appears to come out of the wall; awareness uses pad 0, so it
     * still sees you the instant you are actually visible.
     */
    fun lineOfSight(x0: Float, z0: Float, x1: Float, z1: Float, pad: Float = 0f): Boolean {
        for (w in walls) if (segmentHitsBox(x0, z0, x1, z1, w.minX - pad, w.minZ - pad, w.maxX + pad, w.maxZ + pad)) return false
        return true
    }

    /** Parametric distance (0..1) along the segment to the first wall, or 2 when clear. */
    fun rayHit(x0: Float, z0: Float, x1: Float, z1: Float): Float {
        var best = 2f
        for (w in walls) { val t = segmentBoxT(x0, z0, x1, z1, w.minX, w.minZ, w.maxX, w.maxZ); if (t < best) best = t }
        return best
    }

    private fun segmentHitsBox(x0: Float, z0: Float, x1: Float, z1: Float, bx0: Float, bz0: Float, bx1: Float, bz1: Float) =
        segmentBoxT(x0, z0, x1, z1, bx0, bz0, bx1, bz1) <= 1f

    private fun segmentBoxT(x0: Float, z0: Float, x1: Float, z1: Float, bx0: Float, bz0: Float, bx1: Float, bz1: Float): Float {
        val dx = x1 - x0; val dz = z1 - z0
        var tmin = 0f; var tmax = 1f
        if (abs(dx) < 1e-6f) { if (x0 < bx0 || x0 > bx1) return 2f } else {
            var t1 = (bx0 - x0) / dx; var t2 = (bx1 - x0) / dx
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            tmin = maxOf(tmin, t1); tmax = minOf(tmax, t2)
            if (tmin > tmax) return 2f
        }
        if (abs(dz) < 1e-6f) { if (z0 < bz0 || z0 > bz1) return 2f } else {
            var t1 = (bz0 - z0) / dz; var t2 = (bz1 - z0) / dz
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            tmin = maxOf(tmin, t1); tmax = minOf(tmax, t2)
            if (tmin > tmax) return 2f
        }
        return tmin
    }
}
