package io.github.shaonialife.cardeck.audio

/**
 * Home-screen audio indicator: user choice is not the same as an active sender.
 *
 * In particular, a B233 audio failure may force video-only recovery while
 * preserving the user's original selection so the UI can explain the fallback.
 * The sender running does not prove audible playback at the receiver.
 */
internal enum class AudioUiStatus(val caption: String) {
    NOT_REQUESTED("车机侧声音 · 未开启"),
    WAITING("车机侧声音 · 已选择，待启动"),
    SENDING("车机侧声音 · 正在推送"),
    FAILED("车机侧声音 · 启动失败"),
    DOWNGRADED("车机侧声音 · 异常已降级为仅视频");

    companion object {
        fun fromSession(
            userRequested: Boolean,
            attempted: Boolean,
            senderRunning: Boolean,
            autoDowngraded: Boolean,
        ): AudioUiStatus = when {
            autoDowngraded && userRequested -> DOWNGRADED
            !userRequested -> NOT_REQUESTED
            senderRunning -> SENDING
            attempted -> FAILED
            else -> WAITING
        }
    }
}
