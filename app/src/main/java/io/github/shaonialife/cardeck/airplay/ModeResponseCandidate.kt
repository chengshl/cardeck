package io.github.shaonialife.cardeck.airplay

/**
 * OFFLINE RESPONSE CANDIDATE; deliberately not connected to RTSP/event handling.
 *
 * The native iPhone log records Car Borrow mainAudio + Speaking -> HTTP 200 with
 * 210-byte body, and Car Unborrow + no speech -> HTTP 200 with 220-byte body.
 * This structurally follows the publicly documented ChangeModesResponse:
 * { status: 0, params: ModesChanged(appStates, resources) }.
 *
 * Matching a body length cannot prove byte-for-byte identity, or prove a B233
 * request was accepted. No HTTP request, ACK, ownership mutation or media gate
 * is performed here.
 */
internal object ModeResponseCandidate {
    enum class Speech(val code: Long, val owner: Long) {
        NONE(-1L, 0L),
        SPEAKING(1L, 2L),
    }

    fun body(
        state: ResourceOwnershipReplay.State,
        speech: Speech,
    ): Map<String, Any> =
        linkedMapOf(
            "status" to 0L,
            "params" to linkedMapOf(
                "appStates" to listOf(
                    linkedMapOf("appStateID" to 2L, "entity" to 0L),
                    linkedMapOf("appStateID" to 3L, "entity" to 0L),
                    linkedMapOf("appStateID" to 1L, "entity" to speech.owner, "speechMode" to speech.code),
                ),
                "resources" to ResourceOwnershipReplay.resourceOwnership(state),
            ),
        )

    /**
     * Use opt-in Apple-style 8-byte signed negative integers for speechMode=-1;
     * normal legacy BinaryPlist.encode callers preserve their existing behavior.
     */
    fun encode(state: ResourceOwnershipReplay.State, speech: Speech): ByteArray =
        BinaryPlist.encode(body(state, speech), appleNegativeIntegers = true, appleInternScalars = true)
}
