package io.github.harkharaj.rawgeocam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualControlsTest {

    @Test
    fun shutterStopsStayInRangeAndAscend() {
        val stops = ManualControls.shutterStops(13_000L, 683_709_000L) // a typical phone range
        assertTrue(stops.first() >= 13_000L && stops.last() <= 683_709_000L)
        for (i in 1 until stops.size) assertTrue(stops[i] > stops[i - 1])
        assertTrue(stops.any { it == 1_000_000_000L / 250 })
    }

    @Test
    fun isoStopsIncludeRangeEnds() {
        val stops = ManualControls.isoStops(43, 6400)
        assertEquals(43, stops.first())
        assertEquals(6400, stops.last())
    }

    @Test
    fun nearestShutterIsLogarithmic() {
        val stops = longArrayOf(1_000_000L, 2_000_000L, 4_000_000L)
        assertEquals(1, ManualControls.nearestIndex(stops, 2_700_000L)) // closer to 2 ms than 4 ms in stops
    }

    @Test
    fun focusSliderRoundTripsExactly() {
        val max = 10f
        for (p in 0..1000) {
            val d = ManualControls.dioptersForProgress(p, 1000, max)
            assertEquals(p, ManualControls.progressForDiopters(d, 1000, max))
        }
        assertEquals(0f, ManualControls.dioptersForProgress(0, 1000, max), 0f) // leftmost = infinity
    }

    @Test
    fun labels() {
        assertEquals("1/250 s", ManualControls.shutterLabel(4_000_000L))
        assertEquals("2.0 s", ManualControls.shutterLabel(2_000_000_000L))
        assertEquals("∞", ManualControls.focusLabel(0f, true))
    }
}
