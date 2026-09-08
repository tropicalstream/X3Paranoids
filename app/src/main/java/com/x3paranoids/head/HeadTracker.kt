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
    private var recentred = false
    /** Smoothed logic angles (radians). */
    var yaw = 0f; private set
    var pitch = 0f; private set

    fun start() {
        if (running || sensor == null) return
        thread = HandlerThread("x3paranoids-head").also { it.start() }
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(thread!!.looper))
        running = true; hasData = false; recentred = false
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

    /** Make the current heading the maze's forward. */
    fun recentre() { if (hasData) { yaw0 = rawYaw; recentred = true; yaw = 0f } }

    /** GL-thread smoothing. */
    fun update(dt: Float) {
        if (!running || !hasData) return
        if (!recentred) recentre()
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
        val pt = rawPitch.coerceIn(-0.9f, 0.9f)
        pitch += (pt - pitch) * a
        if (abs(pitch) < 1e-5f) pitch = 0f
    }
}
