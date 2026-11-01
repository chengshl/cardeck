package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSenderCallbackOwnerTest {
    @Test
    fun queuedOldSyncRefusalCannotMutateReplacementWithSameProfile() {
        // Both callbacks belong to the same client/profile; only sender identity differs.
        val oldSender = Any()
        val replacementSender = Any()
        val oldCallback = AudioSenderCallbackOwner().apply { bind(oldSender) }
        val newCallback = AudioSenderCallbackOwner().apply { bind(replacementSender) }
        var activeSender: Any? = oldSender
        var handled = 0
        fun dispatch(owner: AudioSenderCallbackOwner) {
            if (owner.owns(activeSender)) handled++
        }

        dispatch(oldCallback)
        assertEquals(1, handled)
        activeSender = replacementSender
        dispatch(oldCallback) // Delayed Bg.run from the retired sender.
        assertEquals(1, handled)
        dispatch(newCallback)
        assertEquals(2, handled)
    }

    @Test
    fun unboundNullAndRetiredSendersCannotPassOwnership() {
        val sender = Any()
        val gate = AudioSenderCallbackOwner()
        assertFalse(gate.owns(sender))
        gate.bind(sender)
        assertTrue(gate.owns(sender))
        assertFalse(gate.owns(null))
        assertFalse(gate.owns(Any()))
    }
}
