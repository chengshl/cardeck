package io.github.shaonialife.cardeck.airplay

/**
 * Offline-only resource transition checker backed by observed iPhone CarPlay
 * resource-state logs. Never sends RTSP, grants ownership, or changes a session.
 *
 * An incoming changeModes is a *request*, not a confirmed transfer. The expected
 * transition is only a model hypothesis; a separate observed modesChanged state
 * must be supplied for verification. Exact event reply plist/status semantics are
 * intentionally outside this model until a real response body is available.
 */
internal object ResourceOwnershipReplay {
    enum class Entity(val value: Long) { CONTROLLER(1L), ACCESSORY(2L) }
    enum class Resource(val id: Long) { MAIN_SCREEN(1L), MAIN_AUDIO(2L) }
    enum class Action(val code: Long) {
        TAKE(1L), UNTAKE(2L), BORROW(3L), UNBORROW(4L)
    }

    data class Ownership(
        val current: Entity,
        val permanent: Entity,
    )

    data class State(
        val screen: Ownership,
        val audio: Ownership,
    ) {
        fun get(resource: Resource): Ownership =
            when (resource) {
                Resource.MAIN_SCREEN -> screen
                Resource.MAIN_AUDIO -> audio
            }

        fun replacing(resource: Resource, value: Ownership): State =
            when (resource) {
                Resource.MAIN_SCREEN -> copy(screen = value)
                Resource.MAIN_AUDIO -> copy(audio = value)
            }
    }

    data class Request(
        val requester: Entity,
        val resource: Resource,
        val action: Action,
    )

    enum class Verdict {
        MATCHES_OBSERVED_STATE,
        CONTRADICTS_OBSERVED_STATE,
        INDETERMINATE,
    }

    data class Review(
        val verdict: Verdict,
        val projected: State?,
        val confirmed: State,
    )

    /**
     * Proposes only transitions supported by the available iPhone observations:
     * TAKE: both owners become requester.
     * BORROW: current becomes requester, permanent stays unchanged.
     * UNBORROW: current reverts to permanent if requester currently borrowed it,
     *           otherwise remains unchanged (idempotent).
     *
     * UNTAKE: return ownership is context-sensitive and not established by the
     * supplied normal-session evidence. Do not invent a recipient.
     */
    fun project(before: State, request: Request): State? {
        val old = before.get(request.resource)
        val next = when (request.action) {
            Action.TAKE -> Ownership(request.requester, request.requester)
            Action.BORROW -> old.copy(current = request.requester)
            Action.UNBORROW ->
                if (old.current == request.requester) {
                    old.copy(current = old.permanent)
                } else {
                    old
                }
            Action.UNTAKE -> return null
        }
        return before.replacing(request.resource, next)
    }

    /**
     * Confirms a *model match*, never network acceptance or protocol success.
     * A changed screen ownership must not silently mutate main audio, and vice
     * versa. The observed state is caller-supplied, not derived from an HTTP 200.
     */
    fun review(before: State, request: Request, observed: State): Review {
        val expected = project(before, request)
        return Review(
            verdict = when {
                expected == null -> Verdict.INDETERMINATE
                expected == observed -> Verdict.MATCHES_OBSERVED_STATE
                else -> Verdict.CONTRADICTS_OBSERVED_STATE
            },
            projected = expected,
            confirmed = observed,
        )
    }

    /** A normalized explicit state, not evidence of a real plist payload. */
    fun resourceOwnership(state: State): List<Map<String, Long>> =
        listOf(
            Resource.MAIN_SCREEN to state.screen,
            Resource.MAIN_AUDIO to state.audio,
        ).map { (resource, ownership) ->
            linkedMapOf(
                "resourceID" to resource.id,
                "entity" to ownership.current.value,
                "permanentEntity" to ownership.permanent.value,
            )
        }
}
