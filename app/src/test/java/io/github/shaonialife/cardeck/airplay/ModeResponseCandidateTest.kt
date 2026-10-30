package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.*
import org.junit.Test

/**
 * Body lengths are observations from the iPhone logs in carplay_logs.zip,
 * not reconstructed encrypted event bodies. Exact schema remains a candidate.
 */
class ModeResponseCandidateTest {
    private val C = ResourceOwnershipReplay.Entity.CONTROLLER
    private val A = ResourceOwnershipReplay.Entity.ACCESSORY

    private fun state(audioCurrent: ResourceOwnershipReplay.Entity, audioPermanent: ResourceOwnershipReplay.Entity) =
        ResourceOwnershipReplay.State(
            screen = ResourceOwnershipReplay.Ownership(C, C),
            audio = ResourceOwnershipReplay.Ownership(audioCurrent, audioPermanent),
        )

    @Test fun nativeBorrowWithSpeechMatches210ByteBplist() {
        // 2026-10-18 11:09:17.835 BJT: Car Borrow mainAudio, Speech Speaking.
        // 11:09:17.836: event HTTP 200 with a 210-byte body.
        val body = ModeResponseCandidate.encode(state(A, C), ModeResponseCandidate.Speech.SPEAKING)
        assertEquals(210, body.size)
        assertEquals("bplist00", String(body.copyOfRange(0, 8), Charsets.US_ASCII))
        val decoded = BinaryPlist.parse(body) as Map<*, *>
        assertEquals(0L, decoded["status"])
        val params = decoded["params"] as Map<*, *>
        val resources = params["resources"] as List<*>
        val audio = resources[1] as Map<*, *>
        assertEquals(2L, audio["resourceID"])
        assertEquals(2L, audio["entity"])
        assertEquals(1L, audio["permanentEntity"])
        val appStates = params["appStates"] as List<*>
        val speech = appStates[2] as Map<*, *>
        assertEquals(1L, speech["speechMode"])
        assertEquals(2L, speech["entity"])
    }

    @Test fun nativeUnborrowWithNoSpeechMatches220ByteBplist() {
        // 2026-10-18 11:09:30.050 BJT: Car Unborrow mainAudio / Speech Neither.
        // 11:09:30.051: event HTTP 200 with a 220-byte body.
        val body = ModeResponseCandidate.encode(state(C, C), ModeResponseCandidate.Speech.NONE)
        assertEquals(220, body.size)
        val decoded = BinaryPlist.parse(body) as Map<*, *>
        assertEquals(0L, decoded["status"])
        val params = decoded["params"] as Map<*, *>
        val appStates = params["appStates"] as List<*>
        val speech = appStates[2] as Map<*, *>
        assertEquals(-1L, speech["speechMode"])
        assertEquals(0L, speech["entity"])
        val resources = params["resources"] as List<*>
        assertEquals(1L, (resources[1] as Map<*, *>)["entity"])
    }

    @Test fun legacyBplistEncodingDoesNotSilentlyChange() {
        val basic = linkedMapOf<String, Any?>("speechMode" to -1L)
        val old = BinaryPlist.encode(basic)
        val apple = BinaryPlist.encode(basic, appleNegativeIntegers = true)
        assertEquals(7, apple.size - old.size)
        assertEquals(255L, (BinaryPlist.parse(old) as Map<*, *>)["speechMode"])
        assertEquals(-1L, (BinaryPlist.parse(apple) as Map<*, *>)["speechMode"])
    }
}
