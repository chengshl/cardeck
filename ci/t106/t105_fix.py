#!/usr/bin/env python3
"""Post-T104 v6 fail-closed B233 patch. Strictly from T101+validated T104-v6 checkout.

Mutates ONLY the isolated GitLab Runner's temporary full Android checkout.
Neither signs nor publishes. Fails if one of the fixed anchors has drifted.
"""
import argparse
from pathlib import Path
import sys

CLIENT = 'app/src/main/java/io/github/shaonialife/cardeck/airplay/AirPlayClient.kt'
SESSION = 'app/src/main/java/io/github/shaonialife/cardeck/SessionController.kt'
CLASS_FILE = 'app/src/main/java/io/github/shaonialife/cardeck/airplay/B233ModeReplyGate.kt'
TEST_FILE = 'app/src/test/java/io/github/shaonialife/cardeck/airplay/B233ModeReplyGateTest.kt'

def one(raw, old, new, label):
    n = raw.count(old)
    if n != 1:
        raise ValueError(f'STOP_T105_ANCHOR_{label}_count={n}')
    return raw.replace(old, new, 1)

def patch_session(s):
    return one(s, '''                client.noteB233ExplicitAudioClaim()
''', '''                if (!client.noteB233ExplicitAudioClaim()) {
                    client.b233AudioClaimPrepared = false
                    client.revokeB233ExplicitAudioClaim()
                    appendLog("AUDIO T105: B233 local audio claim rejected/stale; block SETUP and retain video")
                    return false
                }
''', 'claim_return')

def patch_client(s):
    s = one(s, '''    internal fun revokeB233ExplicitAudioClaim(): Boolean {
        if (!endpoint.isBuickB233) return false
        val binding = b233ResourceBinding ?: return false
        return b233ResourceFence.revokeLocalAudioClaim(
            binding.generation, binding.controlSocket, binding.eventSocket,
        )
    }
''', '''    internal fun revokeB233ExplicitAudioClaim(): Boolean {
        if (!endpoint.isBuickB233) return false
        // Same lock as the final snapshot validity check AND RTSP socket write.
        // A downgrade cannot slip between the final check and the wire write.
        return synchronized(rtspWriteLock) {
            val binding = b233ResourceBinding ?: return@synchronized false
            b233ResourceFence.revokeLocalAudioClaim(
                binding.generation, binding.controlSocket, binding.eventSocket,
            )
        }
    }
''', 'revoke_write_lock')
    s = one(s, '''    internal fun noteB233ExplicitAudioClaim(): Boolean {
        if (!endpoint.isBuickB233) return false
        val binding = b233ResourceBinding ?: return false
        return b233ResourceFence.noteLocalAudioClaim(
            binding.generation, binding.controlSocket, binding.eventSocket,
        )
    }
''', '''    internal fun noteB233ExplicitAudioClaim(): Boolean {
        if (!endpoint.isBuickB233) return false
        // Pair with revoke and the socket write boundary; never arm an obsolete
        // event/control binding while another command is physically writing.
        return synchronized(rtspWriteLock) {
            val binding = b233ResourceBinding ?: return@synchronized false
            b233ResourceFence.noteLocalAudioClaim(
                binding.generation, binding.controlSocket, binding.eventSocket,
            )
        }
    }
''', 'claim_write_lock')
    s = one(s, '''                contentType = "application/x-apple-binary-plist",
                expectedSocket = expected,
            )
            // A 200 confirms only RTSP transport, not receiver resource acceptance.
            val transportOk = Regex("^(?:RTSP|HTTP)/[0-9.]+\\\\s+200(?:\\\\s|$)").containsMatchIn(status)
            log("AUDIO T104: B233 resource response transport200=$transportOk " +
                "bodyBytes=${response.size} businessAcceptance=unknown")
''', '''                contentType = "application/x-apple-binary-plist",
                expectedSocket = expected,
                beforeSocketWrite = {
                    // Rechecked *inside* rtspWriteLock immediately before writing.
                    b233ResourceFence.isLatest(snapshot) && containsAudio == currentAudioClaim()
                },
            )
            // An empty bplist dictionary confirms a syntactically valid command
            // ACK only; it does NOT prove receiver resource ownership.
            val parsed = runCatching { BinaryPlist.parse(response) }.getOrNull()
            val verdict = B233ModeReplyGate.classify(status, response.isNotEmpty(), parsed)
            log("AUDIO T105: B233 resource response verdict=$verdict " +
                "bodyBytes=${response.size} businessAcceptance=unknown")
''', 'snapshot_wire_gate')
    s = one(s, '''        extraHeaders: List<Pair<String, String>> = emptyList(),
        expectedSocket: Socket? = null,
    ): Pair<String, ByteArray> {''', '''        extraHeaders: List<Pair<String, String>> = emptyList(),
        expectedSocket: Socket? = null,
        beforeSocketWrite: (() -> Boolean)? = null,
    ): Pair<String, ByteArray> {''', 'request_gate_signature')
    s = one(s, '''            if (expectedSocket != null && socket !== expectedSocket) throw IOException("RTSP 会话已更换")
            cseq = ++cSeq''', '''            if (expectedSocket != null && socket !== expectedSocket) throw IOException("RTSP 会话已更换")
            if (beforeSocketWrite != null && !beforeSocketWrite()) {
                throw IOException("B233 resource snapshot superseded or audio claim revoked")
            }
            cseq = ++cSeq''', 'request_atomic_gate')
    # Only modify assertModes; retain all legacy/non-B233 behavior.
    start = s.index('    fun assertModes(')
    end = s.index('\n    /**',start)
    fragment = s[start:end]
    fragment = one(fragment, '''            val (statusLine, _) =
''', '''            val (statusLine, responseBody) =
''','assert_modes_response_body')
    fragment = one(fragment, '''            statusLine.contains("200")
''', '''            if (endpoint.isBuickB233) {
                val parsed = runCatching { BinaryPlist.parse(responseBody) }.getOrNull()
                val verdict = B233ModeReplyGate.classify(statusLine, responseBody.isNotEmpty(), parsed)
                log("AUDIO T105: B233 modesChanged response=$verdict " +
                    "bodyBytes=${responseBody.size} businessAcceptance=unknown")
                verdict == B233ModeReplyGate.Verdict.ACK_ONLY
            } else {
                statusLine.contains("200")
            }
''','assert_modes_b233_gate')
    return s[:start] + fragment + s[end:]

