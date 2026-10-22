package io.github.shaonialife.cardeck.gateway

import java.util.concurrent.CopyOnWriteArrayList

/** Process-local UI bridge; no credentials or log text in published state. */
object GatewayLogRuntime {
    @Volatile var running = false
        internal set
    @Volatile var probe: GatewayProbe? = null
        internal set
    @Volatile var snapshot: GatewayProbe.Snapshot? = null
        private set
    private val listeners = CopyOnWriteArrayList<(GatewayProbe.Snapshot) -> Unit>()

    fun publish(value: GatewayProbe.Snapshot) {
        snapshot = value
        listeners.forEach { listener -> runCatching { listener(value) } }
    }

    fun subscribe(listener: (GatewayProbe.Snapshot) -> Unit): AutoCloseable {
        listeners.add(listener)
        snapshot?.let(listener)
        return AutoCloseable { listeners.remove(listener) }
    }
}
