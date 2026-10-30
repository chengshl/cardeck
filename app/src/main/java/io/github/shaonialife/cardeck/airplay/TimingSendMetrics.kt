package io.github.shaonialife.cardeck.airplay

/**
 * Read-only, socket-independent timing observability.
 *
 * A received valid request is NOT a successfully transmitted reply.
 * All fields are counters or monotonic elapsed time; no packet contents,
 * addresses, device identities or credentials are retained.
 */
internal data class TimingSendSnapshot(
    val requestRx: Long,
    val responseSendOk: Long,
    val sendError: Long,
    val loopExit: Long,
    val lastSuccessMonoMs: Long,
    val firstSendErrorMonoMs: Long,
    val lastLoopExitReason: String,
)

internal class TimingSendMetrics {
    private var requestRx = 0L
    private var responseSendOk = 0L
    private var sendError = 0L
    private var loopExit = 0L
    private var lastSuccessMonoMs = -1L
    private var firstSendErrorMonoMs = -1L
    private var lastLoopExitReason = "none"

    @Synchronized fun requestReceived() { requestRx++ }

    /** Call strictly AFTER DatagramSocket.send has returned without throwing. */
    @Synchronized fun responseSentOk(nowMonoMs: Long) {
        responseSendOk++
        lastSuccessMonoMs = nowMonoMs
    }

    /** First failed send timestamp is preserved even if subsequent sends succeed. */
    @Synchronized fun responseSendFailed(nowMonoMs: Long) {
        sendError++
        if (firstSendErrorMonoMs < 0L) firstSendErrorMonoMs = nowMonoMs
    }

    /** Fixed-value reason only: stop, receive_error; never an exception message. */
    @Synchronized fun exited(reason: String) {
        require(reason == "stop" || reason == "receive_error")
        loopExit++
        lastLoopExitReason = reason
    }

    @Synchronized fun snapshot() = TimingSendSnapshot(
        requestRx, responseSendOk, sendError, loopExit, lastSuccessMonoMs,
        firstSendErrorMonoMs, lastLoopExitReason,
    )
}
