package com.x3paranoids.engine

import kotlin.math.cos
import kotlin.math.sin

/**
 * THE SHIELD'S SHELL, WRITTEN DOWN ONCE — a unit sphere as line segments, for exactly the reason
 * [RecognizerModel] exists: two different things need to know what the bubble is MADE of and they
 * must never disagree. [com.x3paranoids.gl.GLRenderer.buildShield] draws it around the periscope,
 * and [Derez.seedShield] takes those same segments apart when the last charge goes. A shield that
 * derezzed into geometry it was never drawn from would be a different object bursting.
 *
 * THE SHELL IS THE READOUT. Latitude rings are BANDED, one band per charge: band 0 is the equator,
 * band 1 the pair at ±33 degrees, band 2 the pair at ±58. The renderer draws band < charges, so the
 * shell visibly THINS as it is spent — three charges is a cage, one charge is a single ring round
 * your waist — and "how much shield have I got" is answered by the thing you are already looking
 * through. The meridians never drop; they are what says "there is a shell here at all".
 *
 * The rings come in symmetric PAIRS above and below the equator on purpose. A single ring lost from
 * one side leaves a lopsided bowl, which reads as damage in the wrong place — the shell is meant to
 * get sparser, not crooked.
 *
 * Everything is a UNIT vector; the caller scales by [Game.SHIELD_R]. Poles are world up/down rather
 * than the view axis, so the cage is anchored to the hull and SWEEPS as you look around — that is
 * what makes it read as a thing around the tank instead of a filter over the lens.
 */
object ShieldModel {

    /** Not a latitude ring: a meridian, always drawn while any charge remains. */
    const val MERIDIAN = -1
    /** Latitude bands, and therefore the shield's maximum charge — see [Game.SHIELD_MAX]. */
    const val BANDS = 3

    /** Six floats per segment: x0,y0,z0, x1,y1,z1 — on the unit sphere. */
    val seg: FloatArray
    /** [MERIDIAN], or the band this ring segment belongs to (0 = equator, outward in pairs). */
    val band: IntArray
    val count: Int

    init {
        val s = ArrayList<Float>()
        val b = ArrayList<Int>()
        val tau = 6.2831855f

        fun p(az: Float, el: Float): FloatArray {
            val ce = cos(el)
            return floatArrayOf(cos(az) * ce, sin(el), sin(az) * ce)
        }
        fun line(a: FloatArray, c: FloatArray, bandOf: Int) {
            s.add(a[0]); s.add(a[1]); s.add(a[2]); s.add(c[0]); s.add(c[1]); s.add(c[2]); b.add(bandOf)
        }

        // Six meridians, cut off short of the poles: the convergence at a pole is a bright knot of
        // lines right where you are looking when you glance up, and it buys nothing.
        val meridians = 6
        val mSeg = 6
        val elMax = 1.15f
        for (i in 0 until meridians) {
            val az = tau * i / meridians
            for (j in 0 until mSeg) {
                val e0 = -elMax + 2f * elMax * j / mSeg
                val e1 = -elMax + 2f * elMax * (j + 1) / mSeg
                line(p(az, e0), p(az, e1), MERIDIAN)
            }
        }
        // The bands: the equator, then a symmetric pair further out for each charge above one.
        val rSeg = 16
        val elev = floatArrayOf(0f, 0.58f, 1.02f)
        for (k in elev.indices) {
            val els = if (k == 0) floatArrayOf(0f) else floatArrayOf(elev[k], -elev[k])
            for (e in els) for (i in 0 until rSeg) {
                line(p(tau * i / rSeg, e), p(tau * (i + 1) / rSeg, e), k)
            }
        }
        seg = s.toFloatArray(); band = b.toIntArray(); count = b.size
    }
}
