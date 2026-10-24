package io.github.shaonialife.cardeck.buick

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuickConnectionModePolicyTest {
    private fun eligible(
        active: Boolean = true,
        busy: Boolean = false,
        uptime: Long = 1_500L,
        retryAge: Long = Long.MAX_VALUE,
        cooldown: Long = 0L,
    ) = BuickConnectionModePolicy.mayDial(active, busy, uptime, retryAge, cooldown)

    @Test fun passiveNeverDialsEvenAfterAclAndLongWait() {
        assertFalse(eligible(active = false, uptime = 90_000L))
    }

    @Test fun activeWaitsForIncomingPriority() {
        assertFalse(eligible(uptime = 1_499L))
        assertTrue(eligible(uptime = 1_500L))
    }

    @Test fun activeDoesNotRaceAnEstablishedOrIncomingSession() {
        assertFalse(eligible(busy = true))
    }

    @Test fun activeHasBoundedRetryAndRespectsCooldown() {
        assertFalse(eligible(retryAge = 14_999L))
        assertTrue(eligible(retryAge = 15_000L))
        assertFalse(eligible(cooldown = 1L))
    }
}
