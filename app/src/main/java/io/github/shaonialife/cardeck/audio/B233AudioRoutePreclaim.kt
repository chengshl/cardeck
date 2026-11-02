package io.github.shaonialife.cardeck.audio

import java.net.DatagramSocket

/**
 * A local, receive-free B233 audio route preflight before mainAudio declaration.
 *
 * Does not send a packet, resolve a host, connect, request a Network, or change
 * the process/default VPN route. An ephemeral UDP socket is always closed.
 * Successful binding is only a point-in-time prerequisite, never proof that
 * the receiver accepted a resource or that the later RTP socket will work.
 */
internal fun preflightB233AudioRouteBeforeClaim(
    isBuickB233: Boolean,
    bindToVehicleNetwork: ((DatagramSocket) -> Unit)?,
    onFailure: (Exception) -> Unit,
    newSocket: () -> DatagramSocket = { DatagramSocket() },
): Boolean {
    // Other receivers retain their old behavior: no preflight or socket.
    if (!isBuickB233) return true
    return try {
        newSocket().use { probe ->
            bindOwnedAudioDatagramSocket(
                probe,
                bindToVehicleNetwork?.let { bind -> { bind(probe) } },
                failClosed = true,
                onFailure = onFailure,
            )
        }
    } catch (error: Exception) {
        onFailure(error)
        false
    }
}
