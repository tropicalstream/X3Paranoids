package com.x3paranoids.engine

/**
 * THE RECOGNIZER'S SILHOUETTE, WRITTEN DOWN ONCE — 60 line segments in the machine's own frame,
 * at scale 1, origin on the axle, +y up, +z forward-ish (the eye slit faces -z).
 *
 * It exists because two different things now need to know what a Recognizer is MADE of, and they
 * must never disagree:
 *
 *  - [com.x3paranoids.gl.GLRenderer.buildRecognizer] draws it.
 *  - The DEREZ (see [Derez]) takes it apart. A program does not explode, it loses cohesion and
 *    comes apart into the geometry it was made of — so the fragments ARE these segments, detached
 *    where they stood, and for the first beat of the death the shape is still legible enough to
 *    read WHAT died.
 *
 * The same lesson as [Recognizer.RADIUS]: the drawing and the thing that reasons about the drawing
 * were once written out separately, and drifted. There is no longer a second copy to drift from.
 *
 * Local→world for a machine at (x,y,z) with heading `yaw` and scale `sc` is the transform
 * GLRenderer has always used, and [Derez] reproduces it exactly:
 *      wx = x + (ox·cos yaw + oz·sin yaw)·sc
 *      wy = y +  oy·sc
 *      wz = z + (-ox·sin yaw + oz·cos yaw)·sc
 */
object RecognizerModel {

    /** Structure: the bar, the cab, the legs, the feet. Drawn in the machine's mood colour. */
    const val BODY = 0
    /** The three short ribs on the cross-bar — dimmer, they are detail rather than frame. */
    const val RIB = 1
    /** The eye slit. Always red, and it is the last thing to go out. */
    const val EYE = 2

    /** Six floats per segment: x0,y0,z0, x1,y1,z1. */
    val seg: FloatArray
    /** What each segment IS, so a fragment can keep its own colour on the way down. */
    val kind: IntArray
    val count: Int

    init {
        val s = ArrayList<Float>(); val k = ArrayList<Int>()

        fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, kd: Int) {
            s.add(x0); s.add(y0); s.add(z0); s.add(x1); s.add(y1); s.add(z1); k.add(kd)
        }
        /** The 12 edges of an axis-aligned box in the machine's frame. */
        fun box(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, kd: Int) {
            val xs = floatArrayOf(cx - hx, cx + hx)
            val zs = floatArrayOf(cz - hz, cz + hz)
            val ys = floatArrayOf(cy - hy, cy + hy)
            for (y in ys) {
                line(xs[0], y, zs[0], xs[1], y, zs[0], kd)
                line(xs[1], y, zs[0], xs[1], y, zs[1], kd)
                line(xs[1], y, zs[1], xs[0], y, zs[1], kd)
                line(xs[0], y, zs[1], xs[0], y, zs[0], kd)
            }
            for (x in xs) for (z in zs) line(x, ys[0], z, x, ys[1], z, kd)
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
        box(-lg, 0.85f, 0f, 0.3f, 0.85f, 0.42f, BODY)
        box(lg, 0.85f, 0f, 0.3f, 0.85f, 0.42f, BODY)
        // feet, flaring outboard — the corner Recognizer.RADIUS is sized to contain
        val fl = Recognizer.FOOT_FLARE; val fd = Recognizer.HALF_D
        for (side in intArrayOf(-1, 1)) {
            val ox = side * lg
            line(ox - 0.3f, 0f, -0.42f, ox - fl, -0.28f, -fd, BODY)
            line(ox + 0.3f, 0f, -0.42f, ox + fl, -0.28f, -fd, BODY)
            line(ox - fl, -0.28f, -fd, ox + fl, -0.28f, -fd, BODY)
            line(ox - fl, -0.28f, fd, ox + fl, -0.28f, fd, BODY)
        }

        seg = s.toFloatArray(); kind = k.toIntArray(); count = k.size
    }

    /** The eye's own point light, in local coordinates — drawn, but never a fragment. */
    const val EYE_PT_X = 0f
    const val EYE_PT_Y = 2.66f
    const val EYE_PT_Z = -0.48f
    /** Roughly where the machine's mass is, and the point a derez blows outward FROM. */
    const val CORE_Y = 1.3f
}
