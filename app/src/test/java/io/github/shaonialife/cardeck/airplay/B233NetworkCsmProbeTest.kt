package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class B233NetworkCsmProbeTest {
    @Test fun repeatIdentificationAfterStopHidUsesMonotonicClock() {
        val probe = B233NetworkCsmProbe()
        val first = probe.observe(0x1D01, 1000L)!!
        assertEquals("network_identify_first", first.event)
        assertEquals(1000L, first.monoMs)
        assertTrue(first.detail.contains("count=1 sincePrevMs=unknown"))
        assertEquals("network_start_hid", probe.observe(0x6800, 1700L)!!.event)
        assertEquals("network_stop_hid", probe.observe(0x6803, 16571L)!!.event)
        val repeated = probe.observe(0x1D01, 16740L)!!
        assertEquals("network_identify_repeat", repeated.event)
        assertEquals(16740L, repeated.monoMs)
        assertEquals("count=2 sincePrevMs=15740 sinceStopHidMs=169", repeated.detail)
        assertEquals("network_start_hid", probe.observe(0x6800, 17345L)!!.event)
    }

    @Test fun nonControlAndClockRegressionAreFailClosed() {
        val probe = B233NetworkCsmProbe()
        assertNull(probe.observe(0x5000, 100L))
        assertNull(probe.observe(0x1D01, -1L))
        probe.observe(0x1D01, 500L)
        val repeat = probe.observe(0x1D01, 300L)!!
        assertTrue(repeat.detail.contains("sincePrevMs=unknown"))
        assertTrue(repeat.detail.contains("sinceStopHidMs=unknown"))
        assertFalse(repeat.detail.contains("500"))
    }

    @Test fun evidenceIsEpochLocalAndBoundedWithoutPayload() {
        val first = B233NetworkCsmProbe()
        repeat(30) { first.observe(0x6800, 1000L + it) }
        assertNull(first.observe(0x1D01, 2000L)) // 16 reports maximum
        val newSession = B233NetworkCsmProbe()
        val snapshot = newSession.observe(0x1D01, 3000L)!!
        assertEquals("network_identify_first", snapshot.event)
        assertEquals(3000L, snapshot.monoMs)
        assertTrue(snapshot.detail.contains("count=1"))
        assertNull(newSession.observe(0x1234, 3001L))
        assertFalse(snapshot.detail.contains("0x"))
    }
}
