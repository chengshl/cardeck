package io.github.shaonialife.cardeck.airplay

import java.net.Socket
import java.net.SocketAddress

/**
 * B233 event-channel TCP candidate ownership before publication.
 *
 * Resolving the receiver address can fail before Socket.connect() is called.
 * Until a successful connect returns, the candidate belongs to this helper
 * and must be closed on every failure. No network or protocol fallback here.
 */
internal object B233EventSocketConnection {
    fun connect(
        candidate: Socket,
        timeoutMillis: Int,
        address: () -> SocketAddress,
    ): Socket {
        try {
            candidate.connect(address(), timeoutMillis)
            return candidate
        } catch (error: Throwable) {
            // Keep the original DNS/connect exception; close failures cannot mask it.
            runCatching { candidate.close() }
            throw error
        }
    }
}
