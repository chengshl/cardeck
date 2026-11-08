package io.github.shaonialife.cardeck.airplay

/**
 * T104 B233-only resource arbitration model — OFFLINE CONTRACT, NOT WIRED.
 *
 * This state is scoped to one RTSP/control-session generation. Construct a new
 * instance for each session. No Android dependencies, sockets or side effects.
 */
internal class B233ResourceState {
    private data class Resource(
        var permanentEntity: Long = PHONE,
        var takeConstraint: Int? = null,
        val borrowIds: MutableSet<String> = linkedSetOf(),
        var borrowOverflow: Boolean = false,
    ) {
        val effectiveEntity: Long
            get() = if (borrowOverflow || borrowIds.isNotEmpty()) CAR else permanentEntity
    }

    private val resources = linkedMapOf(SCREEN_ID to Resource(), AUDIO_ID to Resource())

    @Synchronized
    fun initialize(initialModes: Map<*, *>?) {
        resources.values.forEach {
            it.permanentEntity = PHONE
            it.takeConstraint = null
            it.borrowIds.clear()
            it.borrowOverflow = false
        }
        val initialResources = initialModes?.get("resources") as? List<*>
        if (initialResources != null && initialResources.size > MAX_RESOURCE_ITEMS) {
            resources.values.forEach { it.borrowOverflow = true }
        }
        initialResources?.take(MAX_RESOURCE_ITEMS)?.forEach { raw ->
            val item = raw as? Map<*, *> ?: return@forEach
            val id = boundedResourceId(item["resourceID"]) ?: return@forEach
            resources.getValue(id).permanentEntity = CAR
        }
        val initialEntities = initialModes?.get("initialPermanentEntity") as? List<*>
        if (initialEntities != null && initialEntities.size > MAX_RESOURCE_ITEMS) {
            resources.values.forEach { it.borrowOverflow = true }
        }
        initialEntities?.take(MAX_RESOURCE_ITEMS)?.forEach { raw ->
            val item = raw as? Map<*, *> ?: return@forEach
            val id = boundedResourceId(item["resourceID"]) ?: return@forEach
            val resource = resources.getValue(id)
            resource.permanentEntity = boundedEntity(item["permanentEntity"]) ?: resource.permanentEntity
            resource.takeConstraint = constraintFrom(item)
        }
        applyCarChange(initialModes)
    }

    /** TAKE=1, RELEASE=2, BORROW=3, UNBORROW=4; invalid fields are no-ops. */
    @Synchronized
    fun applyCarChange(params: Map<*, *>?) {
        val items = params?.get("resources") as? List<*> ?: return
        if (items.size > MAX_RESOURCE_ITEMS) {
            resources.values.forEach { it.borrowOverflow = true }
            return
        }
        items.forEach { raw ->
            val item = raw as? Map<*, *> ?: return@forEach
            val id = boundedResourceId(item["resourceID"]) ?: return@forEach
            val resource = resources.getValue(id)
            when (boundedLong(item["transferType"])) {
                1L -> {
                    resource.permanentEntity = CAR
                    resource.takeConstraint = constraintFrom(item)
                }
                2L -> resource.permanentEntity = PHONE
                3L -> {
                    val borrowId = boundedBorrowId(item["borrowID"])
                    if (borrowId == null) {
                        // Unknown borrow cannot be safely matched by a future UNBORROW.
                        resource.borrowOverflow = true
                        return@forEach
                    }
                    if (borrowId !in resource.borrowIds) {
                        if (resource.borrowIds.size < MAX_ACTIVE_BORROWS) {
                            resource.borrowIds.add(borrowId)
                        } else {
                            // Don't falsely reclaim screen if the receiver borrowed more
                            // IDs than we can safely retain. New session clears the flag.
                            resource.borrowOverflow = true
                        }
                    }
                }
                4L -> {
                    val borrowId = boundedBorrowId(item["borrowID"]) ?: return@forEach
                    resource.borrowIds.remove(borrowId)
                    // Overflow cannot be safely reconciled from incomplete identifiers.
                }
            }
        }
    }

    @Synchronized
    fun requestPhoneScreen() {
        val screen = resources.getValue(SCREEN_ID)
        if (!screen.borrowOverflow && screen.borrowIds.isEmpty() &&
            screen.takeConstraint.let { it == null || it == 100 || it == 500 }
        ) {
            screen.permanentEntity = PHONE
        }
    }

    @Synchronized
    fun screenOwnedByPhone(): Boolean = resources.getValue(SCREEN_ID).effectiveEntity == PHONE

    /**
     * Explicit local transition for the existing T85 ``just before audio SETUP``
     * moment. A receiver TAKE is not silently reversed by later changeModes;
     * the caller must separately decide to request audio. If the receiver has
     * outstanding (or untracked) borrows, fail closed and keep its ownership.
     * This method is NOT called by the generic resource-event reply path.
     */
    @Synchronized
    fun requestMainAudioForSetup(): Boolean {
        val audio = resources.getValue(AUDIO_ID)
        if (audio.borrowOverflow || audio.borrowIds.isNotEmpty()) return false
        audio.permanentEntity = PHONE
        return true
    }

    /** B233 video-only must never publish a resource-2 mainAudio claim. */
    @Synchronized
    fun resourcesForModesChanged(claimAudio: Boolean): List<Map<String, Long>> =
        listOfNotNull(
            resourceSnapshot(SCREEN_ID),
            if (claimAudio) resourceSnapshot(AUDIO_ID) else null,
        )

    private fun resourceSnapshot(id: Int): Map<String, Long> {
        val state = resources.getValue(id)
        return linkedMapOf(
            "resourceID" to id.toLong(),
            "entity" to state.effectiveEntity,
            "permanentEntity" to state.permanentEntity,
        )
    }

    private fun boundedResourceId(value: Any?): Int? =
        when (boundedLong(value)) {
            1L -> SCREEN_ID
            2L -> AUDIO_ID
            else -> null
        }

    private fun boundedEntity(value: Any?): Long? = boundedLong(value)?.takeIf { it in 0L..2L }

    /** Avoid Number.toInt()/toLong() narrowing of hostile overflows/fractions. */
    private fun boundedLong(value: Any?): Long? =
        when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            else -> null
        }

    private fun boundedInt(value: Any?): Int? =
        boundedLong(value)?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

    private fun constraintFrom(item: Map<*, *>): Int? =
        if (!item.containsKey("takeConstraint")) null else boundedInt(item["takeConstraint"]) ?: INVALID_CONSTRAINT

    private fun boundedBorrowId(value: Any?): String? =
        (value as? String)?.takeIf { it.isNotEmpty() && it.length <= MAX_BORROW_ID_LENGTH }

    private companion object {
        const val SCREEN_ID = 1
        const val AUDIO_ID = 2
        const val PHONE = 1L
        const val CAR = 2L
        const val INVALID_CONSTRAINT = Int.MIN_VALUE
        const val MAX_RESOURCE_ITEMS = 32
        const val MAX_ACTIVE_BORROWS = 32
        const val MAX_BORROW_ID_LENGTH = 128
    }
}