GATE = '''package io.github.shaonialife.cardeck.airplay

/** Transport status and binary-plist validation, B233-only; ACK != allocation. */
internal object B233ModeReplyGate {
    internal enum class Verdict { BAD_STATUS, BAD_BODY, EXPLICIT_REJECTION, ACK_ONLY }

    fun classify(status: String, hasBody: Boolean, decoded: Any?): Verdict {
        // Exact RTSP version + status; not substring matching or an HTTP 200.
        if (!Regex("^RTSP/1\\\\.0 200(?:[ \\t].*)?$").matches(status.trim())) return Verdict.BAD_STATUS
        if (!hasBody || decoded !is Map<*, *>) return Verdict.BAD_BODY
        if (decoded["success"] == false || decoded["accepted"] == false) return Verdict.EXPLICIT_REJECTION
        val error = decoded["error"]
        if (error is Number && error.toLong() != 0L) return Verdict.EXPLICIT_REJECTION
        val statusCode = decoded["status"]
        if (statusCode is Number && statusCode.toLong() != 0L) return Verdict.EXPLICIT_REJECTION
        return Verdict.ACK_ONLY // no assertion of business-level resource grant
    }
}
'''
TEST='''package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class B233ModeReplyGateTest {
    private fun check(line: String, body: Boolean, decoded: Any?, expected: B233ModeReplyGate.Verdict) {
        assertEquals(expected, B233ModeReplyGate.classify(line, body, decoded))
    }
    @Test fun emptyBplistMapIsAckOnlyNotBusinessGrant() =
        check("RTSP/1.0 200 OK", true, emptyMap<String, Any>(), B233ModeReplyGate.Verdict.ACK_ONLY)
    @Test fun rejectsMisleadingStatusSubstring() =
        check("RTSP/1.0 500 Error 200", true, emptyMap<String, Any>(), B233ModeReplyGate.Verdict.BAD_STATUS)
    @Test fun rejectsHttp200InsteadOfRtsp() =
        check("HTTP/1.0 200 OK", true, emptyMap<String, Any>(), B233ModeReplyGate.Verdict.BAD_STATUS)
    @Test fun rejectsMissingAndMalformedBody() {
        check("RTSP/1.0 200 OK", false, null, B233ModeReplyGate.Verdict.BAD_BODY)
        check("RTSP/1.0 200 OK", true, "no-plist-map", B233ModeReplyGate.Verdict.BAD_BODY)
    }
    @Test fun deniesExplicitRejectionFields() {
        check("RTSP/1.0 200 OK", true, mapOf("success" to false), B233ModeReplyGate.Verdict.EXPLICIT_REJECTION)
        check("RTSP/1.0 200 OK", true, mapOf("status" to 17), B233ModeReplyGate.Verdict.EXPLICIT_REJECTION)
        check("RTSP/1.0 200 OK", true, mapOf("error" to 2), B233ModeReplyGate.Verdict.EXPLICIT_REJECTION)
    }
}
'''

def run(repo, apply):
    repo=Path(repo).resolve()
    files={CLIENT:patch_client((repo/CLIENT).read_text()), SESSION:patch_session((repo/SESSION).read_text())}
    for extra in (CLASS_FILE, TEST_FILE):
        if (repo/extra).exists():raise ValueError('STOP_T105_EXISTING_'+extra)
    files[CLASS_FILE]=GATE
    files[TEST_FILE]=TEST
    if apply:
        for name,content in files.items():
            p=repo/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
    print('T105_B233_PATCH_ANCHORS=PASS')
    print('T105_CHANGED_FILES=4')
    print('T105_APPLIED='+str(apply))

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('repo');ap.add_argument('--apply',action='store_true');args=ap.parse_args()
    try:run(args.repo,args.apply)
    except (OSError,ValueError) as e:
        print(str(e),file=sys.stderr);sys.exit(2)