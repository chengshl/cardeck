package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B233ResourceEofEvidenceTest {
    @Test fun rtsp200AndScreenOnlyRequestNeverImplyMainAudioOwnership() {
        val evidence = B233ResourceEofEvidence()
        evidence.receiverChangeModes(mapOf(
            "type" to "changeModes",
            "params" to mapOf("resources" to listOf(mapOf("resourceID" to 1,
                "reasonStr" to "never-log-this-vehicle-secret")))
        ))
        evidence.claimMainAudio(1000)
        evidence.claimReply("RTSP/1.0 200 OK", bodyHasBytes = false)
        val summary = evidence.summaryAtPeerEof(21123)
        assertTrue(summary.contains("mainAudioClaimAttempts=1"))
        assertTrue(summary.contains("lastClaimReply=rtsp_200"))
        assertTrue(summary.contains("lastClaimAgeMs=20123"))
        assertTrue(summary.contains("incomingChangeModesScreenOnly=1"))
        assertTrue(summary.contains("incomingChangeModesMentionsAudio=0"))
        assertTrue(summary.contains("ownership=unverified"))
        assertFalse(summary.contains("never-log-this"))
        assertFalse(summary.contains("ownership=granted"))
    }

    @Test fun peerRequestWithResourceTwoIsMentionNotApproval() {
        val e = B233ResourceEofEvidence()
        e.receiverChangeModes(mapOf("type" to "changeModes", "params" to mapOf(
            "resources" to listOf(mapOf("resourceID" to 2L, "transferType" to 1,
                "reasonStr" to "secret")))))
        e.claimMainAudio(20)
        e.claimIoError()
        val result = e.summaryAtPeerEof(45)
        assertTrue(result.contains("incomingChangeModesMentionsAudio=1"))
        assertTrue(result.contains("lastClaimReply=io_error"))
        assertTrue(result.contains("ownership=unverified"))
        assertFalse(result.contains("secret"))
    }

    @Test fun noClaimAndUnknownResourceRemainUnverified() {
        val e = B233ResourceEofEvidence()
        e.receiverChangeModes(mapOf("type" to "changeModes",
            "params" to mapOf("resources" to listOf(mapOf("resourceID" to 99)))))
        e.claimReply("RTSP/1.0 200 OK", bodyHasBytes = true)
        assertEquals(
            "mainAudioClaimAttempts=0 lastClaimReply=none lastReplyBody=absent " +
                "lastClaimAgeMs=unknown incomingChangeModesScreenOnly=0 " +
                "incomingChangeModesMentionsAudio=0 ownership=unverified",
            e.summaryAtPeerEof(200)
        )
    }

    @Test fun non200PendingAndClockRollbackAreNeverTreatedAsSuccess() {
        val e = B233ResourceEofEvidence()
        e.claimMainAudio(5000)
        assertTrue(e.summaryAtPeerEof(4000).contains("lastClaimReply=pending"))
        assertTrue(e.summaryAtPeerEof(4000).contains("lastClaimAgeMs=unknown"))
        e.claimReply("RTSP/1.0 455 Method Not Valid", bodyHasBytes = true)
        assertTrue(e.summaryAtPeerEof(6500).contains("lastClaimReply=rtsp_non_200"))
        assertTrue(e.summaryAtPeerEof(6500).contains("lastReplyBody=nonempty"))
        assertTrue(e.summaryAtPeerEof(9_000_000).contains("lastClaimAgeMs=600000"))
    }

    @Test fun untrustedInputIsBoundedAndCannotLeakText() {
        val e = B233ResourceEofEvidence()
        repeat(1100) { e.claimMainAudio(it.toLong()) }
        repeat(1100) { e.receiverChangeModes(mapOf("type" to "changeModes",
            "params" to mapOf("resources" to listOf(
                mapOf("resourceID" to 1, "identifier" to "PRIVATE_ADDRESS"))))) }
        val out = e.summaryAtPeerEof(1500)
        assertTrue(out.contains("mainAudioClaimAttempts=1000"))
        assertTrue(out.contains("incomingChangeModesScreenOnly=1000"))
        assertFalse(out.contains("PRIVATE_ADDRESS"))
    }
}
