package io.github.shaonialife.cardeck.service

import java.util.concurrent.atomic.AtomicReference

/**
 * Owns one current projection grant by object identity.
 *
 * Android may invoke MediaProjection.Callback.onStop for an expired grant
 * after a new permission dialog has already published a replacement.
 * This class prevents the delayed old callback from erasing the new grant.
 * It has no Android dependency and performs no capture or network action.
 */
internal class ProjectionLease<T : Any> {
    private val active = AtomicReference<T?>(null)

    fun current(): T? = active.get()

    fun publish(grant: T?): T? = active.getAndSet(grant)

    fun clearIfCurrent(stopped: T): Boolean = active.compareAndSet(stopped, null)
}
