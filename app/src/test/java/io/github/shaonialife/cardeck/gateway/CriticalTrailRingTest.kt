package io.github.shaonialife.cardeck.gateway

import org.junit.Assert.*
import org.junit.Test

class CriticalTrailRingTest {
    private fun event(n: Int, session: String = "abcdef12") =
        CriticalEvent(1_800_000_000_000L + n, 100_000L + n, session, "PHASE_" + n)

    @Test fun retainsLastFiftyExactly() {
        val history = (1..72).fold(emptyList<CriticalEvent>()) { list, n ->
            CriticalTrailRing.append(list, event(n))
        }
        assertEquals(50, history.size)
        assertEquals("PHASE_23", history.first().phase)
        assertEquals("PHASE_72", history.last().phase)
        assertEquals(history, CriticalTrailRing.decode(CriticalTrailRing.encode(history)))
    }

    @Test fun distinguishesAppProcessAndReboot() {
        val a = CriticalEvent(100, 80000, "abcdef12", "APP_PROCESS_START")
        val b = CriticalEvent(200, 81000, "123456ab", "APP_PROCESS_START")
        val c = CriticalEvent(300, 100, "234567ab", "APP_PROCESS_START")
        assertTrue(CriticalTrailRing.newAppProcess(a,b))
        assertFalse(CriticalTrailRing.possibleDeviceReboot(a,b))
        assertTrue(CriticalTrailRing.possibleDeviceReboot(b,c))
    }

    @Test fun rejectsPayloadsAndCorruptStorage() {
        assertFalse(CriticalTrailRing.validPhase("ssid=E5"))
        assertFalse(CriticalTrailRing.validPhase("http://example"))
        assertFalse(CriticalTrailRing.validPhase("A\\tDATA"))
        assertTrue(CriticalTrailRing.validPhase("SHIZUKU_BIND_REQUESTED"))
        val stored = CriticalTrailRing.encode(listOf(event(1)))
        assertEquals(listOf(event(1)),
            CriticalTrailRing.decode("1\t2\tabcdef12\tssid=E5\n" + stored + "\nBAD"))
    }
}
