package io.github.shaonialife.cardeck.bt

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

/**
 * Per-installation observation of a successfully established receiver protocol.
 *
 * The Bluetooth name can be changed by the owner. Keep only a one-way hash
 * of the paired device address as the local preference key, never upload it.
 * This is a routing hint, NOT cryptographic receiver authentication.
 * Only remember B233 after the existing iAP2 identity gate AND a valid 0x4301.
 */
internal object ReceiverCompatibilityMemory {
    private const val PREFS = "receiver_protocol_observations_v1"
    private const val PREFIX = "observed_b233_"
    private val addressPattern = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    /** Deterministic across Bluetooth-name changes and case variations. */
    internal fun preferenceKey(address: String?): String? {
        val normalized = address?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (!addressPattern.matches(normalized)) return null
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(("CarDeck/Buick/B233/v1/" + normalized).toByteArray(Charsets.UTF_8))
        return PREFIX + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun wasB233Observed(context: Context, address: String?): Boolean {
        val key = preferenceKey(address) ?: return false
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key, false)
    }

    /** Returns true only for a newly remembered valid address. */
    fun rememberB233(context: Context, address: String?): Boolean {
        val key = preferenceKey(address) ?: return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }
}
