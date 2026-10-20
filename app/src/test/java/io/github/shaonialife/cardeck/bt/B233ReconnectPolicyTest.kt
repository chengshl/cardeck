package io.github.shaonialife.cardeck.bt

import org.junit.Assert.assertEquals
import org.junit.Test

class B233ReconnectPolicyTest {
    @Test fun audioTeardownDelaysOnlyAutomaticRetry() {
        val p = B233ReconnectPolicy()
        assertEquals(0L, p.remainingMs(1_000L))
        assertEquals(20_000L, p.onAudioSessionEnded(1_000L))
        assertEquals(19_000L, p.remainingMs(2_000L))
        assertEquals(0L, p.remainingMs(21_000L))
    }

    @Test fun repeatedUnansweredStartSessionsBackOffAtSixtySeconds() {
        val p = B233ReconnectPolicy()
        assertEquals(15_000L, p.onStartSessionTimeout(100_000L))
        assertEquals(30_000L, p.onStartSessionTimeout(120_000L))
        assertEquals(60_000L, p.onStartSessionTimeout(180_000L))
        assertEquals(60_000L, p.onStartSessionTimeout(250_000L))
        assertEquals(60_000L, p.onStartSessionTimeout(320_000L))
        p.onStartSessionAccepted()
        assertEquals(0L, p.remainingMs(320_000L))
        assertEquals(15_000L, p.onStartSessionTimeout(400_000L))
    }

    @Test fun inboundFallbackIsOneShotAndRequiresTwoFailures() {
        val p = B233ReconnectPolicy()
        assertEquals(false, p.claimInboundRecoveryAttempt())
        p.onStartSessionTimeout(1_000L)
        assertEquals(false, p.claimInboundRecoveryAttempt())
        p.onStartSessionTimeout(20_000L)
        assertEquals(true, p.claimInboundRecoveryAttempt())
        assertEquals(false, p.claimInboundRecoveryAttempt())
        p.onStartSessionTimeout(60_000L)
        assertEquals(false, p.claimInboundRecoveryAttempt())
        p.onStartSessionAccepted()
        assertEquals(false, p.claimInboundRecoveryAttempt())
        p.onStartSessionTimeout(90_000L)
        p.onStartSessionTimeout(110_000L)
        assertEquals(true, p.claimInboundRecoveryAttempt())
    }

    @Test fun manualRestartRearmsFallbackFromScratch() {
        val p = B233ReconnectPolicy()
        p.onStartSessionTimeout(0L)
        p.onStartSessionTimeout(25_000L)
        assertEquals(true, p.claimInboundRecoveryAttempt())
        p.reset()
        assertEquals(false, p.claimInboundRecoveryAttempt())
        p.onStartSessionTimeout(80_000L)
        assertEquals(false, p.claimInboundRecoveryAttempt())
        p.onStartSessionTimeout(110_000L)
        assertEquals(true, p.claimInboundRecoveryAttempt())
    }

    @Test fun userControlledRestartClearsCooldownAndFailures() {
        val p = B233ReconnectPolicy()
        p.onStartSessionTimeout(2_000L)
        p.onStartSessionTimeout(20_000L)
        p.onAudioSessionEnded(30_000L)
        assertEquals(20_000L, p.remainingMs(30_000L))
        p.reset()
        assertEquals(0L, p.remainingMs(30_000L))
        assertEquals(15_000L, p.onStartSessionTimeout(60_000L))
    }
}
