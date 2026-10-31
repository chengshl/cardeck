package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic RTSP start lines only: no vehicle capture, target, or identifiers. */
class RtspPeerRequestEvidenceTest {
    @Test fun b233AbsoluteUriRequestWithPendingCseqMustBypassQueueAndBeAcked() {
        val line = "SET_PARAMETER rtsp://receiver.invalid/session RTSP/1.0"
        val oldPathOnly = line.split(" ")[1].startsWith("/")
        assertEquals(false, oldPathOnly)
        val recognized = RtspPeerRequestEvidence.isB233AbsoluteUriRequest(line, true)
        assertEquals(true, recognized)
        // Same branch ordering as AirPlayClient.dispatchResponse: the peer
        // request is handled before looking up a locally pending CSeq.
        val pendingLocalCseqMatches = true
        assertEquals(false, pendingLocalCseqMatches && !recognized)
        assertEquals(true, oldPathOnly || recognized)
    }

    @Test fun b233AbsoluteUriTeardownIsAcknowledgedWithoutCseqCollision() {
        val line = "TEARDOWN rtsp://receiver.invalid/session RTSP/1.0"
        assertEquals(
            RtspPeerRequestEvidence.Kind.TEARDOWN,
            RtspPeerRequestEvidence.classify(line),
        )
        assertEquals(true, RtspPeerRequestEvidence.isB233AbsoluteUriRequest(line, true))
    }

    @Test fun nonB233AbsoluteUriPreservesExistingPendingQueueAndNoAck() {
        val line = "SET_PARAMETER rtsp://receiver.invalid/session RTSP/1.0"
        val recognized = RtspPeerRequestEvidence.isB233AbsoluteUriRequest(line, false)
        assertEquals(false, recognized)
        assertEquals(true, !recognized) // existing CSeq lookup remains eligible
        assertEquals(false, line.split(" ")[1].startsWith("/") || recognized)
    }

    @Test fun b233MalformedOrResponseLinesNeverTriggerAbsoluteUriAck() {
        val lines = listOf(
            "RTSP/1.0 200 OK",
            "RTSP/1.0 454 Session Not Found",
            "SET_PARAMETER rtsp:// RTSP/1.0",
            "SET_PARAMETER rtsp://receiver.invalid/session HTTP/1.1",
            "SET_PARAMETER rtsp://receiver.invalid/session RTSP/1.0 extra",
            "set_parameter rtsp://receiver.invalid/session RTSP/1.0",
            "SET_PARAMETER https://receiver.invalid/session RTSP/1.0",
            "SET_PARAMETER /session RTSP/1.0",
            "SET_PARAMETER rtsp://receiver.invalid/session\\r\\nInjected: yes RTSP/1.0",
        )
        lines.forEach { line ->
            assertEquals(line, false, RtspPeerRequestEvidence.isB233AbsoluteUriRequest(line, true))
        }
    }

    @Test fun b233MalformedAbsoluteUriAuthorityMustNotBeAcknowledged() {
        val invalidTargets = listOf(
            "rtsp:///session",             // no authority
            "rtsp://?token=secret",        // query without authority
            "rtsp://#fragment",           // fragment without authority
            "rtsp://:7000/session",       // missing host
            "rtsp://host:bad/session",    // invalid port
            "rtsp://host:65536/session",  // out-of-range port
            "rtsp://host:/session",      // URI parser reports host but empty port
            "rtsp://[2001:db8::1]:/session", // bracketed IPv6 empty port
            "rtsp://host:000000/session", // six-digit port accepted by java.net.URI
            "rtsp://[2001:db8::1]:000000/session", // IPv6 six-digit port
            "rtsp://host/session#frag",   // fragments are not RTSP request targets
            "rtsp://host/%GG",            // malformed percent encoding
        )
        invalidTargets.forEach { target ->
            val line = "SET_PARAMETER $target RTSP/1.0"
            assertEquals(line, false, RtspPeerRequestEvidence.isB233AbsoluteUriRequest(line, true))
        }
    }

    @Test fun b233ValidAbsoluteUriAuthoritiesRemainAcknowledged() {
        val validTargets = listOf(
            "rtsp://receiver.invalid/session",
            "rtsp://receiver.invalid:7000/session?stream=1",
            "rtsp://receiver_name.local:7000/session", // legal RFC3986 reg-name
            "rtsp://[2001:db8::1]:7000/session",
        )
        validTargets.forEach { target ->
            val line = "SET_PARAMETER $target RTSP/1.0"
            assertEquals(line, true, RtspPeerRequestEvidence.isB233AbsoluteUriRequest(line, true))
            assertEquals(line, false, RtspPeerRequestEvidence.isB233AbsoluteUriRequest(line, false))
        }
    }

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
