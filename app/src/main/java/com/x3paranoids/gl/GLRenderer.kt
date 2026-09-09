package com.x3paranoids.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.x3paranoids.SettingsStore
import com.x3paranoids.engine.Game
import com.x3paranoids.engine.Maze
import com.x3paranoids.engine.RecognizerModel
import com.x3paranoids.engine.State
import com.x3paranoids.head.HeadTracker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The cabinet's screen: additive phosphor wireframes on black (transparent on the waveguide), a
 * perspective periscope steered by the head, and a 640×480 ortho HUD in the stroke font — drawn once
 * per eye on the X3's side-by-side viewports. Every line is drawn twice: a wide dim pass for the
 * glow and a thin bright pass for the core, which is how a vector monitor's beam looks.
 */
class GLRenderer(private val game: Game, private val head: HeadTracker, private val store: SettingsStore) : GLSurfaceView.Renderer {

    var sbs = false

    private var program = 0
    private var aPos = 0; private var aColor = 0
    private var uMVP = 0; private var uPointSize = 0; private var uPoint = 0; private var uAlpha = 0
    private var width = 1; private var height = 1
    private var lastNanos = 0L
    private var maxLine = 1f
    /** Reported once at surface creation. Nothing depends on it any more — see [OCCLUSION]. */
    private var depthBits = 0

    private val proj = FloatArray(16); private val view = FloatArray(16); private val mvp = FloatArray(16); private val ortho = FloatArray(16)
    private val lines = Batch(40000)
    private val mesh = Batch(30000)
    private val tris = Batch(3000)
    private val pts = Batch(6000)
    // The HUD batch has room for three times what the sight normally draws, because a failing sight
    // breaks every stroke into three pieces before it starts dropping them.
    private val hud = Batch(30000)
    private val rnd = Random(3)
    private var statT = 0f; private var statFrames = 0

