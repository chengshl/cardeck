package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic RTSP start lines only: no vehicle capture, target, or identifiers. */
class RtspPeerRequestEvidenceTest {
    @Test fun peerTeardownIsRecognizedBeforeAnyCseqDispatch() {
        assertEquals(
            RtspPeerRequestEvidence.Kind.TEARDOWN,
            RtspPeerRequestEvidence.classify("TEARDOWN / RTSP/1.0"),
        )
        assertEquals(
            RtspPeerRequestEvidence.Kind.TEARDOWN,
            RtspPeerRequestEvidence.classify("TEARDOWN rtsp://receiver.invalid/session RTSP/1.0"),
        )
    }

    @Test fun ordinaryPeerRequestIsNotMisclassifiedAsTeardown() {
        assertEquals(
            RtspPeerRequestEvidence.Kind.OTHER_REQUEST,
            RtspPeerRequestEvidence.classify("GET_PARAMETER / RTSP/1.0"),
        )
    }

    @Test fun cseqCollisionEvidenceCoversOtherRequestsWithoutLeakingTarget() {
        val target = "rtsp://receiver.invalid/private-session"
        val kind = RtspPeerRequestEvidence.classify("SET_PARAMETER $target RTSP/1.0")
        assertEquals(RtspPeerRequestEvidence.Kind.OTHER_REQUEST, kind)
        // A matching pending CSeq routes to the response queue, not the peer ACK path.
        val summary = RtspPeerRequestEvidence.diagnosticTag(kind, true, false)
        assertEquals("kind=OTHER_REQUEST pendingCseqMatch=true legacyAckEligible=false", summary)
        // An absolute URI is a peer request, but the legacy path-only
        // isRequest predicate would not acknowledge it even without collision.
        val legacyRequestRecognized = "SET_PARAMETER $target RTSP/1.0"
            .split(" ")[1].startsWith("/")
        assertEquals(false, legacyRequestRecognized)
        assertEquals(
            "kind=OTHER_REQUEST pendingCseqMatch=false legacyAckEligible=false",
            RtspPeerRequestEvidence.diagnosticTag(kind, false, legacyRequestRecognized),
        )
        assertEquals(
            "kind=OTHER_REQUEST pendingCseqMatch=false legacyAckEligible=true",
            RtspPeerRequestEvidence.diagnosticTag(
                RtspPeerRequestEvidence.classify("SET_PARAMETER /session RTSP/1.0"),
                false,
                true,
            ),
        )
        assertEquals(null, RtspPeerRequestEvidence.diagnosticTag(
            RtspPeerRequestEvidence.Kind.RESPONSE_OR_UNKNOWN, true, false,
        ))
        assertEquals(
            "kind=TEARDOWN pendingCseqMatch=false legacyAckEligible=true",
            RtspPeerRequestEvidence.diagnosticTag(RtspPeerRequestEvidence.Kind.TEARDOWN, false, true),
        )
    }

    @Test fun unmatchedB233PeerRequestNeverEchoesSensitiveUri() {
        val secret = "synthetic-private-token-DO-NOT-LOG"
        val lines = listOf(
            "TEARDOWN rtsp://receiver.invalid/$secret RTSP/1.0",
            "SET_PARAMETER /stream?authorization=$secret RTSP/1.0",
        )
        assertEquals("peer_request=TEARDOWN", RtspPeerRequestEvidence.safeUnmatchedStartLine(lines[0]))
        assertEquals("peer_request=OTHER", RtspPeerRequestEvidence.safeUnmatchedStartLine(lines[1]))
        for (line in lines) {
            val safe = RtspPeerRequestEvidence.safeUnmatchedStartLine(line)
            assertEquals(false, safe.contains(secret))
            assertEquals(false, safe.contains("receiver.invalid"))
            assertEquals(false, safe.contains("/stream"))
        }
    }

    @Test fun unmatchedB233ResponsePreservesOnlyValidatedStatusCode() {
        val secret = "synthetic-private-reason"
        assertEquals(
            "response_status=454",
            RtspPeerRequestEvidence.safeUnmatchedStartLine("RTSP/1.0 454 $secret"),
        )
        assertEquals(
            "response_status=200",
            RtspPeerRequestEvidence.safeUnmatchedStartLine("RTSP/1.0 200 OK"),
        )
        assertEquals(
            "unrecognized",
            RtspPeerRequestEvidence.safeUnmatchedStartLine("RTSP/1.0 2000 $secret"),
        )
        assertEquals(
            "unrecognized",
            RtspPeerRequestEvidence.safeUnmatchedStartLine("RTSP/1.0 2OO $secret"),
        )
    }

    @Test fun malformedOrInjectedStartLinesDoNotEnterDiagnostics() {
        val secret = "synthetic-credential"
        val malformed = listOf(
            "RTSP/1.0",
            "NOT_RTSP /private?token=$secret HTTP/1.1",
            "SET_PARAMETER /private?token=$secret RTSP/1.0 extra",
            "",
        )
        for (line in malformed) {
            assertEquals("unrecognized", RtspPeerRequestEvidence.safeUnmatchedStartLine(line))
        }
        // The privacy summary must not alter the protocol classification used
        // for the existing request ACK decision.
        assertEquals(
            RtspPeerRequestEvidence.Kind.TEARDOWN,
            RtspPeerRequestEvidence.classify("TEARDOWN / RTSP/1.0"),
        )
    }

    @Test fun responsesAndMalformedLinesCannotInventPeerTeardown() {
        val notRequests = listOf(
            "RTSP/1.0 200 OK",
            "RTSP/1.0 454 Session Not Found",
            "TEARDOWN / RTSP/1.0 extra",
            "TEARDOWN not-a-path RTSP/1.0",
            "TEARDOWN / HTTP/1.1",
            "",
        )
        for (line in notRequests) {
            assertEquals(
                line,
                RtspPeerRequestEvidence.Kind.RESPONSE_OR_UNKNOWN,
                RtspPeerRequestEvidence.classify(line),
            )
        }
    }
}
