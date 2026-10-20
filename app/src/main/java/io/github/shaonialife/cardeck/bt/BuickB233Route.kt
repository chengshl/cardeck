package io.github.shaonialife.cardeck.bt

/**
 * Only the observed Buick E5 B233 Bluetooth receiver name qualifies for the
 * outbound iAP2 exception. Do not broadly match "BUICK" or other GM devices:
 * their SDP and incoming-link semantics may legitimately differ.
 *
 * If the head unit is renamed, explicitly selecting it in Connection
 * Preparation still bypasses the SDP check in Iap2BluetoothClient.
 */
internal object BuickB233Route {
    fun isKnownReceiver(name: String?): Boolean =
        name?.trim()?.equals("BUICK_2427", ignoreCase = true) == true
}
