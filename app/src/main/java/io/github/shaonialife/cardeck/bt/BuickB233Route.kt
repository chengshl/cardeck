package io.github.shaonialife.cardeck.bt

/**
 * B233 experimental SDP fallback is limited to Buick's four-digit
 * Bluetooth-name format; never match generic Buick/GM or other accessories.
 * This avoids storing an individual car's advertised name in public source.
 *
 * Explicit user-selected connection targets continue to bypass SDP as before.
 */
internal object BuickB233Route {
    /** Legacy discovery hint only. A Bluetooth name is mutable and is not a model identity. */
    fun isKnownReceiver(name: String?): Boolean =
        name?.trim()?.matches(Regex("(?i)^BUICK_[0-9]{4}$")) == true

    /**
     * Name-independent reconnect/SDP fallback for a receiver whose B233 protocol
     * was previously observed after a successful 0x4301 exchange on this phone.
     * This only selects Bluetooth transport handling: the iAP2 receiver identity
     * is checked separately before invoking the B233-specific handshake.
     */
    fun useB233Transport(name: String?, observedB233: Boolean): Boolean =
        observedB233 || isKnownReceiver(name)
}
