package io.github.harkharaj.rawgeocam

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry with no Android dependencies, so it can be tested on a plain JVM.
 *
 * Frames
 *  - Device (Android sensor frame): X right, Y toward the top of the screen, Z out of the screen,
 *    with the phone held in its natural (portrait) orientation.
 *  - World: East-North-Up (ENU). "Magnetic" ENU points N at magnetic north, "true" ENU at true north.
 *  - Camera (pinhole / OpenCV axes): x along increasing pixel column u, y along increasing pixel row v,
 *    z along the optical axis into the scene.
 *      "sensor" camera frame  = the raw DNG pixel grid (the frame the Camera2 intrinsics are given in);
 *      "display" camera frame = the same image after applying the DNG/EXIF orientation tag.
 *
 * All 3x3 matrices are row-major DoubleArray(9). R_ab maps a vector expressed in frame b into frame a.
 */
object PoseMath {

    data class Angles(
        /** Bearing of the optical axis, degrees clockwise from north, [0, 360). */
        val headingDeg: Double,
        /** Elevation of the optical axis above the horizon, degrees, + = looking up. */
        val pitchDeg: Double,
        /**
         * Rotation of the image about the optical axis, degrees in (-180, 180]: the angle from the
         * level "right" direction to the image +x axis, positive when +x tips toward the ground
         * (the scene then appears rotated counter-clockwise in the image).
         */
        val rollDeg: Double,
        /** True when the optical axis is within 5 degrees of straight up/down (heading and roll unstable). */
        val nearVertical: Boolean,
    )

