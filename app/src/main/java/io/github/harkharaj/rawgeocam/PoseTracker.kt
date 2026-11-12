package io.github.harkharaj.rawgeocam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Keeps a short history of device orientation (so the pose can be matched to the exact exposure time
 * of a frame) plus the latest GPS / network location.
 *
 * Orientation comes from TYPE_ROTATION_VECTOR (gyro + accelerometer + magnetometer fusion, referenced to
 * magnetic north). If a phone has no rotation-vector sensor, accelerometer + magnetometer are used.
 */
class PoseTracker(context: Context) : SensorEventListener, LocationListener {

    class Snapshot(
        /** R_world(magnetic ENU)_from_device, row-major, or null if no orientation sample exists. */
        val rWorldMagFromDevice: DoubleArray?,
        val orientationSource: String,
        /** Sample time minus target time, milliseconds. */
        val sampleOffsetMs: Double?,
        val timeReference: String,
        val headingAccuracyDeg: Double?,
        val rotationAccuracyStatus: Int,
        val magnetometerAccuracyStatus: Int,
        val angularSpeedDps: Double?,
        val location: Location?,
        val locationAgeS: Double?,
        val declinationDeg: Double?,
    )

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val orientationSource: String = when {
        rotationSensor != null -> "rotation_vector"
        accelSensor != null && magSensor != null -> "accelerometer_magnetometer"
        else -> "none"
    }

    // Ring buffers, guarded by [lock]. Sensor timestamps share the SystemClock.elapsedRealtimeNanos() base.
    private val lock = Any()
    private val times = LongArray(CAPACITY)
    private val mats = FloatArray(CAPACITY * 9)
    private val headingAcc = FloatArray(CAPACITY)
    private var next = 0
    private var count = 0
    private val gyroTimes = LongArray(CAPACITY)
    private val gyroSpeed = FloatArray(CAPACITY)
    private var gyroNext = 0
    private var gyroCount = 0

    // Only touched on the sensor thread.
    private val tmpR = FloatArray(9)
    private val accelVals = FloatArray(3)
    private val magVals = FloatArray(3)
    private var haveAccel = false

    @Volatile var rotationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        private set
    @Volatile var magnetometerAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        private set
    @Volatile private var lastGps: Location? = null
    @Volatile private var lastNetwork: Location? = null

    private var thread: HandlerThread? = null

    fun start() {
        if (thread != null) return
        val t = HandlerThread("RawGeoCam-sensors").apply { start() }
        thread = t
        val h = Handler(t.looper)
        val rate = SensorManager.SENSOR_DELAY_GAME
        rotationSensor?.let { sensorManager.registerListener(this, it, rate, h) }
        if (rotationSensor == null) accelSensor?.let { sensorManager.registerListener(this, it, rate, h) }
        // Registered even with a rotation vector: it reports the compass calibration status.
        magSensor?.let { sensorManager.registerListener(this, it, rate, h) }
        gyroSensor?.let { sensorManager.registerListener(this, it, rate, h) }
        startLocation(t)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        thread?.quitSafely()
        thread = null
        synchronized(lock) {
            next = 0; count = 0; gyroNext = 0; gyroCount = 0
        }
        haveAccel = false
    }

