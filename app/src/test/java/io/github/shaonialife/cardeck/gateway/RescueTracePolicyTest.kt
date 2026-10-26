package io.github.shaonialife.cardeck.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueTracePolicyTest {
    @Test fun retainRebootAndServiceRegistrationEvidence() {
        assertTrue(RescueTracePolicy.keep("10-26 22:23:13 E AndroidRuntime: SettingsProvider java.lang.SecurityException: SELinux denied"))
        assertTrue(RescueTracePolicy.keep("10-26 22:23:15 E auditd: avc: denied { add } name=settings tclass=service_manager"))
        assertTrue(RescueTracePolicy.keep("10-26 22:23:47 I RescueParty: WARM_REBOOT"))
        assertTrue(RescueTracePolicy.keep("10-26 22:23:28 E ActivityManager: Process 1000 has died"))
    }
    @Test fun rejectUnrelatedAndUnboundedLines() {
        assertFalse(RescueTracePolicy.keep("10-26 22:23:13 D MyApp: user entered a message"))
        assertFalse(RescueTracePolicy.keep(""))
        assertFalse(RescueTracePolicy.keep("x".repeat(8193)))
    }
    @Test fun redactBeforePersistenceKeepsAvcContext() {
        val chunks = LogRedactor(listOf("my-secret-token")).forensicChunks(
            "10-26 22:23:13 E auditd: avc: denied { add } name=settings " +
                "scontext=u:r:system_app:s0 tcontext=u:object_r:settings_service:s0 " +
                "tclass=service_manager token=my-secret-token"
        )
        assertTrue(chunks.joinToString(" ").contains("denied { add }"))
        assertTrue(chunks.joinToString(" ").contains("settings_service"))
        assertFalse(chunks.joinToString(" ").contains("my-secret-token"))
    }
}
