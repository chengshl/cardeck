package io.github.shaonialife.cardeck

/** E5 T78 pure lifecycle gate, deliberately independent of audio state. */
internal object E5DisconnectPolicy {
    fun shouldTryTeardown(
        isVideoStreaming: Boolean,
        hasClient: Boolean,
        clientOwnsRecordedScreen: Boolean,
    ): Boolean = isVideoStreaming && hasClient && clientOwnsRecordedScreen
}
