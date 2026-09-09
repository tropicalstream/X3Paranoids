package com.x3paranoids.head

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * 3-DoF head look for the tank's periscope. TYPE_GAME_ROTATION_VECTOR (present on the X3 Pro as a
 * QTI hardware sensor) → rotation matrix → remapCoordinateSystem(AXIS_X, AXIS_Z) → getOrientation,
 * which is the recipe the shipped Everyday app proved on this exact hardware. Output is yaw/pitch in
 * radians relative to a recentre reference: yaw + = looking right, pitch + = looking up. Smoothed on
 * the GL thread (τ 35 ms) so the maze never jitters, with shortest-arc wrapping for yaw.
 */
class HeadTracker(ctx: Context) : SensorEventListener {

    companion object {
        /** How much of a head-down (or head-up) start pose a recentre will adopt: 20 degrees. */
        private const val PITCH_REF_MAX = 0.349f
        /** How far the periscope may look up or down once recentred (~51 degrees). */
        private const val PITCH_LIMIT = 0.9f
    }

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private var thread: HandlerThread? = null

    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)

    @Volatile private var rawYaw = 0f     // absolute azimuth, radians
    @Volatile private var rawPitch = 0f   // + = up
    @Volatile var hasData = false; private set
    var running = false; private set
    val available get() = sensor != null

    private var yaw0 = 0f
    private var pitch0 = 0f
    private var recentred = false
    /**
     * A recentre that was ASKED FOR BEFORE THE SENSOR HAD SPOKEN, still owed.
     *
     * [recentre] used to be a no-op when `hasData` was false, and on a cold start that is exactly
     * when it is called: the player taps START on the title screen a moment after the activity
     * resumed, [Game.startGame] calls through, and there is not yet a sample to take a reference
     * from. The old code got away with it only by accident — `update` recentres itself while
     * `recentred` is false — but `recentred` is cleared only in [start], so the SECOND game of a
     * session hit the real bug: the request was dropped and the run kept the previous game's
     * forward. The flag makes the deferral explicit and survives any number of games.
     */
    @Volatile private var pendingRecentre = false
    /** Smoothed logic angles (radians). */
    var yaw = 0f; private set
    var pitch = 0f; private set

    fun start() {
        if (running || sensor == null) return
        thread = HandlerThread("x3paranoids-head").also { it.start() }
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(thread!!.looper))
        running = true; hasData = false; recentred = false; pendingRecentre = false
        pitch0 = 0f
    }

    fun stop() {
        if (!running) return
        sm.unregisterListener(this)
        thread?.quitSafely(); thread = null
        running = false; hasData = false
        yaw = 0f; pitch = 0f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Head-worn: yaw about the device's up axis, pitch about its right axis (Everyday's remap).
        SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orient)
        rawYaw = orient[0]            // azimuth: clockwise (turning right) increases
        rawPitch = -orient[1]         // Android pitch is negative when looking up
        hasData = true
    }

    /**
     * Make the current head pose the maze's forward — BOTH AXES.
     *
     * Yaw was always zeroed here. Pitch was not: it was fed through raw and absolute, so the
     * horizon sat wherever the player's head happened to be pointing when they tapped. Tap while
     * looking down at the desk — which is what you do when you are reaching for a phone or reading
     * a keyboard — and you played the entire run with the floor grid halfway up the sight and the
     * maze sliding off the top of the display. The tap sets forward, and forward has two angles.
     *
     * THE CLAMP is why this is a reference and not just a subtraction. An unclamped pitch datum
     * lets a player bake an absurd rest pose into the run: recentre while staring at your shoes
     * and looking level would then read as sixty degrees UP, so the horizon really would be at
     * your feet and the game would be unplayable in the other direction. [PITCH_REF_MAX] bounds
     * how much of a bad head pose can be adopted, so the worst case is a horizon a few degrees
     * off rather than an inverted one — and someone who genuinely starts bent over gets most of
     * the correction and can look level for the rest.
     *
     * If the sensor has not produced a sample yet the request is REMEMBERED, not dropped.
     */
    fun recentre() { if (hasData) applyRecentre() else pendingRecentre = true }

    private fun applyRecentre() {
        yaw0 = rawYaw
        pitch0 = rawPitch.coerceIn(-PITCH_REF_MAX, PITCH_REF_MAX)
        recentred = true
        pendingRecentre = false
        // Land on the value `update` will converge to, so recentring never costs a visible lurch.
        yaw = 0f
        pitch = (rawPitch - pitch0).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
    }

    /** GL-thread smoothing. */
    fun update(dt: Float) {
        if (!running || !hasData) return
        if (pendingRecentre || !recentred) applyRecentre()
        var target = rawYaw - yaw0
        while (target > PI) target -= 2f * PI.toFloat()
        while (target < -PI) target += 2f * PI.toFloat()
        var d = target - yaw
        while (d > PI) d -= 2f * PI.toFloat()
        while (d < -PI) d += 2f * PI.toFloat()
        val a = 1f - exp(-dt / 0.035f)
        yaw += d * a
        while (yaw > PI) yaw -= 2f * PI.toFloat()
        while (yaw < -PI) yaw += 2f * PI.toFloat()
        val pt = (rawPitch - pitch0).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
        pitch += (pt - pitch) * a
        if (abs(pitch) < 1e-5f) pitch = 0f
    }
}