    private fun hasLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocation(t: HandlerThread) {
        if (!hasLocationPermission()) return
        val providers = locationManager.allProviders
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (p !in providers) continue
            try {
                locationManager.getLastKnownLocation(p)?.let { onLocationChanged(it) }
                locationManager.requestLocationUpdates(p, 500L, 0f, this, t.looper)
            } catch (_: Exception) {
                // SecurityException when only coarse location is granted for GPS, or provider disabled.
            }
        }
    }

    // ---------------- sensors ----------------

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(tmpR, e.values)
                rotationAccuracy = e.accuracy
                push(e.timestamp, tmpR, if (e.values.size > 4) e.values[4] else -1f)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(e.values, 0, accelVals, 0, 3)
                haveAccel = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(e.values, 0, magVals, 0, 3)
                magnetometerAccuracy = e.accuracy
                if (rotationSensor == null && haveAccel &&
                    SensorManager.getRotationMatrix(tmpR, null, accelVals, magVals)
                ) {
                    push(e.timestamp, tmpR, -1f)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val v = e.values
                val dps = Math.toDegrees(sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())).toFloat()
                synchronized(lock) {
                    gyroTimes[gyroNext] = e.timestamp
                    gyroSpeed[gyroNext] = dps
                    gyroNext = (gyroNext + 1) % CAPACITY
                    if (gyroCount < CAPACITY) gyroCount++
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        when (sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> rotationAccuracy = accuracy
            Sensor.TYPE_MAGNETIC_FIELD -> magnetometerAccuracy = accuracy
        }
    }

    private fun push(t: Long, r: FloatArray, headingAccuracyRad: Float) {
        synchronized(lock) {
            times[next] = t
            System.arraycopy(r, 0, mats, next * 9, 9)
            headingAcc[next] = headingAccuracyRad
            next = (next + 1) % CAPACITY
            if (count < CAPACITY) count++
        }
    }

    // ---------------- location ----------------

    override fun onLocationChanged(location: Location) {
        if (location.provider == LocationManager.GPS_PROVIDER) lastGps = location else lastNetwork = location
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    /** A GPS fix up to 10 s old wins; otherwise the newest fix from any provider. */
    fun currentLocation(): Location? {
        val gps = lastGps
        val net = lastNetwork
        if (gps != null && locationAgeSeconds(gps) <= 10.0) return gps
        return listOfNotNull(gps, net).maxByOrNull { it.elapsedRealtimeNanos }
    }

    fun locationAgeSeconds(l: Location): Double =
        (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1e9

    fun declinationAt(l: Location): Double =
        GeomagneticField(
            l.latitude.toFloat(),
            l.longitude.toFloat(),
            (if (l.hasAltitude()) l.altitude else 0.0).toFloat(),
            System.currentTimeMillis(),
        ).declination.toDouble()

    // ---------------- queries ----------------

    fun latestWorldMagFromDevice(): DoubleArray? = synchronized(lock) {
        if (count == 0) {
            null
        } else {
            val i = (next - 1 + CAPACITY) % CAPACITY
            DoubleArray(9) { mats[i * 9 + it].toDouble() }
        }
    }

    /** Orientation sample nearest to [targetRealtimeNs] (elapsedRealtimeNanos base) plus the current location. */
    fun snapshotAt(targetRealtimeNs: Long, timeReference: String): Snapshot {
        var r: DoubleArray? = null
        var offsetMs: Double? = null
        var hAcc: Double? = null
        var gyro: Double? = null
        var reference = timeReference
        synchronized(lock) {
            var i = PoseMath.nearestIndex(times, count, targetRealtimeNs)
            if (i >= 0 && kotlin.math.abs(times[i] - targetRealtimeNs) > CLOCK_MISMATCH_NS) {
                // Sensor and camera clocks disagree on this phone: fall back to the newest sample.
                i = (next - 1 + CAPACITY) % CAPACITY
                reference = "$timeReference (clock mismatch: newest sample used)"
            }
            if (i >= 0) {
                r = DoubleArray(9) { mats[i * 9 + it].toDouble() }
                offsetMs = (times[i] - targetRealtimeNs) / 1e6
                val a = headingAcc[i]
                hAcc = if (a >= 0f) Math.toDegrees(a.toDouble()) else null
            }
            val g = PoseMath.nearestIndex(gyroTimes, gyroCount, targetRealtimeNs)
            if (g >= 0) gyro = gyroSpeed[g].toDouble()
        }
        val loc = currentLocation()
        return Snapshot(
            rWorldMagFromDevice = r,
            orientationSource = orientationSource,
            sampleOffsetMs = offsetMs,
            timeReference = reference,
            headingAccuracyDeg = hAcc,
            rotationAccuracyStatus = rotationAccuracy,
            magnetometerAccuracyStatus = magnetometerAccuracy,
            angularSpeedDps = gyro,
            location = loc,
            locationAgeS = loc?.let { locationAgeSeconds(it) },
            declinationDeg = loc?.let { declinationAt(it) },
        )
    }

    companion object {
        private const val CAPACITY = 512 // ~10 s at SENSOR_DELAY_GAME
        private const val CLOCK_MISMATCH_NS = 1_000_000_000L

        fun accuracyName(status: Int): String = when (status) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            SensorManager.SENSOR_STATUS_NO_CONTACT -> "NO_CONTACT"
            else -> "UNKNOWN($status)"
        }
    }
}
