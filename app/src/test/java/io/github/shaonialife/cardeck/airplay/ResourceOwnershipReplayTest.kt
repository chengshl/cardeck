package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.*
import org.junit.Test

/**
 * Observations transcribed from the user's decoded, original iPhone logs:
 * carplay_logs.zip / 1018_1122/core.jsonl, 1019_1943/core.jsonl.
 *
 * UTC source times below correspond to Beijing local times (+08:00).
 * Only resource owner values are modeled; speech/appStates and wire plist are
 * deliberately not guessed from these human-readable unified logs.
 */
class ResourceOwnershipReplayTest {
    private val C = ResourceOwnershipReplay.Entity.CONTROLLER
    private val A = ResourceOwnershipReplay.Entity.ACCESSORY
    private val screen = ResourceOwnershipReplay.Resource.MAIN_SCREEN
    private val audio = ResourceOwnershipReplay.Resource.MAIN_AUDIO
    private val take = ResourceOwnershipReplay.Action.TAKE
    private val borrow = ResourceOwnershipReplay.Action.BORROW
    private val unborrow = ResourceOwnershipReplay.Action.UNBORROW

    private fun owned(current: ResourceOwnershipReplay.Entity, permanent: ResourceOwnershipReplay.Entity) =
        ResourceOwnershipReplay.Ownership(current, permanent)

    @Test fun actualIPhoneBorrowMainAudioPreservesPermanentOwner() {
        // 2026-10-18 03:09:17.835Z: Car Borrow mainAudio, priority User.
        // 03:09:17.835Z: mainAudio Car (permanent: iOS).
        val before = ResourceOwnershipReplay.State(owned(C, C), owned(C, C))
        val observed = before.copy(audio = owned(A, C))
        val request = ResourceOwnershipReplay.Request(A, audio, borrow)
        val review = ResourceOwnershipReplay.review(before, request, observed)
        assertEquals(ResourceOwnershipReplay.Verdict.MATCHES_OBSERVED_STATE, review.verdict)
        assertEquals(owned(A, C), review.projected!!.audio)
        assertEquals(before.screen, review.projected!!.screen)
    }

    @Test fun actualIPhoneUnborrowMainAudioReturnsToPermanentOwner() {
        // 2026-10-18 03:09:30.050Z: Car Unborrow mainAudio.
        // 03:09:30.051Z: mainAudio iOS (permanent: iOS).
        val before = ResourceOwnershipReplay.State(owned(C, C), owned(A, C))
        val observed = before.copy(audio = owned(C, C))
        val review = ResourceOwnershipReplay.review(
            before,
            ResourceOwnershipReplay.Request(A, audio, unborrow),
            observed,
        )
        assertEquals(ResourceOwnershipReplay.Verdict.MATCHES_OBSERVED_STATE, review.verdict)
        assertEquals(before.screen, review.projected!!.screen)
    }

    @Test fun actualIPhoneTakeScreenChangesCurrentAndPermanentAndNotAudio() {
        // 2026-10-19 11:40:57.775Z: Car Take screen.
        // 11:40:57.778Z: screen Car (permanent: Car); audio iOS/iOS.
        val before = ResourceOwnershipReplay.State(owned(C, C), owned(C, C))
        val observed = before.copy(screen = owned(A, A))
        val review = ResourceOwnershipReplay.review(
            before,
            ResourceOwnershipReplay.Request(A, screen, take),
            observed,
        )
        assertEquals(ResourceOwnershipReplay.Verdict.MATCHES_OBSERVED_STATE, review.verdict)
        assertEquals(owned(A, A), review.projected!!.screen)
        assertEquals(owned(C, C), review.projected!!.audio)
    }

    @Test fun actualIPhoneBorrowAudioCanBeIdempotentWhenCarAlreadyOwnsBoth() {
        // 2026-10-18 03:21:34.691Z: Car Borrow mainAudio even though
        // current/permanent mainAudio already Car/Car. State remains Car/Car.
        val before = ResourceOwnershipReplay.State(owned(C, C), owned(A, A))
        val review = ResourceOwnershipReplay.review(
            before,
            ResourceOwnershipReplay.Request(A, audio, borrow),
            before,
        )
        assertEquals(ResourceOwnershipReplay.Verdict.MATCHES_OBSERVED_STATE, review.verdict)
        assertEquals(before, review.projected)
    }

    @Test fun unborrowAlreadyReturnedOrNotHeldDoesNotStealResource() {
        val before = ResourceOwnershipReplay.State(owned(C, C), owned(C, C))
        val review = ResourceOwnershipReplay.review(
            before,
            ResourceOwnershipReplay.Request(A, screen, unborrow),
            before,
        )
        assertEquals(ResourceOwnershipReplay.Verdict.MATCHES_OBSERVED_STATE, review.verdict)
    }

    @Test fun unsupportedUntakeCannotInventAnOwner() {
        val before = ResourceOwnershipReplay.State(owned(A, A), owned(A, A))
        val review = ResourceOwnershipReplay.review(
            before,
            ResourceOwnershipReplay.Request(A, audio, ResourceOwnershipReplay.Action.UNTAKE),
            before,
        )
        assertNull(review.projected)
        assertEquals(ResourceOwnershipReplay.Verdict.INDETERMINATE, review.verdict)
    }

    @Test fun mismatchCannotBeReportedAsConfirmedResourceTransfer() {
        val before = ResourceOwnershipReplay.State(owned(C, C), owned(C, C))
        val review = ResourceOwnershipReplay.review(
            before,
            ResourceOwnershipReplay.Request(A, audio, borrow),
            // Pretend that someone reported only HTTP 200; no actual change observed.
            before,
        )
        assertEquals(ResourceOwnershipReplay.Verdict.CONTRADICTS_OBSERVED_STATE, review.verdict)
    }

    @Test fun modeStateProjectionKeepsEntityAndPermanentEntitySeparate() {
        val state = ResourceOwnershipReplay.State(owned(C, C), owned(A, C))
        val resources = ResourceOwnershipReplay.resourceOwnership(state)
        assertEquals(listOf(1L, 2L), resources.map { it["resourceID"] })
        assertEquals(2L, resources[1]["entity"])
        assertEquals(1L, resources[1]["permanentEntity"])
        assertEquals(1L, resources[0]["entity"])
        assertEquals(1L, resources[0]["permanentEntity"])
    }
}
