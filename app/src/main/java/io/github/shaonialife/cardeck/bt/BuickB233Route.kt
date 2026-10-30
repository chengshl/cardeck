package io.github.shaonialife.cardeck.bt

/**
 * #24 BT-A: a narrow B233 discovery hint for profile-disconnect avoidance.
 * Bluetooth names are mutable and are not proof of vehicle identity.
 * Do not embed any real receiver address or expand to generic Buick/GM names.
 */
internal object BuickB233Route {
    fun isKnownReceiver(name: String?): Boolean =
        name?.trim()?.matches(Regex("(?i)^BUICK_[0-9]{4}$")) == true

    fun shouldProtectProfiles(
        advertisedName: String?,
        selectedName: String?,
        selectedMatchesDevice: Boolean,
    ): Boolean = isKnownReceiver(advertisedName) ||
        (selectedMatchesDevice && isKnownReceiver(selectedName))
}