    private var camX = 0f; private var camY = Game.EYE_H; private var camZ = 0f
    private var fogFar = 62f
    /**
     * Per-frame world trace, OFF. Flip it to true for one line per frame naming the periscope's own
     * position and heading, every Recognizer's range, bearing off the centreline, `vis` and its fire
     * gate, and the Bit's range and bearing.
     *
     * It is how the occlusion cull was verified on the glasses — `vis` and `hasLos` agree on every
     * line, so a machine you cannot see is a machine that is not shooting at you — and it is also
     * the only practical way to DRIVE this game over adb, which is what the derez frames and the
     * Bit's proximity chatter were captured with. Bearings are what let a script aim a hull that
     * only turns in quarter steps, and the Bit's range is what lets one navigate to a thing the HUD
     * deliberately refuses to point at.
     */
    private val VERIFY = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram(VERT, FRAG)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize")
        uPoint = GLES30.glGetUniformLocation(program, "uPoint")
        uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        // NO DEPTH TEST, EVER. See [OCCLUSION] — hiding is decided on the CPU, per object, before a
        // vertex is written. Every pass is pure additive phosphor, exactly as a vector monitor sums
        // one beam over another, and no stroke is ever discarded by a buffer.
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        val range = FloatArray(2); GLES30.glGetFloatv(GLES30.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
        maxLine = max(1f, range[1])
        val bits = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_DEPTH_BITS, bits, 0)
        depthBits = bits[0]
        android.util.Log.i("X3Paranoids", "surface: depthBits=${bits[0]} maxLine=$maxLine")
        lastNanos = 0L
        for (b in arrayOf(lines, mesh, tris, pts, hud)) b.contextLost()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        Matrix.orthoM(ortho, 0, 0f, 640f, 480f, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now

        head.update(dt)
        val headOn = store.headLook && head.running
        game.update(dt, head.yaw, head.pitch, headOn)
        buildScene()
        buildHud()

        val eyes = if (sbs) 2 else 1
        val vw = if (sbs) width / 2 else width
        val aspect = vw.toFloat() / height.toFloat()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        // periscope
        val title = game.state == State.TITLE
        // The tank's death carries its own judder on top of the damage shake — decaying, so the
        // world steadies as the sight fails, rather than both of them going at once.
        val shake = game.damageFlash * 0.25f +
            (if (game.state == State.DYING) 0.16f * kotlin.math.exp(-game.stateT * 0.9f) else 0f)
        val sx = (rnd.nextFloat() - 0.5f) * shake; val sy = (rnd.nextFloat() - 0.5f) * shake
        val yaw = if (title) 0f else game.yaw; val pitch = if (title) 0.04f else game.pitch
        val cp = cos(pitch)
        val fx = sin(yaw) * cp; val fy = sin(pitch); val fz = -cos(yaw) * cp
        Matrix.setLookAtM(view, 0, camX + sx, camY + sy, camZ, camX + sx + fx, camY + sy + fy, camZ + fz, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, 60f, aspect, 0.25f, 240f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        statFrames++; statT += dt
        if (VERIFY && game.state == State.PLAY) {
            val sb = StringBuilder("VIS p(%.1f,%.1f) yaw=%.0f".format(game.px, game.pz, game.yaw * 57.2958f))
            for ((i, r) in game.recognizers.withIndex()) {
                var b = (kotlin.math.atan2(r.x - game.px, -(r.z - game.pz)) - game.yaw) * 57.2958f
                while (b > 180f) b -= 360f
                while (b < -180f) b += 360f
                sb.append(" | R$i d=%.1f bear=%.0f vis=%.2f hasLos=%b".format(hypot(r.x - game.px, r.z - game.pz), b, r.vis, r.hasLos))
            }
            var bb = (kotlin.math.atan2(game.bitX - game.px, -(game.bitZ - game.pz)) - game.yaw) * 57.2958f
            while (bb > 180f) bb -= 360f
            while (bb < -180f) bb += 360f
            sb.append(" || BIT d=%.1f bear=%.0f act=%b".format(hypot(game.bitX - game.px, game.bitZ - game.pz), bb, game.bitActive))
            sb.append(" || lock=%b bolts=%d".format(game.lockedOn, game.shots.count { !it.friendly }))
            android.util.Log.i("X3Paranoids", sb.toString())
        }
        if (statT >= 2f) {
            android.util.Log.i("X3Paranoids", "fps=%.1f world=%d infill=%d fill=%d hud=%d hidden=%d/%d".format(
                statFrames / statT, lines.count, mesh.count, tris.count, hud.count, culled, game.recognizers.size))
            statFrames = 0; statT = 0f
        }

        val wide = min(4f, maxLine)
        for (e in 0 until eyes) {
            GLES30.glViewport(e * vw, 0, vw, height)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES30.glUniform1f(uPoint, 0f)
            // the near-wall wash first: a body for the surface the strokes then draw the frame of
            GLES30.glUniform1f(uAlpha, 1f); tris.draw(GLES30.GL_TRIANGLES)
            // the wall infill: a single fine stroke and NO glow pass. A hundred rungs' halos merge
            // into a smear, and the wash is already doing that job — so the fine ladder stays crisp,
            // and a wall you are pressed against costs half the fill rate it otherwise would.
            GLES30.glLineWidth(1.5f.coerceAtMost(maxLine)); mesh.draw(GLES30.GL_LINES)
            // glow pass then core pass
            GLES30.glLineWidth(wide); GLES30.glUniform1f(uAlpha, 0.30f); lines.draw(GLES30.GL_LINES)
            GLES30.glLineWidth(1.5f.coerceAtMost(maxLine)); GLES30.glUniform1f(uAlpha, 1f); lines.draw(GLES30.GL_LINES)
            GLES30.glUniform1f(uPoint, 1f); GLES30.glUniform1f(uPointSize, 9f); GLES30.glUniform1f(uAlpha, 1f); pts.draw(GLES30.GL_POINTS)
            clearPlate(e, vw)
            // The sight is bolted to the glass, not standing in the arena: the ortho pass draws last
            // and unconditionally, so no wall can ever eat a bracket or a readout.
            GLES30.glUniformMatrix4fv(uMVP, 1, false, ortho, 0)
            GLES30.glUniform1f(uPoint, 0f)
            GLES30.glLineWidth(min(3f, maxLine)); GLES30.glUniform1f(uAlpha, 0.28f); hud.draw(GLES30.GL_LINES)
            GLES30.glLineWidth(1.2f.coerceAtMost(maxLine)); GLES30.glUniform1f(uAlpha, 1f); hud.draw(GLES30.GL_LINES)
        }
    }

    // ------------------------------------------------------------------ scene

    private fun buildScene() {
        lines.reset(); pts.reset(); tris.reset(); mesh.reset()
        if (game.state == State.TITLE) { buildTitleScene(); return }
        camX = game.px; camY = Game.EYE_H - game.deathSink; camZ = game.pz
        fogFar = 62f
        val tint = game.wallTint()
        buildFloor(game.maze, tint)
        buildWalls(game.maze, tint)
        culled = 0
        for (r in game.recognizers) {
            if (r.vis <= 0.001f) { culled++; continue }
            buildRecognizer(r.x, r.y, r.z, r.yaw, 1f, r.alert, r.hitFlash, r.vis)
        }
        if (game.bitActive && game.bitVis > 0.001f) {
            buildBit(game.bitX, 1.4f + 0.25f * sin(game.time * 3f), game.bitZ, game.bitT, game.bitVis)
        }
        buildShots()
        buildSparks()
        buildDerez()
        buildMuzzle()
    }

    // ------------------------------------------------------------------ [OCCLUSION]
    /**
     * A WALL HIDES WHAT IS BEHIND IT — and the hiding is decided here, on the CPU, one object at a
     * time, before a single vertex is written.
     *
     * The alternative was tried and is what this replaces: a depth-only prepass that drew every wall
     * panel and the whole floor as invisible colour-masked solids, then depth-TESTED the strokes
     * against them. On paper that is true hidden-line removal, walls hiding walls included. On this
     * hardware it ate the world. The failure is a coplanarity problem the prepass cannot win: the
     * arena's floor grid lies at y=0 and the invisible floor slab lies at y=0 UNDER ALL OF IT, and
     * every wall's base and top strokes lie exactly on the wall's own invisible panel. Nothing
     * separates them but glPolygonOffset, on a 16-bit depth buffer stretched over a 0.25–240 frustum
     * where a floor cell seen from eye height is very nearly edge-on and the offset's slope term is
     * both largest and least predictable. When the tie-break lost, it lost for the entire y=0 plane
     * at once. The evidence frames say exactly that: the wall the tank happened to be facing drew
     * its full ladder — head-on, zero depth slope, the offset behaves — and every stroke on the
     * floor in front of it was gone, which read as "the world vanished, leaving flat bands".
     *
     * So: no depth buffer in the render path at all. The renderer is back to the pure additive one
     * the cabinet wants, and a Recognizer standing behind a wall is simply NOT DRAWN. [Maze.lineOfSight]
     * is exact, it is the same test the Recognizers' own AI has run every frame since the first
     * commit, and it cannot half-work: there is no precision to lose and no driver to disagree with.
     *
     * What this deliberately does NOT do is hide a wall behind a wall. That is a real loss and a
     * small one — a 1982 vector cabinet drew every edge of every wall it knew about, so the arena
     * showing its own far corridors through the near ones is the idiom rather than a bug, and the
     * near-wall solidity work (density, [nearGain], the wash) is what tells you which wall is the
     * one you are about to hit. The owner's complaint was a Recognizer painted OVER the wall it was
     * standing behind, and that is now impossible.
     *
     * A CENTRE POINT IS NOT ENOUGH. A Recognizer is 3.7 units across the feet, so a test against its
     * axle alone pops the whole machine out while a third of it is still in plain sight round the
     * corner. Visibility is sampled at three points — the axle and both ends of the cross-bar, in
     * its current heading — and the machine counts as seen if ANY of them is. The remaining
     * transition is honest, and [Recognizer.vis] ramps it over about a tenth of a second so a
     * machine crossing a doorway de-rezzes rather than strobing. Shots and sparks are point-sized
     * and short-lived, so they take the bare test with no ramp.
     */
    private var culled = 0

    private fun visible(x: Float, z: Float) = game.maze.lineOfSight(camX, camZ, x, z)

    private fun buildTitleScene() {
        camX = 0f; camY = 1.3f; camZ = 0f
        fogFar = 55f
        val t = game.time
        // a floor of light rolling toward the viewer
        val off = (t * 3.5f) % 3f
        for (i in 0..22) {
            val z = -66f + i * 3f + off
            wline(-40f, 0f, z, 40f, 0f, z, 0.25f, 1f, 0.45f, 0.5f)
        }
        var x = -39f
        while (x <= 39f) { wline(x, 0f, -66f, x, 0f, 1f, 0.25f, 1f, 0.45f, 0.35f); x += 3f }
        // the Recognizer, turning slowly, mood drifting between patrol green and hunting red
        val alert = 0.5f + 0.5f * sin(t * 0.55f)
        buildRecognizer(0f, 0.35f + 0.2f * sin(t * 1.7f), -11f, t * 0.5f, 1.7f, alert, 0f, 1f)
        // a Bit chattering at its side
        buildBit(3.4f, 1.6f + 0.2f * sin(t * 2.3f), -8f, t, 1f)
    }

    private fun fog(x: Float, y: Float, z: Float): Float {
        val dx = x - camX; val dy = y - camY; val dz = z - camZ
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        return (1f - d / fogFar).coerceIn(0.06f, 1f)
    }

    /** A world line with per-vertex fog. */
    private fun wline(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, b: Float, a: Float) {
        lines.v(x0, y0, z0, r, g, b, a * fog(x0, y0, z0))
        lines.v(x1, y1, z1, r, g, b, a * fog(x1, y1, z1))
    }

    private fun buildFloor(m: Maze, tint: FloatArray) {
        val step = Maze.CELL / 2f
        val seg = Maze.CELL / 2f
        var x = 0f
        while (x <= m.width + 0.01f) {
            var z = 0f
            while (z < m.depth - 0.01f) { wline(x, 0f, z, x, 0f, min(z + seg, m.depth), tint[0], tint[1], tint[2], 0.42f); z += seg }
            x += step
        }
        var z = 0f
        while (z <= m.depth + 0.01f) {
            var xx = 0f
            while (xx < m.width - 0.01f) { wline(xx, 0f, z, min(xx + seg, m.width), 0f, z, tint[0], tint[1], tint[2], 0.42f); xx += seg }
            z += step
        }
    }

    // ------------------------------------------------------------- wall solidity
    /**
     * A near wall has to STOP you — and on an additive see-through display there is no darkness to
     * stop you with. Black is the one colour the waveguide cannot draw, there is no depth buffer,
     * and a pale fill would break the cabinet. So solidity is built out of light, in three layers
     * that all key off one number: how far this panel of wall is from the periscope.
     *
     * DENSITY. Each ~3 unit panel carries a ladder of rungs built in halving levels — level k adds
     * 2^k rungs at the odd 1/2^(k+1) heights, so the union of levels 0..K is an EVEN ladder of
     * 2^(K+1)-1 rungs and no level ever lands on another's line. Level k fades up across the band
     * [RUNG_ON[k] → RUNG_ON[k]·RUNG_FULL], so rungs arrive out of black one interleaved set at a
     * time: the surface fills in as you close on it and empties as you back off, with nothing to pop.
     * Far panels keep exactly the sparse frame they always had.
     *
     * The onsets are set in SCREEN space, not world space, because that is the only place solidity
     * happens: perspective spreads a fixed world spacing wider the closer you get, so a ladder that
     * looks dense at ten units is seven lonely bars at arm's length. Each level's full-brightness
     * range is picked so its rungs land roughly 84, 64, 48, 35, 26, 22 and finally 15 px apart — a
     * wall you are pressed against ends up carrying 127 of them and reads as a lit surface, while one
     * across the arena still carries a single mid rail. The last level's onset is pulled right in to
     * 2.4 units: it is the most expensive rung set by far and it only earns its keep on the one panel
     * you are actually touching. Deeper levels are dimmed slightly ([RUNG_DIM]) so the halves and
     * quarters stay the wall's structure and the fine ladder between them stays texture — and so 127
     * strokes never add up to a white screen.
     *
     * BRIGHTNESS. [nearGain] lifts a stroke's alpha ABOVE 1 inside [NEAR_GAIN_D]; the fragment shader
     * emits rgb·a, so a > 1 drives the phosphor past its own colour into a white-hot core. That is
     * what a real vector monitor does when the beam dwells on a short stroke, and it is the single
     * strongest "this is close" cue available here.
     *
     * BODY. Inside [FILL_D] the panel also gets an additive wash — brightest along the floor, half
     * that at the top, and gone entirely by [FILL_D]. It is what lives BETWEEN the rungs, and it is
     * the difference between a lit surface and a grating. Its hue is the wall's own tint pushed hard
     * toward saturated green, so it stays a coloured glow at every wave tint and never becomes the
     * pale plate the cabinet forbids — even at wave 6, when the strokes themselves have gone white.
     * [FILL_A] is deliberately low and [FILL_D] short — under a cell, the range at which a wall is a
     * thing you are ABOUT to hit. Wound up, it stacked: additive quads have no depth to hide behind,
     * so a wash strong enough to matter at corridor range summed through every wall beyond it and
     * hazed the whole arena, and at point-blank — where one panel IS the entire screen — it drowned
     * the sight in a green field. The ladder is the surface; this is only its shadow.
     *
     * One number governs all of it: the blend is (GL_SRC_ALPHA, GL_ONE) over a shader that already
     * emits rgb·a, so what actually lands on the waveguide is rgb·a SQUARED. Alpha is not a dimmer
     * here, it is a gamma — which is why the wash needs [FILL_A] as high as it looks, and why a gain
     * above 1 blows a near stroke out so hard.
     *
     * Every one of the three is shaded PER VERTEX from that vertex's own distance, so a panel and its
     * neighbour always agree along their shared edge — the wall shades off along its length instead
     * of stepping panel by panel. Emission is decided by the panel's NEAREST point, which is only ever
     * more generous than the shading, so a level that is emitted but out of range simply shades to
     * zero at both ends.
     */
    private val RUNG_ON = floatArrayOf(34f, 23f, 16f, 11f, 7.5f, 4.6f, 2.4f)
    private val RUNG_FULL = 0.5f
    private val RUNG_A = 0.34f
    private val RUNG_DIM = 0.04f
    private val NEAR_GAIN = 1.25f
    private val NEAR_GAIN_D = 12f
    private val FILL_D = 8f
    private val FILL_A = 0.5f

    /** Horizontal range to the periscope: height is ignored on purpose, so a tall wall's whole face is equally near. */
    private fun hdist(x: Float, z: Float) = hypot(x - camX, z - camZ)

    private fun nearGain(d: Float) = 1f + NEAR_GAIN * (1f - d / NEAR_GAIN_D).coerceIn(0f, 1f)

    private fun rungFade(k: Int, d: Float): Float {
        val on = RUNG_ON[k]
        return ((on - d) / (on * (1f - RUNG_FULL))).coerceIn(0f, 1f)
    }

    /** Linear in alpha, which — alpha being squared on the way to the glass — is a smooth quadratic in light. */
    private fun fillFade(d: Float) = FILL_A * (1f - d / FILL_D).coerceIn(0f, 1f)

    /** Nearest horizontal range from the periscope to the panel, not to its midpoint. */
    private fun panelDist(ax: Float, az: Float, bx: Float, bz: Float): Float {
        val dx = bx - ax; val dz = bz - az
        val ll = dx * dx + dz * dz
        val t = if (ll < 1e-6f) 0f else (((camX - ax) * dx + (camZ - az) * dz) / ll).coerceIn(0f, 1f)
        return hypot(camX - (ax + dx * t), camZ - (az + dz * t))
    }

    /** A wall stroke: per-vertex fog and per-vertex beam gain, with its own alpha at each end. */
    private fun sline(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, t: FloatArray, a0: Float, a1: Float) {
        lines.v(x0, y0, z0, t[0], t[1], t[2], a0 * fog(x0, y0, z0) * nearGain(hdist(x0, z0)))
        lines.v(x1, y1, z1, t[0], t[1], t[2], a1 * fog(x1, y1, z1) * nearGain(hdist(x1, z1)))
    }

    /** The same, into the un-glowed infill batch. */
    private fun rline(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, t: FloatArray, a0: Float, a1: Float) {
        mesh.v(x0, y0, z0, t[0], t[1], t[2], a0 * fog(x0, y0, z0) * nearGain(hdist(x0, z0)))
        mesh.v(x1, y1, z1, t[0], t[1], t[2], a1 * fog(x1, y1, z1) * nearGain(hdist(x1, z1)))
    }

    /** The panel's body: two triangles, floor-bright and fading up, in the wall's own hue pushed dark and saturated. */
    private fun fillPanel(ax: Float, az: Float, bx: Float, bz: Float, h: Float, t: FloatArray, ga: Float, gb: Float) {
        val r = t[0] * 0.30f; val g = t[1] * 0.70f; val b = t[2] * 0.40f
        val a0 = ga * fog(ax, 0f, az); val b0 = gb * fog(bx, 0f, bz)
        val a1 = a0 * 0.5f; val b1 = b0 * 0.5f
        tris.v(ax, 0f, az, r, g, b, a0); tris.v(bx, 0f, bz, r, g, b, b0); tris.v(bx, h, bz, r, g, b, b1)
        tris.v(ax, 0f, az, r, g, b, a0); tris.v(bx, h, bz, r, g, b, b1); tris.v(ax, h, az, r, g, b, a1)
    }

    private fun buildWalls(m: Maze, tint: FloatArray) {
        val h = Maze.WALL_H
        for (w in m.walls) {
            val len = if (w.vertical) abs(w.z1 - w.z0) else abs(w.x1 - w.x0)
            val n = max(1, (len / 3f).toInt())
            val dx = w.x1 - w.x0; val dz = w.z1 - w.z0
            // The posts stay on the wall's own fixed 3-unit rhythm at every range, so a post can never
            // appear or vanish as you drive — they are the wall's bright bones and a popping one shows.
            for (i in 0..n) {
                val t = i.toFloat() / n
                sline(w.x0 + dx * t, 0f, w.z0 + dz * t, w.x0 + dx * t, h, w.z0 + dz * t, tint, 0.85f, 0.85f)
            }
            // Each panel is re-cut finer as IT closes, because the shading is per vertex: the ends of
            // a 3-unit panel are up to 1.5 units further off than its middle, and at arm's length
            // that bias is the whole difference between a lit wall and a blank one. Cutting per panel
            // rather than per wall matters — a boundary wall is 72 units long, and only the yard of
            // it beside you deserves the vertices. Only the interpolation changes, never where a
            // stroke lies, so nothing pops as a panel's cut steps up; and a panel and its neighbour
            // still share their edge vertex exactly, whatever cut either of them chose.
            for (p in 0 until n) {
                val pa = p.toFloat() / n; val pb = (p + 1f) / n
                val cut = panelDist(w.x0 + dx * pa, w.z0 + dz * pa, w.x0 + dx * pb, w.z0 + dz * pb)
                    .let { if (it > 9f) 1 else if (it > 4.5f) 2 else 3 }
                for (s in 0 until cut) {
                    val t0 = pa + (pb - pa) * s / cut; val t1 = pa + (pb - pa) * (s + 1f) / cut
                    val ax = w.x0 + dx * t0; val az = w.z0 + dz * t0
                    val bx = w.x0 + dx * t1; val bz = w.z0 + dz * t1
                    sline(ax, 0f, az, bx, 0f, bz, tint, 0.9f, 0.9f)
                    sline(ax, h, az, bx, h, bz, tint, 0.9f, 0.9f)
                    val near = panelDist(ax, az, bx, bz)
                    val da = hdist(ax, az); val db = hdist(bx, bz)
                    for (k in RUNG_ON.indices) {
                        if (near >= RUNG_ON[k]) continue
                        val ka = RUNG_A * (1f - RUNG_DIM * k)
                        val fa = ka * rungFade(k, da); val fb = ka * rungFade(k, db)
                        if (fa < 0.004f && fb < 0.004f) continue
                        val cnt = 1 shl k
                        for (j in 0 until cnt) {
                            val y = h * (2 * j + 1) / (2f * cnt)
                            rline(ax, y, az, bx, y, bz, tint, fa, fb)
                        }
                    }
                    if (near < FILL_D) {
                        val ga = fillFade(da); val gb = fillFade(db)
                        if (ga >= 0.004f || gb >= 0.004f) fillPanel(ax, az, bx, bz, h, tint, ga, gb)
                    }
                }
            }
        }
    }

    /**
     * The Recognizer: cross-bar, raised cab with a red eye, two hanging legs with flared feet.
     *
     * Every stroke of it comes out of [RecognizerModel], which is derived in turn from the same
     * constants the ENGINE moves the machine on. When the drawing and the collider were written out
     * separately they disagreed — a bar drawn 1.65 out either side, a collider of 1.2 — and every
     * Recognizer that hugged a wall put 45 cm of cross-bar inside it. Now the derez is a third
     * reader of the same numbers, and there is still no way to change one of them alone.
     */
    private fun buildRecognizer(x: Float, y: Float, z: Float, yaw: Float, sc: Float, alert: Float, flash: Float, vis: Float,
                                gain: Float = 1f, whiten: Float = 0f) {
        var r = 0.25f + 0.75f * alert; var g = 1f - 0.75f * alert; var b = 0.45f - 0.25f * alert
        r += (1f - r) * flash; g += (1f - g) * flash; b += (1f - b) * flash
        // THE OVERLOAD wash: colour drains toward white before the shape gives way, and the alpha
        // gain drives it past its own colour into a hot core (the shader emits rgb·a, so a > 1 is
        // the beam dwelling — the same trick nearGain uses on a wall you are about to hit).
        r += (1f - r) * whiten; g += (1f - g) * whiten; b += (1f - b) * whiten
        val a = 0.95f * vis * gain
        val c = cos(yaw); val s = sin(yaw)
        fun lx(ox: Float, oz: Float) = x + (ox * c + oz * s) * sc
        fun lz(ox: Float, oz: Float) = z + (-ox * s + oz * c) * sc
        // The silhouette comes from RecognizerModel, which is also what the derez takes apart — the
        // machine you watch break is made of exactly the segments you were looking at a frame before.
        val eyeR = 1f; val eyeG = 0.25f + 0.2f * (1f - alert) + (1f - 0.25f) * whiten; val eyeB = 0.2f + 0.8f * whiten
        val ea = (0.55f + 0.45f * alert) * vis * gain
        val m = RecognizerModel
        for (i in 0 until m.count) {
            val k = i * 6
            val ax = lx(m.seg[k], m.seg[k + 2]); val ay = y + m.seg[k + 1] * sc; val az = lz(m.seg[k], m.seg[k + 2])
            val bx = lx(m.seg[k + 3], m.seg[k + 5]); val by = y + m.seg[k + 4] * sc; val bz = lz(m.seg[k + 3], m.seg[k + 5])
            when (m.kind[i]) {
                RecognizerModel.RIB -> wline(ax, ay, az, bx, by, bz, r, g, b, 0.6f * vis * gain)
                RecognizerModel.EYE -> wline(ax, ay, az, bx, by, bz, eyeR, eyeG, eyeB, ea)
                else -> wline(ax, ay, az, bx, by, bz, r, g, b, a)
            }
        }
        pts.v(lx(RecognizerModel.EYE_PT_X, RecognizerModel.EYE_PT_Z), y + RecognizerModel.EYE_PT_Y * sc,
            lz(RecognizerModel.EYE_PT_X, RecognizerModel.EYE_PT_Z), eyeR, 0.3f + 0.7f * whiten, 0.25f + 0.75f * whiten, ea * fog(x, y, z))
    }

    // ------------------------------------------------------------------ [DEREZ]
    /**
     * A program losing cohesion, drawn. [com.x3paranoids.engine.Derez] owns the sequence and its
     * physics; this owns what it LOOKS like, and the whole look is one idea: the thing never stops
     * being made of the lines it was always made of.
     *
     * OVERLOAD. The machine is still whole and still drawn by [buildRecognizer], but pushed through
     * two extra knobs it already had — `whiten` drains the hunting red toward white, and `gain`
     * lifts every stroke's alpha above 1 so the phosphor blows out into a white-hot core. On top,
     * a JUDDER: the whole body is displaced by a few centimetres, re-rolled about 45 times a second
     * from a hash of the frame index, so it stutters rather than shimmers. The three together are
     * about 160 ms of "this is about to fail", which is what makes the break read as authored.
     *
     * FRACTURE. Each fragment is one of the model's own segments, still glowing, tumbling on its own
     * axis. Colour is derived entirely from age, so the drain lives in one place: white at the break,
     * washing back through the machine's own mood colour, then toward the arena's phosphor green as
     * it falls, and the last third of a fragment's life is a fade to nothing. A grounded fragment is
     * pulled the rest of the way to the floor grid's exact colour — the death dissolves into the room.
     *
     * The eye slit keeps its red the whole way down. It is the one part of a Recognizer that was
     * never structure, and watching it fall still lit is the detail that sells the rest.
     *
     * A DEREZ BEHIND A WALL STAYS BEHIND IT. Every fragment takes the same per-object sight test as
     * its Recognizer did (see [OCCLUSION]) — a machine you could not see does not get to explain
     * where it was by scattering through the wall.
     */
    private fun buildDerez() {
        if (game.derezzes.isEmpty()) return
        val tint = game.wallTint()
        for (d in game.derezzes) {
            if (!d.broken) {
                if (d.player) continue                       // there is no hull model to flare
                if (!visible(d.ox, d.oz)) continue
                val f = d.flare
                // ~45 Hz stutter, held for the whole frame so it judders instead of buzzing
                val step = (d.t * 45f).toInt()
                val jx = (hash01(step * 7 + 1) - 0.5f) * 0.20f * f
                val jy = (hash01(step * 7 + 3) - 0.5f) * 0.16f * f
                val jz = (hash01(step * 7 + 5) - 0.5f) * 0.20f * f
                buildRecognizer(d.ox + jx, d.oy + jy, d.oz + jz, d.yaw, d.sc * (1f + 0.07f * f),
                    d.alert, 0f, 1f, gain = 1f + 2.4f * f, whiten = f)
                continue
            }
            for (p in d.frags) {
                if (!visible(p.cx, p.cz)) continue
                val u = 1f - (p.life / p.maxLife).coerceIn(0f, 1f)          // 0 fresh … 1 gone
                // white at the break → the mood it died in → the arena's own green as it settles
                val hot = (1f - u * 4.5f).coerceIn(0f, 1f)
                val cool = (u * 1.5f).coerceIn(0f, 1f)
                var r = 0.25f + 0.75f * d.alert; var g = 1f - 0.75f * d.alert; var b = 0.45f - 0.25f * d.alert
                if (p.kind == RecognizerModel.EYE) { r = 1f; g = 0.28f; b = 0.22f }
                r += (tint[0] - r) * cool; g += (tint[1] - g) * cool; b += (tint[2] - b) * cool
                r += (1f - r) * hot; g += (1f - g) * hot; b += (1f - b) * hot
                // the last third of a life is the fade; a grounded piece also dims into the grid
                var a = (p.life / (p.maxLife * 0.34f)).coerceIn(0f, 1f) * (0.95f + 0.85f * hot)
                if (p.down) a *= 0.72f
                if (a < 0.01f) continue
                wline(p.cx - p.ex, p.cy - p.ey, p.cz - p.ez, p.cx + p.ex, p.cy + p.ey, p.cz + p.ez, r, g, b, a)
            }
        }
    }

    /** A cheap deterministic 0..1 — the judder has to HOLD for a frame, so it cannot come from rnd. */
    private fun hash01(k: Int): Float {
        var h = k * -0x61c88647
        h = h xor (h ushr 15); h *= -0x7a143595; h = h xor (h ushr 13)
        return ((h ushr 8) and 0xFFFF) / 65535f
    }

    /** The Bit: a spinning octahedron, cyan when idle, swelling yellow when it says "yes". */
    private fun buildBit(x: Float, y: Float, z: Float, t: Float, vis: Float) {
        val yes = ((t % 2.6f) > 2.1f)
        val rad = if (yes) 0.95f else 0.7f
        val r = if (yes) 1f else 0.4f; val g = if (yes) 0.95f else 0.85f; val b = if (yes) 0.35f else 1f
        val a = t * 2.2f; val e = t * 1.3f
        val c1 = cos(a); val s1 = sin(a); val c2 = cos(e); val s2 = sin(e)
        fun tx(px: Float, py: Float, pz: Float): FloatArray {
            val x1 = px * c1 - pz * s1; val z1 = px * s1 + pz * c1
            val y2 = py * c2 - z1 * s2; val z2 = py * s2 + z1 * c2
            return floatArrayOf(x + x1 * rad, y + y2 * rad, z + z2 * rad)
        }
        val v = arrayOf(tx(1f, 0f, 0f), tx(-1f, 0f, 0f), tx(0f, 1f, 0f), tx(0f, -1f, 0f), tx(0f, 0f, 1f), tx(0f, 0f, -1f))
        val edges = intArrayOf(0, 2, 0, 3, 0, 4, 0, 5, 1, 2, 1, 3, 1, 4, 1, 5, 2, 4, 2, 5, 3, 4, 3, 5)
        var i = 0
        while (i < edges.size) { val p = v[edges[i]]; val q = v[edges[i + 1]]; wline(p[0], p[1], p[2], q[0], q[1], q[2], r, g, b, 0.95f * vis); i += 2 }
        pts.v(x, y, z, r, g, b, 0.8f * vis * fog(x, y, z))
        if (yes) for (k in 0 until 6) { val p = v[k]; pts.v(p[0], p[1], p[2], 1f, 1f, 0.6f, 0.9f * vis * fog(x, y, z)) }
    }

    /**
     * A bolt is hidden by a wall exactly as its Recognizer is, so a machine you cannot see cannot
     * appear to shoot at you through the maze. The tail is drawn from the head, so the head's own
     * sight line governs the whole streak — a shell crossing a doorway is briefly half-length rather
     * than half-through a wall, which is the right way for a stroke that lives a few frames to end.
     */
    private fun buildShots() {
        for (s in game.shots) {
            if (!visible(s.x, s.z)) continue
            val l = sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz).coerceAtLeast(0.01f)
            val k = if (s.friendly) 1.6f / l else 1.1f / l
            val r = if (s.friendly) 1f else 1f; val g = if (s.friendly) 0.88f else 0.28f; val b = if (s.friendly) 0.3f else 0.22f
            wline(s.x, s.y, s.z, s.x - s.vx * k, s.y - s.vy * k, s.z - s.vz * k, r, g, b, 1f)
            pts.v(s.x, s.y, s.z, r, g, b, fog(s.x, s.y, s.z))
        }
    }

