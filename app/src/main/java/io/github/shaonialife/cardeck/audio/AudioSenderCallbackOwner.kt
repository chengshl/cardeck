package io.github.shaonialife.cardeck.audio

/**
 * An audio sender can queue a sync-port failure callback on a background worker.
 * The callback must not act on a replacement sender, even when the receiver,
 * CarPlay session and audio profile are unchanged.
 *
 * Bind before start(); reference identity is intentional, not value equality.
 */
internal class AudioSenderCallbackOwner {
    @Volatile private var owner: Any? = null

    fun bind(sender: Any) {
        check(owner == null) { "Audio sender callback owner already bound" }
        owner = sender
    }

    fun owns(activeSender: Any?): Boolean =
        activeSender != null && owner === activeSender
}
