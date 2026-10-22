package io.github.shaonialife.cardeck.bt

/**
 * B233 experimental SDP fallback is limited to Buick's four-digit
 * Bluetooth-name format; never match generic Buick/GM or other accessories.
 * This avoids storing an individual car's advertised name in public source.
 *
 * Explicit user-selected connection targets continue to bypass SDP as before.
 */
internal object BuickB233Route {
    fun isKnownReceiver(name: String?): Boolean =
        name?.trim()?.matches(Regex("(?i)^BUICK_[0-9]{4}$")) == true
}