    /** A derez behind a wall stays behind it: sparks take the same sight test, per particle. */
    private fun buildSparks() {
        for (p in game.sparks) {
            if (!visible(p.x, p.z)) continue
            pts.v(p.x, p.y, p.z, p.r, p.g, p.b, p.life.coerceIn(0f, 1f) * fog(p.x, p.y, p.z))
        }
    }

    /** The cannon's beam flares from below the periscope toward the sight, like the cabinet's shot. */
    private fun buildMuzzle() {
        val m = game.muzzle; if (m <= 0f) return
        val yaw = game.yaw; val pitch = game.pitch
        val cp = cos(pitch)
        val fx = sin(yaw) * cp; val fy = sin(pitch); val fz = -cos(yaw) * cp
        val rx = cos(yaw); val rz = sin(yaw)
        val x0 = camX + fx * 0.9f + rx * 0.05f; val y0 = camY - 0.55f + fy * 0.9f; val z0 = camZ + fz * 0.9f + rz * 0.05f
        val x1 = camX + fx * 2.6f; val y1 = camY - 0.25f + fy * 2.6f; val z1 = camZ + fz * 2.6f
        lines.v(x0, y0, z0, 1f, 0.95f, 0.5f, m); lines.v(x1, y1, z1, 1f, 0.95f, 0.5f, m * 0.6f)
    }

