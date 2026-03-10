package com.rayneo.client

// Ported unchanged from informalTechCode/RayNeo-Air-Series-3DoF-Scaffold
// Credit: https://github.com/informalTechCode/RayNeo-Air-Series-3DoF-Scaffold

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private data class Vec3(val x: Float, val y: Float, val z: Float) {
    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 { val n = length(); return if (n < 1e-6f) Vec3(0f,0f,0f) else Vec3(x/n, y/n, z/n) }
    fun cross(o: Vec3) = Vec3(y*o.z - z*o.y, z*o.x - x*o.z, x*o.y - y*o.x)
    operator fun plus(o: Vec3) = Vec3(x+o.x, y+o.y, z+o.z)
    operator fun minus(o: Vec3) = Vec3(x-o.x, y-o.y, z-o.z)
    operator fun times(s: Float) = Vec3(x*s, y*s, z*s)
}

private data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {
    operator fun times(o: Quaternion) = Quaternion(
        w*o.w - x*o.x - y*o.y - z*o.z,
        w*o.x + x*o.w + y*o.z - z*o.y,
        w*o.y - x*o.z + y*o.w + z*o.x,
        w*o.z + x*o.y - y*o.x + z*o.w
    )
    operator fun times(s: Float) = Quaternion(w*s, x*s, y*s, z*s)
    fun normalized(): Quaternion { val n = sqrt(w*w+x*x+y*y+z*z); return if (n < 1e-6f) IDENTITY else Quaternion(w/n,x/n,y/n,z/n) }
    fun conjugate() = Quaternion(w,-x,-y,-z)
    fun rotate(v: Vec3): Vec3 { val r = this * Quaternion(0f,v.x,v.y,v.z) * conjugate(); return Vec3(r.x,r.y,r.z) }
    fun integrateBodyRate(omega: Vec3, dt: Float): Quaternion {
        val qDot = (this * Quaternion(0f, omega.x, omega.y, omega.z)) * 0.5f
        return Quaternion(w + qDot.w*dt, x + qDot.x*dt, y + qDot.y*dt, z + qDot.z*dt)
    }
    companion object {
        val IDENTITY = Quaternion(1f,0f,0f,0f)
        fun fromAxisAngle(axis: Vec3, angleRad: Float): Quaternion {
            val n = axis.normalized(); val h = angleRad*0.5f
            return Quaternion(cos(h), n.x*sin(h), n.y*sin(h), n.z*sin(h)).normalized()
        }
    }
}

class OrientationFilter(imuRotationXDeg: Float) {
    private val worldUp = Vec3(0f, 1f, 0f)
    private val accelGain = 1.5f
    private val dpsToRad = (Math.PI.toFloat() / 180f)
    private val gravityMps2 = 9.81f
    private val stationaryAccelTol = 1.25f
    private val stationaryGyroRadPerSec = 0.18f
    private val gyroBiasUpdateHz = 0.5f
    private val imuRotation = Quaternion.fromAxisAngle(Vec3(1f,0f,0f), imuRotationXDeg * dpsToRad)

    private var q = Quaternion.IDENTITY
    private var gyroBias = Vec3(0f,0f,0f)
    private var lastTick100us = -1L
    private var lastRealtimeNs = 0L

    fun update(sample: RayNeoSensorSample): FloatArray {
        var accel = imuRotation.rotate(Vec3(sample.accelMps2[0], sample.accelMps2[1], sample.accelMps2[2]))
        var gyro  = imuRotation.rotate(Vec3(sample.gyroDps[0]*dpsToRad, sample.gyroDps[1]*dpsToRad, sample.gyroDps[2]*dpsToRad))

        val nowNs = System.nanoTime()
        var dt = 0.01f
        if (lastTick100us >= 0 && sample.deviceTick100us > lastTick100us)
            dt = (sample.deviceTick100us - lastTick100us).toFloat() * 1e-4f
        else if (lastRealtimeNs > 0 && nowNs > lastRealtimeNs)
            dt = (nowNs - lastRealtimeNs).toFloat() * 1e-9f
        if (dt < 0.001f || dt > 0.1f) dt = 0.01f
        lastTick100us = sample.deviceTick100us
        lastRealtimeNs = nowNs

        val accelNorm = accel.length()
        val stationary = kotlin.math.abs(accelNorm - gravityMps2) < stationaryAccelTol && gyro.length() < stationaryGyroRadPerSec
        if (stationary) {
            val alpha = minOf(1f, dt * gyroBiasUpdateHz)
            gyroBias = gyroBias * (1f - alpha) + gyro * alpha
        }
        gyro -= gyroBias

        if (accelNorm > 1e-3f) {
            val measuredUp = accel.normalized()
            val predictedUp = q.conjugate().rotate(worldUp).normalized()
            gyro += predictedUp.cross(measuredUp) * accelGain
        }

        q = q.integrateBodyRate(gyro, dt).normalized()
        return floatArrayOf(q.w, q.x, q.y, q.z)
    }
}
