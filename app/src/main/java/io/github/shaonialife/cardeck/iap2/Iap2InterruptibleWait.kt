package io.github.shaonialife.cardeck.iap2

import java.io.IOException

/**
 * iAP2 deliberately uses cancellable waits while the Bluetooth client owns a
 * worker thread. stop() interrupts it. Convert that cancellation to the
 * IOException handled at the session boundary rather than crashing Android.
 * This does not modify handshake order or wait duration.
 */
internal object Iap2InterruptibleWait {
    @Throws(IOException::class)
    fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (cause: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("iAP2 session cancelled: interrupted", cause)
        }
    }
}
