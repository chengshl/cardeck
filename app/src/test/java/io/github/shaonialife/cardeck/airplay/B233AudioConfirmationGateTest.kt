package io.github.shaonialife.cardeck.airplay

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards B233 T85 prepared=true vs successful RTSP transport=200 sequencing. */
class B233AudioConfirmationGateTest {
    private val requestUi = mapOf("type" to "requestUI")

    private class Deferred : Executor {
        val pending = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { pending.add(command) }
        fun drain() { while (pending.isNotEmpty()) pending.removeAt(0).run() }
    }

    @Test fun prepareDoesNotPermitMainAudioResource() {
        val f=B233ResourceSessionFence();val c=Any();val e=Any();val g=f.begin(c,e,null)
        assertFalse(f.hasConfirmedAudioClaim(g,c,e))
        val prepared=true
        val pending=f.plan(g,c,e,requestUi,claimAudio=prepared)!!
        assertEquals(listOf(1L),pending.resources.map{it["resourceID"]})
    }

    @Test fun transportSuccessExplicitlyConfirmsMainAudio() {
        val f=B233ResourceSessionFence();val c=Any();val e=Any();val g=f.begin(c,e,null)
        assertTrue(f.noteLocalAudioClaim(g,c,e))
        assertTrue(f.hasConfirmedAudioClaim(g,c,e))
        val snap=f.plan(g,c,e,requestUi,claimAudio=true)!!
        assertEquals(listOf(1L,2L),snap.resources.map{it["resourceID"]})
    }

    @Test fun failedTransportMustNeverMarkAudioConfirmed() {
        val f=B233ResourceSessionFence();val c=Any();val e=Any();val g=f.begin(c,e,null)
        // A failed RTSP declaration doesn't invoke noteLocalAudioClaim.
        assertTrue(f.revokeLocalAudioClaim(g,c,e))
        assertFalse(f.hasConfirmedAudioClaim(g,c,e))
        assertEquals(listOf(1L),f.plan(g,c,e,requestUi,true)!!.resources.map{it["resourceID"]})
    }

    @Test fun revokeDropsQueuedAudioModeAndSuppressesNewAudio() {
        val f=B233ResourceSessionFence();val c=Any();val e=Any();val g=f.begin(c,e,null)
        val queue=Deferred();val seen=mutableListOf<B233ResourceSessionFence.Snapshot>()
        val d=B233ResourceEventDriver(f,queue){seen.add(it)}
        assertTrue(f.noteLocalAudioClaim(g,c,e))
        assertTrue(d.onEvent(g,c,e,requestUi,true))
        assertTrue(f.revokeLocalAudioClaim(g,c,e))
        queue.drain()
        assertTrue(seen.isEmpty())
        assertTrue(d.onEvent(g,c,e,requestUi,true))
        queue.drain()
        assertEquals(listOf(1L),seen.single().resources.map{it["resourceID"]})
    }

    @Test fun secondSetupAttemptDoesNotInheritPriorConfirmation() {
        val f=B233ResourceSessionFence();val c=Any();val e=Any();val g=f.begin(c,e,null)
        assertTrue(f.noteLocalAudioClaim(g,c,e))
        assertTrue(f.revokeLocalAudioClaim(g,c,e))
        assertFalse(f.hasConfirmedAudioClaim(g,c,e))
        assertEquals(listOf(1L),f.plan(g,c,e,requestUi,true)!!.resources.map{it["resourceID"]})
        assertTrue(f.noteLocalAudioClaim(g,c,e))
        assertEquals(listOf(1L,2L),f.plan(g,c,e,requestUi,true)!!.resources.map{it["resourceID"]})
    }

    @Test fun staleSessionCannotConfirmOrRevokeReplacement() {
        val f=B233ResourceSessionFence()
        val c1=Any();val e1=Any();val g1=f.begin(c1,e1,null)
        val c2=Any();val e2=Any();val g2=f.begin(c2,e2,null)
        assertFalse(f.noteLocalAudioClaim(g1,c1,e1))
        assertFalse(f.revokeLocalAudioClaim(g1,c1,e1))
        assertFalse(f.hasConfirmedAudioClaim(g2,c2,e2))
        assertTrue(f.noteLocalAudioClaim(g2,c2,e2))
        assertTrue(f.hasConfirmedAudioClaim(g2,c2,e2))
    }

    @Test fun borrowedAudioCannotBeConfirmedOnFailedLocalReclaim() {
        val f=B233ResourceSessionFence();val c=Any();val e=Any();val g=f.begin(c,e,null)
        val borrow=mapOf("type" to "changeModes", "params" to mapOf("resources" to listOf(
            mapOf("resourceID" to 2L, "transferType" to 3L, "borrowID" to "token-1")
        )))
        f.plan(g,c,e,borrow,true)
        assertFalse(f.noteLocalAudioClaim(g,c,e))
        assertFalse(f.hasConfirmedAudioClaim(g,c,e))
        assertEquals(listOf(1L),f.plan(g,c,e,requestUi,true)!!.resources.map{it["resourceID"]})
    }
}