    // ------------------------------------------------------------------ HUD

    private val sink = object : StrokeFont.LineSink {
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float) = hl(x0, y0, x1, y1)
    }
    private var cr = 1f; private var cg = 1f; private var cb = 1f; private var ca = 1f
    private fun color(r: Float, g: Float, b: Float, a: Float = 1f) { cr = r; cg = g; cb = b; ca = a }
    private fun text(s: String, x: Float, y: Float, sc: Float) = StrokeFont.draw(corrupt(s), x, y, sc, sink)
    private fun textC(s: String, cx: Float, y: Float, sc: Float) = StrokeFont.draw(corrupt(s), cx - StrokeFont.width(s, sc) / 2f, y, sc, sink)
    private fun textR(s: String, rx: Float, y: Float, sc: Float) = StrokeFont.draw(corrupt(s), rx - StrokeFont.width(s, sc), y, sc, sink)

    // ------------------------------------------------------------------ [THE SIGHT FAILING]
    /**
     * 0 while the sight is an instrument; 1 when it has stopped being one. Only the tank's own derez
     * raises it — and when it does, EVERY hud stroke in the file goes through here without a single
     * call site knowing about it, because [hl] is the one door they all use and the stroke font's
     * sink now goes through it too. That is the whole reason this works: the brackets, the chevrons,
     * the bezel dials, the readouts, the nav plate and its wall traces all break up together, as one
     * failing display, and nothing had to be hand-authored to make them.
     *
     * A stroke breaks into three pieces; each piece can DROP OUT (increasingly likely), each is
     * offset a little, and each is dragged sideways by a TEAR shared with every other stroke on its
     * screen row — which is what makes it read as a raster giving up rather than as noise. All of it
     * is driven by an integer hash of the stroke's own coordinates and a seed that ticks about 14
     * times a second, so the corruption HOLDS for a few frames and stutters, the way real broken
     * hardware does; re-rolled every frame it would shimmer, which reads as an effect, not a fault.
     *
     * By the end the sight is gone entirely, and GAME OVER arrives on a clean screen. The player
     * should feel derezzed, not be told they died.
     */
    private var glitch = 0f
    private var glitchSeed = 0
    /** Substitutions the stroke font can actually draw. */
    private val GARBLE = charArrayOf('/', '?', '!', '<', '>', 'X', 'Z', '-')

    private fun gnoise(k: Int): Float {
        var h = (k * -0x61c88647) xor (glitchSeed * -0x7a143595)
        h = h xor (h ushr 15); h *= 0x2c1b3c6d; h = h xor (h ushr 12)
        return ((h ushr 8) and 0xFFFF) / 65535f
    }

    /** Readouts lose characters before they lose their strokes — text fails first, and legibly so. */
    private fun corrupt(s: String): String {
        if (glitch < 0.28f) return s
        val p = (glitch - 0.28f) * 1.25f
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            val n = gnoise(s.length * 7919 + i * 31 + s[i].code)
            sb.append(if (n < p) GARBLE[(n * 977f).toInt() % GARBLE.size] else s[i])
        }
        return sb.toString()
    }

    private fun rawHl(x0: Float, y0: Float, x1: Float, y1: Float) { hud.v(x0, y0, 0f, cr, cg, cb, ca); hud.v(x1, y1, 0f, cr, cg, cb, ca) }

    private fun hl(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (glitch <= 0.001f) { rawHl(x0, y0, x1, y1); return }
        val g = glitch
        val key = (x0 * 3.1f + y0 * 7.7f + x1 * 1.9f + y1 * 0.7f).toInt()
        for (i in 0 until 3) {
            val k = key * 31 + i
            // dropout accelerates, so a fully failed sight is actually EMPTY rather than 40% there
            if (gnoise(k) < g * 0.44f + g * g * 0.55f) continue
            val t0 = i / 3f; val t1 = (i + 1) / 3f
            var ax = x0 + (x1 - x0) * t0; var ay = y0 + (y1 - y0) * t0
            var bx = x0 + (x1 - x0) * t1; var by = y0 + (y1 - y0) * t1
            val row = (((ay + by) * 0.5f) / 26f).toInt()
            val tear = (gnoise(row * 977 + 11) - 0.5f) * 66f * g * g
            val ox = tear + (gnoise(k + 5) - 0.5f) * 11f * g
            val oy = (gnoise(k + 9) - 0.5f) * 7f * g
            ax += ox; bx += ox; ay += oy; by += oy
            rawHl(ax, ay, bx, by)
        }
    }
    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) { hl(x0, y0, x1, y0); hl(x1, y0, x1, y1); hl(x1, y1, x0, y1); hl(x0, y1, x0, y0) }
    private fun circle(cx: Float, cy: Float, rad: Float, n: Int) {
        for (i in 0 until n) { val a0 = 6.2832f * i / n; val a1 = 6.2832f * (i + 1) / n; hl(cx + cos(a0) * rad, cy + sin(a0) * rad, cx + cos(a1) * rad, cy + sin(a1) * rad) }
    }

    private val GREEN = floatArrayOf(0.35f, 1f, 0.55f)

    /**
     * The nav plate's box, driven into the sight's TOP-right corner. Two things fix it there.
     *
     * Vertically it is as high as the sight allows: its top edge sits at y=48, immediately under the
     * RECOGNIZERS / timer row, so the readouts and the plate form one clean band across the top of
     * the sight and the plate's whole body — down to y=130 — is a hundred pixels clear of the level
     * horizon at y=240. That matters because the plate is a hole cut in the world (see [clearPlate]):
     * the higher it rides, the less of the arena it takes with it. At its old height it bit chunks
     * out of the corridor's wall edges at the exact range where you read them; up here it mostly
     * covers black sky and the tops of walls too far away to matter, and a downward glance — which
     * sweeps the floor grid up the screen — no longer runs the grid under it.
     *
     * Horizontally the gutter is the hard limit: the sight's top-right bracket ends at x=520 and the
     * bezel's right-hand dial caps at (612,62) with a radius of 7, so x=605 upward is spoken for.
     * That leaves 85 px, and the plate takes 75 of them — a clear 5 px of black on each side. Riding
     * this high costs the plate the 9 px of width it used to steal from below the dial's cap; a
     * slightly smaller square that never touches anything reads better than a larger one that does.
     */
    private val MAP_X = 530f
    private val MAP_Y = 60f
    private val MAP_S = 65f
    /** The plate's mount: the maze square plus its margins. The deeper top margin holds the N. */
    private val PL_X0 = MAP_X - 5f
    private val PL_X1 = MAP_X + MAP_S + 5f
    private val PL_Y0 = MAP_Y - 12f
    private val PL_Y1 = MAP_Y + MAP_S + 5f
    /** A patrolling Recognizer nearer than this (about two cells) shows on the plate; further, it does not. */
    private val MAP_NEAR = 18f
    /** Inside this the Bit's true position appears; outside, only its bearing. */
    private val MAP_BIT_NEAR = 20f
    /** The bearing caret rides this far inside the plate's rim, so it never lands on the frame. */
    private val MAP_INSET = 7f
    private var mapDrawn = false

    /**
     * The nav plate is an instrument bolted OVER the periscope, not a transparency. Left alone, the
     * arena's own wall edges — long, bright, full-height strokes — run straight through it and it
     * stops being glanceable at exactly the moment you need it. So between the world pass and the
     * HUD pass, scissor the plate's rectangle back to black. It costs no light and adds no fill:
     * black is the one value a waveguide renders as "not there", so the plate reads as a small
     * quiet window in the sight rather than a bright panel pasted over it.
     */
    private fun clearPlate(eye: Int, vw: Int) {
        if (!mapDrawn) return
        val sx = vw / 640f; val sy = height / 480f
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor((eye * vw + PL_X0 * sx).toInt(), (height - PL_Y1 * sy).toInt(),
            ((PL_X1 - PL_X0) * sx).toInt() + 1, ((PL_Y1 - PL_Y0) * sy).toInt() + 1)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    private fun buildHud() {
        hud.reset()
        mapDrawn = false
        // The sight only fails during the tank's own derez, and it fails on a curve: a quarter of a
        // second of nothing (the beat where you register what happened), then a steady collapse that
        // is total well before GAME OVER, so the ending arrives on a clean screen.
        glitch = if (game.state == State.DYING) ((game.stateT - 0.25f) / 2.35f).coerceIn(0f, 1f) else 0f
        glitchSeed = (game.stateT * 14f).toInt()
        buildBezel()
        when (game.state) {
            State.TITLE -> buildTitleHud()
            State.PLAY, State.WAVE_CLEAR, State.DYING -> { buildPlayHud(); buildMinimap() }
            State.GAME_OVER -> { buildPlayHud(); buildGameOver() }
        }
        if (game.menuOpen) buildMenu()
    }

    /** The cabinet: an outer frame and the two side dials of the tank sight, dim so they read as bezel. */
    private fun buildBezel() {
        color(0.75f, 0.85f, 0.8f, 0.30f); rect(6f, 6f, 634f, 474f)
        color(0.75f, 0.85f, 0.8f, 0.16f); rect(14f, 14f, 626f, 466f)
        for (cx in floatArrayOf(28f, 612f)) {
            color(0.8f, 0.9f, 0.85f, 0.35f)
            hl(cx, 62f, cx, 418f); circle(cx, 62f, 7f, 10); circle(cx, 418f, 7f, 10)
            circle(cx, 240f, 15f, 20); hl(cx - 22f, 240f, cx + 22f, 240f); hl(cx, 218f, cx, 262f)
            color(1f, 0.25f, 0.2f, 0.75f); circle(cx, 240f, 5f, 12)
        }
    }

    private fun buildTitleHud() {
        val t = game.time
        val pulse = 0.8f + 0.2f * sin(t * 2.5f)
        color(GREEN[0], GREEN[1], GREEN[2], pulse); textC("X3 PARANOIDS", 320f, 92f, 6.4f)
        color(0.7f, 0.95f, 0.8f, 0.75f); textC("A TANK. A MAZE. THE RECOGNIZERS.", 320f, 122f, 2.0f)
        color(0.75f, 0.85f, 0.8f, 0.35f); hl(150f, 136f, 490f, 136f)
        val li = game.introLine
        if (li in Game.INTRO_TEXT.indices) {
            val s = Game.INTRO_TEXT[li]
            color(0.55f, 1f, 0.7f, 0.95f)
            if (StrokeFont.width(s, 2.2f) > 560f) {
                val cut = s.lastIndexOf(' ', s.length / 2 + 6).let { if (it < 0) s.length / 2 else it }
                textC(s.substring(0, cut), 320f, 336f, 2.2f); textC(s.substring(cut + 1), 320f, 362f, 2.2f)
            } else textC(s, 320f, 348f, 2.2f)
        } else if (li >= Game.INTRO_TEXT.size) {
            color(0.55f, 1f, 0.7f, 0.6f); textC("END OF LINE.", 320f, 348f, 2.2f)
        }
        val tapA = if (game.showTap) 0.55f + 0.45f * abs(sin(t * 3f)) else 0.25f
        color(1f, 0.9f, 0.4f, tapA); textC("TAP TO PLAY", 320f, 420f, 3f)
        color(0.7f, 0.95f, 0.8f, 0.6f)
        text("HIGH ${store.highScore}", 56f, 452f, 2.2f); textR("BEST WAVE ${store.bestWave}", 584f, 452f, 2.2f)
        color(0.55f, 0.7f, 0.65f, 0.5f)
        textC("HEAD LOOKS   SWIPE UP/DOWN DRIVES", 320f, 392f, 1.6f)
        textC("LEFT/RIGHT TURNS 90   TAP FIRES", 320f, 410f, 1.6f)
    }

    private fun buildPlayHud() {
        val t = game.time
        val lock = game.lockedOn
        val blink = 0.5f + 0.5f * sin(t * 9f)
        var r = GREEN[0]; var g = GREEN[1]; var b = GREEN[2]
        if (lock) { r += (1f - r) * blink * 0.9f; g -= g * blink * 0.8f; b -= b * blink * 0.6f }
        val inv = if (game.invuln > 0f) 0.45f + 0.55f * abs(sin(t * 14f)) else 1f
        color(r, g, b, 0.9f * inv)
        // outer sight brackets
        hl(120f, 70f, 195f, 70f); hl(120f, 70f, 120f, 118f)
        hl(520f, 70f, 445f, 70f); hl(520f, 70f, 520f, 118f)
        hl(120f, 410f, 195f, 410f); hl(120f, 410f, 120f, 362f)
        hl(520f, 410f, 445f, 410f); hl(520f, 410f, 520f, 362f)
        hl(320f, 70f, 320f, 96f); hl(320f, 410f, 320f, 384f)
        hl(120f, 240f, 152f, 240f); hl(520f, 240f, 488f, 240f)
        // inner chevrons converging on the target
        hl(205f, 138f, 248f, 181f); hl(435f, 138f, 392f, 181f); hl(205f, 342f, 248f, 299f); hl(435f, 342f, 392f, 299f)
        // centre box ticks
        hl(288f, 214f, 304f, 214f); hl(288f, 214f, 288f, 226f); hl(352f, 214f, 336f, 214f); hl(352f, 214f, 352f, 226f)
        hl(288f, 266f, 304f, 266f); hl(288f, 266f, 288f, 254f); hl(352f, 266f, 336f, 266f); hl(352f, 266f, 352f, 254f)
        // readouts
        color(GREEN[0], GREEN[1], GREEN[2], 0.95f)
        text("RECOGNIZERS ${game.recognizersLeft}", 52f, 46f, 2.2f)
        textC(game.timerText(), 320f, 48f, 3.6f)
        text("SCORE ${game.score}", 52f, 455f, 2.4f)
        textR("LIVES ${game.lives}", 588f, 455f, 2.4f)
        textC(game.objectiveText(), 320f, 449f, 2.4f)
        // wave progress: dotted bar
        val prog = game.waveProgress()
        for (i in 0 until 14) {
            val x = 252f + i * 10f
            val on = i < (prog * 14f + 0.5f).toInt()
            color(GREEN[0], GREEN[1], GREEN[2], if (on) 0.95f else 0.22f); hl(x, 465f, x + 6f, 465f)
        }
        if (lock) { color(1f, 0.35f, 0.25f, blink); textC("WARNING", 320f, 68f, 1.8f) }
        // damage: red frame
        if (game.damageFlash > 0f) {
            color(1f, 0.2f, 0.15f, game.damageFlash * 0.85f)
            rect(24f, 24f, 616f, 456f); rect(30f, 30f, 610f, 450f); rect(36f, 36f, 604f, 444f)
        }
        when (game.state) {
            State.WAVE_CLEAR -> {
                color(GREEN[0], GREEN[1], GREEN[2], 0.95f); textC("WAVE ${game.wave} CLEARED", 320f, 205f, 4f)
                color(1f, 0.9f, 0.4f, 0.9f); textC(game.bonusText, 320f, 245f, 2.6f)
            }
            State.DYING -> { color(1f, 0.3f, 0.25f, 0.6f + 0.4f * abs(sin(t * 12f))); textC("DEREZZED", 320f, 215f, 5f) }
            else -> {}
        }
    }

    /**
     * The nav plate — a small top-down wireframe of the arena, bolted into the sight's TOP-right
     * corner where it covers nothing: under the RECOGNIZERS / timer row, clear of the top-right
     * bracket (x > 520) and of the bezel's right-hand dial and its cap (x < 605), and high enough
     * that its whole body sits well above the horizon and the floor grid.
     *
     * NORTH-UP, deliberately. The hull only ever faces four ways, so the usual argument for a
     * track-up map — that mentally rotating an arbitrary angle is hard — does not apply here: the
     * rotation is always 0/90/180/270 and the chevron states it outright. What north-up buys is
     * worth more. The arena survives three waves, so a fixed plate is a plate you LEARN, and on a
     * see-through waveguide motion is the loudest channel there is — a track-up map would spin the
     * whole plate through every quarter turn, at the exact moment the world is already spinning.
     * Held still, the plate never moves on its own, so anything that DOES move on it means
     * something: a Recognizer walked. Map-up is the bearing you spawn on and the one a triple-tap
     * re-centre returns you to; the N above the top edge names it outright.
     *
     * It is a THREAT display, not a map of everything. Walls and the tank always; a Recognizer only
     * once it is hunting you (it has line of sight, so it can already shoot you — you have earned
     * the right to know where from) or once it strays within [MAP_NEAR], about two cells, which is
     * "something is around the corner" and lets you get the drop on it. Sleeping patrols across the
     * maze stay off the plate, so the sweep, and the dread of it, survive intact. The Bit is a
     * BEARING only — an arrow on the rim — until you are within [MAP_BIT_NEAR]; a bearing through a
     * maze of right angles is a problem, not an answer, so FIND THE BIT still means find it.
     */
    private fun buildMinimap() {
        if (!store.minimap) return
        mapDrawn = true
        val m = game.maze
        val s = MAP_S / max(m.width, m.depth)
        val x1 = MAP_X + MAP_S; val y1 = MAP_Y + MAP_S
        fun mx(x: Float) = MAP_X + x * s
        fun my(z: Float) = MAP_Y + z * s

        // The arena's own boundary is the plate's frame; interior walls are dimmer still, because
        // they are structure and not signal. Both take the wave tint, so the plate ages with the maze.
        val tint = game.wallTint()
        for (w in m.walls) {
            val boundary = abs(w.x1 - w.x0) > Maze.CELL * 1.5f || abs(w.z1 - w.z0) > Maze.CELL * 1.5f
            color(tint[0], tint[1], tint[2], if (boundary) 0.62f else 0.38f)
            hl(mx(w.x0), my(w.z0), mx(w.x1), my(w.z1))
        }

        // The mount: four corner brackets in the bezel's colour — the same shorthand the sight
        // itself uses, so the plate reads as another instrument on the same panel. Eight short
        // strokes, and they are what stops the scissored black square reading as a hole punched in
        // the world. A full frame here would be a bright box in the corner of a see-through display;
        // corners give the same "this is a thing" for a quarter of the light. Longer and brighter
        // than they began: up in the corner the plate has busy wall-tops running past both sides of
        // it, and the mount is the only thing saying where the instrument ends and the arena starts.
        color(0.8f, 0.9f, 0.85f, 0.45f)
        val arm = 11f
        hl(PL_X0, PL_Y0, PL_X0 + arm, PL_Y0); hl(PL_X0, PL_Y0, PL_X0, PL_Y0 + arm)
        hl(PL_X1, PL_Y0, PL_X1 - arm, PL_Y0); hl(PL_X1, PL_Y0, PL_X1, PL_Y0 + arm)
        hl(PL_X0, PL_Y1, PL_X0 + arm, PL_Y1); hl(PL_X0, PL_Y1, PL_X0, PL_Y1 - arm)
        hl(PL_X1, PL_Y1, PL_X1 - arm, PL_Y1); hl(PL_X1, PL_Y1, PL_X1, PL_Y1 - arm)
        // North, named rather than hinted. A lone tick above the rim is indistinguishable from a
        // wall edge poking past it, and a fixed plate is worth nothing if you cannot trust its up.
        color(0.75f, 0.9f, 0.85f, 0.5f); text("N", (MAP_X + x1) * 0.5f - 2.2f, MAP_Y - 4f, 1.1f)

        val tx = mx(game.px); val ty = my(game.pz)

        // Recognizers: squares, so they never read as the Bit's diamond at this size. Colour runs the
        // same patrol-green→hunting-red lerp as the world models, so the plate and the view agree.
        for (r in game.recognizers) {
            if (r.hp <= 0) continue
            val threat = r.hunting || r.alert > 0.15f
            if (!threat && hypot(game.px - r.x, game.pz - r.z) > MAP_NEAR) continue
            val ex = mx(r.x); val ey = my(r.z)
            val h = if (threat) 2.8f else 2.2f
            val al = if (threat) 0.70f + 0.30f * (0.5f + 0.5f * sin(game.time * 6f)) else 0.55f
            color(0.25f + 0.75f * r.alert, 1f - 0.75f * r.alert, 0.45f - 0.25f * r.alert, al)
            rect(ex - h, ey - h, ex + h, ey + h)
            // A spur toward the tank on the frames it actually has the shot. This is the plate
            // earning its keep: WARNING already tells you that something has you, the spur tells you
            // WHERE FROM — the difference between backing away blind and stepping behind a wall.
            // It also makes breaking line of sight a move you can see working.
            if (r.hasLos) {
                var dx = tx - ex; var dy = ty - ey
                val dl = hypot(dx, dy).coerceAtLeast(0.001f); dx /= dl; dy /= dl
                hl(ex + dx * (h + 1.5f), ey + dy * (h + 1.5f), ex + dx * (h + 7f), ey + dy * (h + 7f))
            }
        }

        // The Bit: a diamond once you are close, a rim caret on its bearing until then.
        if (game.bitActive) {
            color(0.4f, 0.85f, 1f, 0.9f)
            if (hypot(game.px - game.bitX, game.pz - game.bitZ) <= MAP_BIT_NEAR) {
                val cx = mx(game.bitX); val cy = my(game.bitZ); val h = 3.4f
                hl(cx, cy - h, cx + h, cy); hl(cx + h, cy, cx, cy + h); hl(cx, cy + h, cx - h, cy); hl(cx - h, cy, cx, cy - h)
            } else {
                var dx = mx(game.bitX) - tx; var dy = my(game.bitZ) - ty
                val dl = hypot(dx, dy).coerceAtLeast(0.001f); dx /= dl; dy /= dl
                // Walk the bearing out to the rim, held [MAP_INSET] inside it. Riding the rim exactly
                // put the caret on the boundary wall, where it read as a smudge on the frame.
                val bx0 = MAP_X + MAP_INSET; val bx1 = x1 - MAP_INSET
                val by0 = MAP_Y + MAP_INSET; val by1 = y1 - MAP_INSET
                var t = MAP_S * 2f
                if (dx > 1e-4f) t = min(t, (bx1 - tx) / dx) else if (dx < -1e-4f) t = min(t, (bx0 - tx) / dx)
                if (dy > 1e-4f) t = min(t, (by1 - ty) / dy) else if (dy < -1e-4f) t = min(t, (by0 - ty) / dy)
                // the tank itself can be inside that margin, which makes t negative — pin it in
                val ex = (tx + dx * max(t, 0f)).coerceIn(bx0, bx1)
                val ey = (ty + dy * max(t, 0f)).coerceIn(by0, by1)
                val nx = -dy; val ny = dx
                hl(ex - dx * 6f, ey - dy * 6f, ex, ey)
                hl(ex, ey, ex - dx * 3.6f + nx * 2.4f, ey - dy * 3.6f + ny * 2.4f)
                hl(ex, ey, ex - dx * 3.6f - nx * 2.4f, ey - dy * 3.6f - ny * 2.4f)
            }
        }

        // The tank, LAST so nothing can bury it: when a Recognizer is standing on top of you the
        // plate must still answer "where am I" first. The chevron follows game.yaw — head plus hull
        // — because that is the ONE true heading: where a forward swipe drives, where the cannon
        // points, and where you are looking are all the same ray. Sized to sit inside one cell, so a
        // marker beside it is legible as a separate thing.
        val fx = sin(game.yaw); val fy = -cos(game.yaw)     // plate-space forward (screen y is down)
        val rx = -fy; val ry = fx                            // plate-space right
        color(0.85f, 1f, 0.9f, 1f)
        val hx = tx + fx * 5.5f; val hy = ty + fy * 5.5f
        val bx = tx - fx * 1.2f; val by = ty - fy * 1.2f
        val lx = tx - fx * 2.8f - rx * 3.4f; val ly = ty - fy * 2.8f - ry * 3.4f
        val qx = tx - fx * 2.8f + rx * 3.4f; val qy = ty - fy * 2.8f + ry * 3.4f
        hl(hx, hy, lx, ly); hl(lx, ly, bx, by); hl(bx, by, qx, qy); hl(qx, qy, hx, hy)
    }

    private fun buildGameOver() {
        val t = game.stateT
        color(1f, 0.35f, 0.25f, 0.95f); textC("GAME OVER", 320f, 175f, 5.5f)
        color(GREEN[0], GREEN[1], GREEN[2], 0.95f); textC("SCORE ${game.score}", 320f, 228f, 3f)
        if (game.isNewHigh) { color(1f, 0.9f, 0.4f, 0.6f + 0.4f * abs(sin(t * 6f))); textC("NEW HIGH SCORE", 320f, 266f, 2.6f) }
        else { color(0.7f, 0.95f, 0.8f, 0.8f); textC("HIGH SCORE ${store.highScore}", 320f, 266f, 2.6f) }
        color(0.7f, 0.95f, 0.8f, 0.8f); textC("WAVE ${game.wave}   ${game.timerText()}", 320f, 300f, 2.2f)
        if (t > 1.2f) { color(1f, 0.9f, 0.4f, 0.5f + 0.5f * abs(sin(t * 3f))); textC("TAP TO CONTINUE", 320f, 350f, 2.6f) }
    }

    private fun buildMenu() {
        color(0f, 0f, 0f, 0f)
        color(GREEN[0], GREEN[1], GREEN[2], 0.9f); rect(150f, 86f, 490f, 404f); rect(154f, 90f, 486f, 400f)
        textC("SETTINGS", 320f, 122f, 3f)
        for ((i, item) in game.menuItems.withIndex()) {
            val y = 162f + i * 30f
            val sel = i == game.menuSel
            color(GREEN[0], GREEN[1], GREEN[2], if (sel) 1f else 0.55f)
            if (sel) text(">", 162f, y, 2.2f)
            text(item, 180f, y, 2.2f)
            val v = game.menuValue(i)
            color(if (sel) 1f else 0.8f, if (sel) 0.92f else 0.95f, if (sel) 0.45f else 0.8f, if (sel) 1f else 0.6f)
            if (v.isNotEmpty()) textR(v, 470f, y, if (v.length > 12) 1.6f else 2.2f)
        }
        color(0.7f, 0.95f, 0.8f, 0.55f); textC("DOUBLE-TAP CLOSES   UP/DOWN MOVE   TAP ADJUSTS", 320f, 428f, 1.5f)
    }

    // ------------------------------------------------------------------ GL plumbing

    /**
     * A stream of coloured vertices, uploaded ONCE per frame and drawn as many times as the look
     * needs — the glow and core passes, and again for the second eye.
     *
     * It has to be a buffer object. Handed a client-side FloatBuffer, this driver re-copies and
     * re-validates the whole array on every single glDrawArrays, so the world's vertices were being
     * shipped four times a frame; densifying the near walls pushed that from ~15k to ~28k vertex
     * uploads and the frame rate fell from 60 to 42, on geometry a GPU of this class rasterises
     * without noticing. Uploading once per frame and drawing from VRAM puts it back — and makes the
     * cost of the wall infill roughly what it looks like it should be.
     */
    private inner class Batch(cap: Int) {
        private val buf: FloatBuffer = ByteBuffer.allocateDirect(cap * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private var n = 0
        private val max = cap
        private var vbo = 0
        private var live = false        // has this frame's content reached the buffer object yet
        val count get() = n
        /** The GL context went away; the name we were holding is meaningless now. */
        fun contextLost() { vbo = 0; live = false }
        fun reset() { buf.clear(); n = 0; live = false }
        fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
            if (n >= max) return
            buf.put(x); buf.put(y); buf.put(z); buf.put(r); buf.put(g); buf.put(b); buf.put(a); n++
        }
        fun draw(mode: Int) {
            if (n == 0) return
            if (vbo == 0) { val id = IntArray(1); GLES30.glGenBuffers(1, id, 0); vbo = id[0] }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            if (!live) { buf.position(0); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 28, buf, GLES30.GL_STREAM_DRAW); live = true }
            GLES30.glEnableVertexAttribArray(aPos); GLES30.glEnableVertexAttribArray(aColor)
            GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 28, 0)
            GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, 28, 12)
            GLES30.glDrawArrays(mode, 0, n)
            GLES30.glDisableVertexAttribArray(aPos); GLES30.glDisableVertexAttribArray(aColor)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.e("X3Paranoids", "shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, sh(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(p, sh(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) android.util.Log.e("X3Paranoids", "link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        private const val VERT = """#version 300 es
            uniform mat4 uMVP; uniform float uPointSize; uniform float uAlpha;
            in vec3 aPos; in vec4 aColor; out vec4 vColor;
            void main() { gl_Position = uMVP * vec4(aPos, 1.0); gl_PointSize = uPointSize; vColor = vec4(aColor.rgb, aColor.a * uAlpha); }"""
        private const val FRAG = """#version 300 es
            precision mediump float;
            uniform float uPoint; in vec4 vColor; out vec4 fragColor;
            void main() {
                float a = vColor.a;
                if (uPoint > 0.5) { vec2 d = gl_PointCoord - vec2(0.5); float r = length(d) * 2.0; a *= smoothstep(1.0, 0.2, r); }
                fragColor = vec4(vColor.rgb * a, a);
            }"""
    }
}