    // ---------- small linear algebra ----------

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[3 * i + k] * b[3 * k + j]
            r[3 * i + j] = s
        }
        return r
    }

    fun transpose(a: DoubleArray) = doubleArrayOf(
        a[0], a[3], a[6],
        a[1], a[4], a[7],
        a[2], a[5], a[8],
    )

    fun col(a: DoubleArray, j: Int) = doubleArrayOf(a[j], a[3 + j], a[6 + j])

    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    fun fromColumns(x: DoubleArray, y: DoubleArray, z: DoubleArray) = doubleArrayOf(
        x[0], y[0], z[0],
        x[1], y[1], z[1],
        x[2], y[2], z[2],
    )

    fun normDeg360(d: Double): Double {
        val r = d % 360.0
        return if (r < 0) r + 360.0 else r
    }

    fun normDeg180(d: Double): Double {
        val r = normDeg360(d)
        return if (r > 180.0) r - 360.0 else r
    }

    /** Exact cos/sin for multiples of 90 degrees. */
    private fun cosSin(deg: Int): Pair<Double, Double> = when (((deg % 360) + 360) % 360) {
        0 -> 1.0 to 0.0
        90 -> 0.0 to 1.0
        180 -> -1.0 to 0.0
        270 -> 0.0 to -1.0
        else -> Math.toRadians(deg.toDouble()).let { cos(it) to sin(it) }
    }

    // ---------- frames ----------

    /**
     * R_device_from_camera for a BACK-facing camera with the given SENSOR_ORIENTATION
     * (clockwise rotation that makes the sensor image upright in the device's natural orientation).
     * The optical axis is device -Z. For orientation 0 the image +x is device +X and +y is device -Y;
     * other orientations rotate that pair about the optical axis.
     */
    fun deviceFromBackCamera(sensorOrientationDeg: Int): DoubleArray {
        val (c, s) = cosSin(sensorOrientationDeg)
        return doubleArrayOf(
            c, -s, 0.0,
            -s, -c, 0.0,
            0.0, 0.0, -1.0,
        )
    }

    /** Rotation about Up by -declination: maps magnetic-ENU vectors into true-ENU. Declination is + east. */
    fun trueFromMagnetic(declinationDeg: Double): DoubleArray {
        val a = Math.toRadians(-declinationDeg)
        val c = cos(a)
        val s = sin(a)
        return doubleArrayOf(
            c, -s, 0.0,
            s, c, 0.0,
            0.0, 0.0, 1.0,
        )
    }

    /**
     * R_sensorCam_from_displayCam for an image that is shown after rotating it [rotCwDeg] clockwise
     * (EXIF orientation 6 = 90, 3 = 180, 8 = 270).
     */
    fun sensorCamFromDisplayCam(rotCwDeg: Int): DoubleArray {
        val (c, s) = cosSin(rotCwDeg)
        // columns: display +x = c*x_s - s*y_s, display +y = s*x_s + c*y_s, z unchanged
        return doubleArrayOf(
            c, s, 0.0,
            -s, c, 0.0,
            0.0, 0.0, 1.0,
        )
    }

    // ---------- angles ----------

    fun anglesOf(rWorldFromCam: DoubleArray): Angles {
        val x = col(rWorldFromCam, 0)
        val d = col(rWorldFromCam, 2)
        val heading = normDeg360(Math.toDegrees(atan2(d[0], d[1])))
        val pitch = Math.toDegrees(asin(d[2].coerceIn(-1.0, 1.0)))
        val hn = hypot(d[0], d[1])
        val roll = if (hn < 1e-9) {
            0.0
        } else {
            val rightLevel = doubleArrayOf(d[1] / hn, -d[0] / hn, 0.0) // d x Up, normalised
            val downLevel = cross(d, rightLevel)
            normDeg180(Math.toDegrees(atan2(dot(x, downLevel), dot(x, rightLevel))))
        }
        return Angles(heading, pitch, roll, abs(pitch) > 85.0)
    }

    /** Inverse of [anglesOf]: R_world_from_camera from heading / pitch / roll in degrees. */
    fun worldFromCamera(headingDeg: Double, pitchDeg: Double, rollDeg: Double): DoubleArray {
        val h = Math.toRadians(headingDeg)
        val p = Math.toRadians(pitchDeg)
        val r = Math.toRadians(rollDeg)
        val d = doubleArrayOf(cos(p) * sin(h), cos(p) * cos(h), sin(p))
        val rightLevel = doubleArrayOf(cos(h), -sin(h), 0.0)
        val downLevel = cross(d, rightLevel)
        val x = DoubleArray(3) { cos(r) * rightLevel[it] + sin(r) * downLevel[it] }
        val y = DoubleArray(3) { -sin(r) * rightLevel[it] + cos(r) * downLevel[it] }
        return fromColumns(x, y, d)
    }

    /** Clockwise display rotation (0/90/180/270) that brings the image closest to level. */
    fun displayRotationForRoll(sensorRollDeg: Double): Int =
        ((((sensorRollDeg / 90.0).roundToInt() * 90) % 360) + 360) % 360

    fun exifOrientationForRotation(rotCwDeg: Int): Int = when (((rotCwDeg % 360) + 360) % 360) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    // ---------- misc ----------

    /** Unit quaternion [w, x, y, z] of a rotation matrix. */
    fun quaternionWxyz(m: DoubleArray): DoubleArray {
        val tr = m[0] + m[4] + m[8]
        val q: DoubleArray
        if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2
            q = doubleArrayOf(0.25 * s, (m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s)
        } else if (m[0] > m[4] && m[0] > m[8]) {
            val s = sqrt(1.0 + m[0] - m[4] - m[8]) * 2
            q = doubleArrayOf((m[7] - m[5]) / s, 0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s)
        } else if (m[4] > m[8]) {
            val s = sqrt(1.0 + m[4] - m[0] - m[8]) * 2
            q = doubleArrayOf((m[2] - m[6]) / s, (m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s)
        } else {
            val s = sqrt(1.0 + m[8] - m[0] - m[4]) * 2
            q = doubleArrayOf((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s)
        }
        if (q[0] < 0) for (i in 0..3) q[i] = -q[i]
        return q
    }

    /**
     * Pinhole estimate [fx, fy, cx, cy, s] in pre-correction-active-array pixels, for devices that
     * do not publish LENS_INTRINSIC_CALIBRATION. Physical size covers the full pixel array.
     */
    fun estimateIntrinsics(
        focalMm: Double,
        physicalWidthMm: Double, physicalHeightMm: Double,
        pixelArrayWidth: Int, pixelArrayHeight: Int,
        activeWidth: Int, activeHeight: Int,
    ): DoubleArray {
        val fx = focalMm * pixelArrayWidth / physicalWidthMm
        val fy = focalMm * pixelArrayHeight / physicalHeightMm
        return doubleArrayOf(fx, fy, activeWidth / 2.0, activeHeight / 2.0, 0.0)
    }

    /** Index of the timestamp closest to [t], or -1 if [times] is empty. */
    fun nearestIndex(times: LongArray, count: Int, t: Long): Int {
        var best = -1
        var bestDt = Long.MAX_VALUE
        for (i in 0 until count) {
            val dt = abs(times[i] - t)
            if (dt < bestDt) {
                bestDt = dt
                best = i
            }
        }
        return best
    }
}
