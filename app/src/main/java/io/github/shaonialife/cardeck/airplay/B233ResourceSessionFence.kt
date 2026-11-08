package io.github.shaonialife.cardeck.airplay

import java.util.concurrent.Executor

/**
 * OFFLINE T104 ownership and nonblocking-dispatch contract.
 *
 * Event reader only computes a bounded immutable snapshot; any RTSP operation
 * MUST be offloaded to an executor and MUST independently bind its request to
 * [Snapshot.controlIdentity]. The check before send cannot atomically protect
 * a real socket from replacement during I/O. Integration must use expectedSocket.
 */
internal class B233ResourceSessionFence {
    private class Session(
        val generation: Long,
        val controlIdentity: Any,
        val eventIdentity: Any,
        val resourceState: B233ResourceState,
        var revision: Long = 0L,
        // A request to claim audio is not a confirmed receiver-side RTSP response.
        var explicitAudioClaimConfirmed: Boolean = false,
    )

    class Snapshot internal constructor(
        val generation: Long,
        val controlIdentity: Any,
        val eventIdentity: Any,
        val revision: Long,
        val type: String,
        val resources: List<Map<String, Long>>,
    )

    private var generationCounter: Long = 0L
    private var current: Session? = null

    @Synchronized
    fun begin(controlIdentity: Any, eventIdentity: Any, initialModes: Map<*, *>?): Long {
        require(controlIdentity !== eventIdentity) { "Control/event channels must be distinct" }
        check(generationCounter < Long.MAX_VALUE) { "Generation exhausted" }
        val resource = B233ResourceState().apply { initialize(initialModes) }
        val session = Session(++generationCounter, controlIdentity, eventIdentity, resource)
        current = session
        return session.generation
    }

    @Synchronized
    fun close(generation: Long, controlIdentity: Any, eventIdentity: Any): Boolean {
        if (matching(generation, controlIdentity, eventIdentity) == null) return false
        current = null
        return true
    }

    /** Run synchronously on reader: bounded state mutation only, no network I/O. */
    @Synchronized
    fun plan(
        generation: Long,
        controlIdentity: Any,
        eventIdentity: Any,
        command: Map<*, *>,
        claimAudio: Boolean,
    ): Snapshot? {
        val session = matching(generation, controlIdentity, eventIdentity) ?: return null
        val type = command["type"] as? String ?: return null
        when (type) {
            "changeModes" -> {
                val params = command["params"] as? Map<*, *> ?: return null
                session.resourceState.applyCarChange(params)
            }
            "requestUI" -> session.resourceState.requestPhoneScreen()
            else -> return null
        }
        check(session.revision < Long.MAX_VALUE) { "Revision exhausted" }
        session.revision++
        // Fresh maps; no reference to the parser's mutable input or Resource state.
        // Audio intent may be armed before a successful RTSP modesChanged reply.
        // The resource event path must never publish resource-2 in that window.
        val allowedAudio = claimAudio && session.explicitAudioClaimConfirmed
        val resources = session.resourceState.resourcesForModesChanged(allowedAudio).map { it.toMap() }
        return Snapshot(session.generation, controlIdentity, eventIdentity, session.revision, type, resources)
    }

    /**
     * Must be called when the audio-enabled/prepared decision changes outside
     * a changeModes/requestUI event, so a queued old mainAudio claim is stale.
     */
    @Synchronized
    fun invalidatePending(generation: Long, controlIdentity: Any, eventIdentity: Any): Boolean {
        val session = matching(generation, controlIdentity, eventIdentity) ?: return false
        check(session.revision < Long.MAX_VALUE) { "Revision exhausted" }
        session.revision++
        return true
    }

    /** Successful explicit B233 T85 main-audio declaration, never a generic event reassertion. */
    @Synchronized
    fun noteLocalAudioClaim(generation: Long, controlIdentity: Any, eventIdentity: Any): Boolean {
        val session = matching(generation, controlIdentity, eventIdentity) ?: return false
        if (!session.resourceState.requestMainAudioForSetup()) return false
        check(session.revision < Long.MAX_VALUE) { "Revision exhausted" }
        session.explicitAudioClaimConfirmed = true
        session.revision++
        return true
    }

    /** A new SETUP attempt, transport failure or video-only downgrade revokes it. */
    @Synchronized
    fun revokeLocalAudioClaim(generation: Long, controlIdentity: Any, eventIdentity: Any): Boolean {
        val session = matching(generation, controlIdentity, eventIdentity) ?: return false
        check(session.revision < Long.MAX_VALUE) { "Revision exhausted" }
        session.explicitAudioClaimConfirmed = false
        session.revision++
        return true
    }

    @Synchronized
    fun hasConfirmedAudioClaim(generation: Long, controlIdentity: Any, eventIdentity: Any): Boolean {
        return matching(generation, controlIdentity, eventIdentity)?.explicitAudioClaimConfirmed == true
    }

    /** Drop queued snapshots superseded by a newer event or a new session. */
    @Synchronized
    fun isLatest(snapshot: Snapshot): Boolean {
        val session = matching(snapshot.generation, snapshot.controlIdentity, snapshot.eventIdentity)
        return session != null && session.revision == snapshot.revision
    }

    private fun matching(generation: Long, controlIdentity: Any, eventIdentity: Any): Session? {
        val session = current ?: return null
        return session.takeIf {
            it.generation == generation && it.controlIdentity === controlIdentity && it.eventIdentity === eventIdentity
        }
    }
}

/** The reader enqueues a plan, never invokes a blocking RTSP write itself. */
internal class B233ResourceEventDriver(
    private val fence: B233ResourceSessionFence,
    private val executor: Executor,
    private val send: (B233ResourceSessionFence.Snapshot) -> Unit,
) {
    fun onEvent(
        generation: Long,
        controlIdentity: Any,
        eventIdentity: Any,
        command: Map<*, *>,
        claimAudio: Boolean,
    ): Boolean {
        val snapshot = fence.plan(generation, controlIdentity, eventIdentity, command, claimAudio) ?: return false
        val task = Runnable { if (fence.isLatest(snapshot)) send(snapshot) }
        return try {
            executor.execute(task)
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The dedicated bounded queue holds superseded snapshots. Preserve
            // the latest state by evicting stale queued jobs and retrying once.
            // Never execute network I/O on the event reader thread.
            val pool = executor as? java.util.concurrent.ThreadPoolExecutor ?: return false
            if (pool.isShutdown) return false
            pool.queue.clear() // B233 queue is bounded to 16.
            try {
                pool.execute(task)
                true
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                false
            }
        }
    }
}
