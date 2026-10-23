package io.github.shaonialife.cardeck.buick

import android.content.Context

/** Buick-Test experimental E5 handshake toggle, OFF unless explicitly enabled. */
internal object BuickE5HandshakePrefs {
    private const val FILE = "buick_e5_handshake_experiment"
    private const val ENABLED = "force_e5"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, value).apply()
    }
}

/**
 * Only force a known selected peer, or in auto mode a Buick-named receiver.
 * This is the sole experiment targeting gate; no 0x1D01 capability-list routing.
 */
internal object BuickE5HandshakeTarget {
    fun shouldForce(
        enabled: Boolean,
        selectedAddress: String?,
        peerAddress: String?,
        peerName: String?,
    ): Boolean {
        if (!enabled || peerAddress.isNullOrBlank()) return false
        if (!selectedAddress.isNullOrBlank()) {
            return peerAddress.equals(selectedAddress, ignoreCase = true)
        }
        return peerName?.contains("buick", ignoreCase = true) == true
    }
}
