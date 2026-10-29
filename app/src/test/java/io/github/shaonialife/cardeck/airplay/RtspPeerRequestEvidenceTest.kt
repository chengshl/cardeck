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
        val summary = RtspPeerRequestEvidence.diagnosticTag(kind, true)
        assertEquals("kind=OTHER_REQUEST pendingCseqMatch=true", summary)
        assertEquals(null, RtspPeerRequestEvidence.diagnosticTag(
            RtspPeerRequestEvidence.Kind.RESPONSE_OR_UNKNOWN, true,
        ))
        assertEquals(
            "kind=TEARDOWN pendingCseqMatch=false",
            RtspPeerRequestEvidence.diagnosticTag(RtspPeerRequestEvidence.Kind.TEARDOWN, false),
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
