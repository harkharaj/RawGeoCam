package io.github.harkharaj.rawgeocam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class PoseMathTest {

    private fun det(m: DoubleArray) =
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    private fun angleDiff(a: Double, b: Double) = abs(PoseMath.normDeg180(a - b))

    @Test
    fun anglesRoundTrip() {
        val rng = Random(1)
        repeat(2000) {
            val h = rng.nextDouble() * 360
            val p = rng.nextDouble() * 160 - 80
            val r = rng.nextDouble() * 360 - 180
            val a = PoseMath.anglesOf(PoseMath.worldFromCamera(h, p, r))
            assertTrue(angleDiff(a.headingDeg, h) < 1e-7)
            assertEquals(p, a.pitchDeg, 1e-7)
            assertTrue(angleDiff(a.rollDeg, r) < 1e-7)
        }
    }

    @Test
    fun cameraFramesAreProperRotations() {
        for (so in intArrayOf(0, 90, 180, 270)) assertEquals(1.0, det(PoseMath.deviceFromBackCamera(so)), 1e-12)
        for (rot in intArrayOf(0, 90, 180, 270)) assertEquals(1.0, det(PoseMath.sensorCamFromDisplayCam(rot)), 1e-12)
    }

    /** Phone upright in portrait, user facing north, level: device X = east, Y = up, Z = south. */
    @Test
    fun portraitShotFacingNorthIsLevelAndUpright() {
        val rWorldFromDevice = PoseMath.fromColumns(
            doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, -1.0, 0.0),
        )
        for ((so, exif) in listOf(90 to 6, 270 to 8)) {
            val rWc = PoseMath.mul(rWorldFromDevice, PoseMath.deviceFromBackCamera(so))
            val sensor = PoseMath.anglesOf(rWc)
            val rot = PoseMath.displayRotationForRoll(sensor.rollDeg)
            assertEquals(exif, PoseMath.exifOrientationForRotation(rot))
            val display = PoseMath.anglesOf(PoseMath.mul(rWc, PoseMath.sensorCamFromDisplayCam(rot)))
            assertTrue(angleDiff(display.headingDeg, 0.0) < 1e-9)
            assertEquals(0.0, display.pitchDeg, 1e-9)
            assertEquals(0.0, display.rollDeg, 1e-9)
        }
    }

    @Test
    fun rawBufferOffsetMatchesDngCreator() {
        // buffer == pre-correction array: no offset
        assertArrayEquals(intArrayOf(0, 0), PoseMath.rawBufferOffset(4000, 3000, 8, 8, 4000, 3000, 4016, 3016))
        // buffer == full pixel array: active area sits at the pre-correction origin
        assertArrayEquals(intArrayOf(8, 6), PoseMath.rawBufferOffset(4016, 3012, 8, 6, 4000, 3000, 4016, 3012))
        assertNull(PoseMath.rawBufferOffset(1920, 1080, 8, 6, 4000, 3000, 4016, 3012))
    }

    @Test
    fun preferredRawSize() {
        val w = intArrayOf(9248, 4624, 4016)
        val h = intArrayOf(6944, 3472, 3012)
        assertEquals(2, PoseMath.preferredRawIndex(w, h, 4016, 3012, 4000, 3000)) // pixel array first
        assertEquals(1, PoseMath.preferredRawIndex(w, h, 9999, 9999, 4624, 3472)) // then pre-correction
        assertEquals(0, PoseMath.preferredRawIndex(w, h, 1, 1, 2, 2)) // else largest
    }

    @Test
    fun processedOutputIntrinsics() {
        val k = doubleArrayOf(1000.0, 1000.0, 2000.0, 1500.0, 0.0) // 4000x3000 array, centred
        // same aspect: plain scale, OpenCV centre of a 2000x1500 image is 999.5, 749.5
        assertArrayEquals(doubleArrayOf(500.0, 500.0, 999.5, 749.5, 0.0),
            PoseMath.processedOutputK(k, 0.0, 0.0, 4000.0, 3000.0, 2000, 1500), 1e-9)
        // 16:9 output: rows cropped top and bottom, uniform scale 0.48
        assertArrayEquals(doubleArrayOf(480.0, 480.0, 959.5, 539.5, 0.0),
            PoseMath.processedOutputK(k, 0.0, 0.0, 4000.0, 3000.0, 1920, 1080), 1e-9)
        // 1:1 output: columns cropped left and right
        assertArrayEquals(doubleArrayOf(1000.0, 1000.0, 1499.5, 1499.5, 0.0),
            PoseMath.processedOutputK(k, 0.0, 0.0, 4000.0, 3000.0, 3000, 3000), 1e-9)
    }

    @Test
    fun intrinsicsChecks() {
        val good = PoseMath.checkIntrinsics(doubleArrayOf(3000.0, 3001.0, 2010.0, 1495.0, 0.0), 2980.0, 4000, 3000)
        assertTrue(good.warnings.toString(), good.warnings.isEmpty())
        // intrinsics of a 2x2-binned grid applied to the full-resolution array
        val binned = PoseMath.checkIntrinsics(doubleArrayOf(1500.0, 1500.0, 2000.0, 1500.0, 0.0), 3000.0, 4000, 3000)
        assertTrue(binned.warnings.any { it.contains("estimate") })
        val offCentre = PoseMath.checkIntrinsics(doubleArrayOf(3000.0, 3000.0, 1000.0, 1500.0, 0.0), null, 4000, 3000)
        assertTrue(offCentre.warnings.any { it.contains("centre") })
        val nonSquare = PoseMath.checkIntrinsics(doubleArrayOf(3000.0, 3200.0, 2000.0, 1500.0, 0.0), null, 4000, 3000)
        assertTrue(nonSquare.warnings.any { it.contains("fx and fy") })
    }

    @Test
    fun estimateUsesPixelPitch() {
        // 6.0 mm lens, 6.4 mm wide sensor with 4000 px: fx = 6.0 * 4000 / 6.4 = 3750
        val k = PoseMath.estimateIntrinsics(6.0, 6.4, 4.8, 4000, 3000, 4000, 3000)
        assertEquals(3750.0, k[0], 1e-9)
        assertEquals(3750.0, k[1], 1e-9)
        assertEquals(2000.0, k[2], 1e-9)
    }
}
