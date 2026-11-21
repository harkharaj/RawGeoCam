package io.github.harkharaj.rawgeocam

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Slider value lists and labels for manual mode. Pure Kotlin so it can be unit-tested on a plain JVM. */
object ManualControls {

    private const val NS_PER_S = 1_000_000_000L

    /** Third-stop shutter speeds from 1/8000 s to 30 s, in nanoseconds. */
    private val STANDARD_SHUTTER_NS: LongArray = run {
        val fractions = intArrayOf(
            8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250,
            200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3,
        ).map { (NS_PER_S.toDouble() / it).roundToLong() }
        val seconds = doubleArrayOf(0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.0, 8.0, 10.0,
            13.0, 15.0, 20.0, 25.0, 30.0).map { (it * NS_PER_S).roundToLong() }
        (fractions + seconds).toLongArray()
    }

    private val STANDARD_ISO = intArrayOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
        2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600,
    )

    /** Standard shutter speeds inside [minNs, maxNs]; the range ends are added when they fall between stops. */
    fun shutterStops(minNs: Long, maxNs: Long): LongArray {
        val inside = STANDARD_SHUTTER_NS.filter { it in minNs..maxNs }.toMutableList()
        if (inside.isEmpty() || inside.first() > minNs * 1.05) inside.add(0, minNs)
        if (inside.last() < maxNs * 0.95) inside.add(maxNs)
        return inside.distinct().toLongArray()
    }

    fun isoStops(min: Int, max: Int): IntArray {
        val inside = STANDARD_ISO.filter { it in min..max }.toMutableList()
        if (inside.isEmpty() || inside.first() > min) inside.add(0, min)
        if (inside.last() < max) inside.add(max)
        return inside.distinct().toIntArray()
    }

    fun nearestIndex(values: LongArray, v: Long): Int {
        var best = 0
        for (i in values.indices) {
            // compare in log space: shutter speeds are perceived in stops
            if (abs(Math.log(values[i].toDouble() / v)) < abs(Math.log(values[best].toDouble() / v))) best = i
        }
        return best
    }

    fun nearestIndex(values: IntArray, v: Int): Int {
        var best = 0
        for (i in values.indices) {
            if (abs(Math.log(values[i].toDouble() / v)) < abs(Math.log(values[best].toDouble() / v))) best = i
        }
        return best
    }

    fun shutterLabel(ns: Long): String {
        val s = ns.toDouble() / NS_PER_S
        return if (s < 0.35) "1/${(1.0 / s).roundToInt()} s" else String.format(Locale.US, "%.1f s", s)
    }

    /** Focus slider: position 0..[steps] maps linearly onto 0 (infinity) .. [maxDiopters]. */
    fun dioptersForProgress(progress: Int, steps: Int, maxDiopters: Float): Float =
        if (steps <= 0) 0f else maxDiopters * progress.coerceIn(0, steps) / steps

    fun progressForDiopters(d: Float, steps: Int, maxDiopters: Float): Int =
        if (maxDiopters <= 0f) 0 else ((d / maxDiopters) * steps).roundToInt().coerceIn(0, steps)

    fun focusLabel(diopters: Float, calibrated: Boolean): String {
        if (diopters <= 0f) return "∞"
        val units = if (calibrated) "" else " (uncalibrated)"
        return String.format(Locale.US, "%.2f m  (%.3f D)%s", 1.0 / diopters, diopters, units)
    }
}
