package io.github.shaonialife.cardeck.gateway

import org.junit.Assert.*
import org.junit.Test

class RescueTraceAccuracyTest {
    @Test fun avcStructuralIdentitySurvivesRedaction() {
        val raw = "2026-10-27 19:36:26.998 E SELinux : avc: denied { find } " +
            "for interface=vendor.qti.hardware.perf2.IPerf " +
            "sid=u:r:rkpdapp:s0:c125,c257,c512,c768 pid=32658 " +
            "scontext=u:r:rkpdapp:s0:c125,c257,c512,c768 " +
            "tcontext=u:object_r:vendor_hal_perf_hwservice:s0 " +
            "tclass=hwservice_manager permissive=0"
        val got = LogRedactor().forensicChunks(raw).joinToString("") { it.substringAfter("] ") }
        assertTrue(got.contains("interface=vendor.qti.hardware.perf2.IPerf"))
        assertTrue(got.contains("scontext=u:r:rkpdapp:s0:c125,c257,c512,c768"))
        assertTrue(got.contains("tcontext=u:object_r:vendor_hal_perf_hwservice:s0"))
        assertTrue(got.contains("tclass=hwservice_manager"))
        assertFalse(got.contains("[IPv6]"))
    }

    @Test fun contextIsBoundedAndBeforeAfterAreRestored() {
        val context = RescueTraceContext()
        repeat(10) { assertTrue(context.accept("random-$it", listOf("safe-$it")).isEmpty()) }
        val found = context.accept("SettingsProvider: FATAL EXCEPTION", listOf("error"))
        assertEquals(5, found.size)
        assertEquals("safe-6", found.first().first)
        assertTrue(found.last().second)
        repeat(12) { i ->
            assertEquals(1, context.accept("nonmatching-$i", listOf("post-$i")).size)
        }
        assertTrue(context.accept("unrelated", listOf("not-retained")).isEmpty())
    }

    @Test fun credentialsHiddenInExceptionContext() {
        val safe = LogRedactor().forensicChunks("FATAL EXCEPTION password=do_not_export")
        assertFalse(safe.joinToString().contains("do_not_export"))
        assertTrue(safe.joinToString().contains("FATAL EXCEPTION"))
    }
}
