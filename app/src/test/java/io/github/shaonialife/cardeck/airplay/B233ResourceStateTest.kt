package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T104 offline contract only. Does not claim any B233 real-car success. */
class B233ResourceStateTest {
    private fun command(id: Long, transfer: Long, borrowId: String? = null, constraint: Int? = null): Map<String, Any> =
        mapOf(
            "resources" to listOf(
                linkedMapOf<String, Any>("resourceID" to id, "transferType" to transfer).apply {
                    if (borrowId != null) put("borrowID", borrowId)
                    if (constraint != null) put("takeConstraint", constraint)
                },
            ),
        )

    @Test fun carTakeDoesNotImmediatelyReassertPhoneScreen() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(command(1L, 1L, constraint = 999))
        val screen = state.resourcesForModesChanged(claimAudio = false).single()
        assertEquals(2L, screen["entity"])
        assertEquals(2L, screen["permanentEntity"])
        state.requestPhoneScreen()
        assertFalse(state.screenOwnedByPhone())
    }

    @Test fun releaseAndRequestUiRestorePhoneScreen() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(command(1L, 1L, constraint = 100))
        state.requestPhoneScreen()
        assertTrue(state.screenOwnedByPhone())
        state.applyCarChange(command(1L, 1L, constraint = 999))
        state.applyCarChange(command(1L, 2L))
        assertTrue(state.screenOwnedByPhone())
    }

    @Test fun borrowDoesNotEndUntilMatchingUnborrow() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(command(1L, 3L, "loan-a"))
        state.applyCarChange(command(1L, 3L, "loan-b"))
        state.applyCarChange(command(1L, 4L, "loan-a"))
        state.requestPhoneScreen()
        assertFalse(state.screenOwnedByPhone())
        state.applyCarChange(command(1L, 4L, "loan-b"))
        assertTrue(state.screenOwnedByPhone())
    }

    @Test fun malformedBorrowFailsClosedWhileUnknownResourceIsIgnored() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(command(1L, 3L, ""))
        state.applyCarChange(command(100L, 1L))
        state.applyCarChange(command(1L, 999L))
        assertFalse(state.screenOwnedByPhone())
    }

    @Test fun videoOnlyDoesNotClaimMainAudio() {
        val state = B233ResourceState()
        state.initialize(null)
        assertEquals(listOf(1L), state.resourcesForModesChanged(false).map { it["resourceID"] })
        assertEquals(listOf(1L, 2L), state.resourcesForModesChanged(true).map { it["resourceID"] })
    }

    @Test fun initialReceiverOwnershipAndSessionIsolation() {
        val state = B233ResourceState()
        state.initialize(mapOf("initialPermanentEntity" to listOf(mapOf("resourceID" to 1L, "permanentEntity" to 2L))))
        assertFalse(state.screenOwnedByPhone())
        // Reused model must explicitly reset between session generations; production
        // integration should also reject stale events from the previous socket.
        state.initialize(null)
        assertTrue(state.screenOwnedByPhone())
        val otherSession = B233ResourceState()
        otherSession.initialize(null)
        assertTrue(otherSession.screenOwnedByPhone())
    }
}

// Additional hardening regression cases are kept in a distinct class so the
// preceding six original T104 cases remain unchanged for review.
class B233ResourceStateHardeningTest {
    private fun command(resource: Any, transfer: Any, borrowId: String? = null): Map<String, Any> =
        mapOf("resources" to listOf(linkedMapOf<String, Any>("resourceID" to resource, "transferType" to transfer).apply {
            if (borrowId != null) put("borrowID", borrowId)
        }))

    @Test fun integerNarrowingAndFractionalIdsAreRejected() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(command(4_294_967_297L, 1L))
        state.applyCarChange(command(1.0, 1L))
        state.applyCarChange(command(1L, 4_294_967_297L))
        assertTrue(state.screenOwnedByPhone())
    }

    @Test fun extraBorrowRequestsFailClosed() {
        val state = B233ResourceState()
        state.initialize(null)
        for (i in 0..32) state.applyCarChange(command(1L, 3L, "loan-$i"))
        for (i in 0..32) state.applyCarChange(command(1L, 4L, "loan-$i"))
        state.requestPhoneScreen()
        assertFalse(state.screenOwnedByPhone())
        state.initialize(null)
        assertTrue(state.screenOwnedByPhone())
    }

    @Test fun oversizedBorrowIdentityDoesNotGetStored() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(command(1L, 3L, "secret".repeat(100)))
        assertFalse(state.screenOwnedByPhone())
        state.initialize(null)
        assertTrue(state.screenOwnedByPhone())
    }

    @Test fun takeConstraintIntegerOverflowIsNotTreatedAsAllowed() {
        val state = B233ResourceState()
        state.initialize(null)
        val request = mapOf("resources" to listOf(mapOf(
            "resourceID" to 1L, "transferType" to 1L,
            "takeConstraint" to 4_294_967_396L,
        )))
        state.applyCarChange(request)
        state.requestPhoneScreen()
        // A hostile narrowing of 4294967396 to 100 must not unlock requestUI.
        assertFalse(state.screenOwnedByPhone())
    }
}


class B233ResourceStateOversizeTest {
    @Test fun oversizedResourcesFailClosed() {
        val state = B233ResourceState()
        val list = (1L..33L).map { mapOf("resourceID" to it, "transferType" to 2L) }
        state.initialize(null)
        state.applyCarChange(mapOf("resources" to list))
        assertFalse(state.screenOwnedByPhone())
        state.initialize(mapOf("resources" to list))
        assertFalse(state.screenOwnedByPhone())
    }
}

/** Keep the initial T85 audio intent separate from receiver changeModes. */
class B233ResourceAudioOwnershipTest {
    @Test fun receiverAudioTakeIsNotOverriddenByOrdinaryEventReply() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(mapOf("resources" to listOf(mapOf("resourceID" to 2L, "transferType" to 1L))))
        val audioInEventReply = state.resourcesForModesChanged(true).last()
        assertEquals(2L, audioInEventReply["entity"])
        assertEquals(2L, audioInEventReply["permanentEntity"])
        assertEquals(listOf(1L), state.resourcesForModesChanged(false).map { it["resourceID"] })
    }

    @Test fun explicitAudioSetupClaimCanRestorePhoneAfterTake() {
        val state = B233ResourceState()
        state.initialize(mapOf("initialPermanentEntity" to listOf(mapOf("resourceID" to 2L, "permanentEntity" to 2L))))
        assertEquals(2L, state.resourcesForModesChanged(true).last()["entity"])
        assertTrue(state.requestMainAudioForSetup())
        assertEquals(1L, state.resourcesForModesChanged(true).last()["entity"])
    }

    @Test fun audioBorrowForbidsExplicitClaimUntilReleased() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(mapOf("resources" to listOf(mapOf("resourceID" to 2L, "transferType" to 3L, "borrowID" to "b1"))))
        assertFalse(state.requestMainAudioForSetup())
        assertEquals(2L, state.resourcesForModesChanged(true).last()["entity"])
        state.applyCarChange(mapOf("resources" to listOf(mapOf("resourceID" to 2L, "transferType" to 4L, "borrowID" to "b1"))))
        assertTrue(state.requestMainAudioForSetup())
        assertEquals(1L, state.resourcesForModesChanged(true).last()["entity"])
    }
}
