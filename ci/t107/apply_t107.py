#!/usr/bin/env python3
"""T107 isolated B233 early mainAudio claim timing experiment.

Materialization order: pinned T101 + T104-v6 + T105 + T106 + THIS patch.
Public MIT Buick Link+ is a compatibility reference, not copied code.
This script never handles credentials, builds, publishes or opens network links.
"""
from __future__ import annotations
import argparse
import difflib
import hashlib
import sys
from pathlib import Path

TARGET = Path("app/src/main/java/io/github/shaonialife/cardeck/SessionController.kt")
INITIAL_ANCHOR = ("                            val claimAudioForCarPlay = "
                  "io.github.shaonialife.cardeck.airplay.AirPlayModeClaims.claimAudio(")
INITIAL_SEND = "                            client.assertModes(claimAudioResource = claimAudioForCarPlay)\n"
BLOCK_START = "            // T85: in B233 only, claim mainAudio just before the first actual\n"
BLOCK_END = "            // SETUP 失败不会扰动会话"

EARLY_PREPARE = """                            // T107 isolated B233 early-audio-claim timing comparison.
                            // Video-only / ordinary Bluetooth A2DP remains unchanged.
                            val t107EarlyB233Audio =
                                client.currentEndpoint.isBuickB233 && audioEnabledForSession
                            if (t107EarlyB233Audio) {
                                client.b233AudioClaimPrepared = true
                            }
"""
EARLY_SEND = """                            val t107InitialModesAccepted =
                                client.assertModes(claimAudioResource = claimAudioForCarPlay)
                            if (t107EarlyB233Audio) {
                                // RTSP ACK + local generation fence are not receiver business grant.
                                val localClaimReady =
                                    t107InitialModesAccepted && client.noteB233ExplicitAudioClaim()
                                if (!localClaimReady) {
                                    client.b233AudioClaimPrepared = false
                                    client.revokeB233ExplicitAudioClaim()
                                    appendLog("AUDIO T107: initial claim not ready; block later audio SETUP")
                                } else {
                                    appendLog("AUDIO T107: initial claim RTSP ACK; receiver grant unverified")
                                }
                            }
"""
BEFORE_AUDIO_SETUP = """            // T107: compare Buick Link+ early mainAudio timing in B233 audio mode.
            // The initial modesChanged is before screen SETUP; no late T85 repeat.
            if (client.currentEndpoint.isBuickB233) {
                if (!client.b233AudioClaimPrepared || !client.hasB233ConfirmedAudioClaim()) {
                    appendLog("AUDIO T107: initial claim absent; retain video and block audio SETUP")
                    return false
                }
                appendLog("AUDIO T107: initial B233 claim recorded; attempting audio SETUP")
            }
"""

def replace_once(src: str, old: str, new: str, label: str) -> str:
    count = src.count(old)
    if count != 1:
        raise ValueError(f"STOP={label}_ANCHORS_CHANGED count={count}")
    return src.replace(old, new, 1)

def transform(src: str) -> str:
    if "AUDIO T107:" in src or "t107EarlyB233Audio" in src:
        raise ValueError("STOP=ALREADY_PATCHED")
    start, end = src.find(BLOCK_START), src.find(BLOCK_END)
    if start < 0 or end <= start:
        raise ValueError("STOP=DELAYED_AUDIO_BLOCK_NOT_FOUND")
    previous = src[start:end]
    markers = (
        "AUDIO T85: delayed_mainAudio_claim before_audio_setup",
        "if (!client.noteB233ExplicitAudioClaim())",
        "client.assertModes(claimAudioResource = true)",
        "client.revokeB233ExplicitAudioClaim()",
    )
    for marker in markers:
        if marker not in previous:
            raise ValueError(f"STOP=NOT_T106_MATERIALIZED: {marker}")
    if previous.count("if (client.currentEndpoint.isBuickB233)") != 1:
        raise ValueError("STOP=DELAYED_BLOCK_SCOPE_CHANGED")
    src = src[:start] + BEFORE_AUDIO_SETUP + src[end:]
    src = replace_once(src, INITIAL_ANCHOR, EARLY_PREPARE + INITIAL_ANCHOR,
                       "INITIAL_PREPARE")
    src = replace_once(src, INITIAL_SEND, EARLY_SEND, "INITIAL_MODES")
    if not (src.index("val t107EarlyB233Audio") < src.index("val claimAudioForCarPlay")
            < src.index("val t107InitialModesAccepted")
            < src.index("val screen = client.runScreenStream")):
        raise ValueError("STOP=CLAIM_AFTER_SCREEN_SETUP")
    if "AUDIO T85: delayed_mainAudio_claim" in src:
        raise ValueError("STOP=OLD_LATE_CLAIM_REMAINS")
    return src

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("checkout", type=Path, help="fully materialized T106 source root")
    parser.add_argument("--apply", action="store_true", help="write the source change")
    parser.add_argument("--diff", type=Path, help="optional unified source diff")
    args = parser.parse_args()
    source = args.checkout / TARGET
    if not source.is_file():
        print("STOP=SOURCE_MISSING", file=sys.stderr)
        return 2
    old = source.read_text(encoding="utf-8")
    try:
        new = transform(old)
    except (ValueError, AssertionError) as error:
        print(str(error), file=sys.stderr)
        return 2
    diff = "".join(difflib.unified_diff(
        old.splitlines(keepends=True), new.splitlines(keepends=True),
        fromfile="a/" + str(TARGET), tofile="b/" + str(TARGET),
    ))
    if args.diff:
        args.diff.write_text(diff, encoding="utf-8")
    if args.apply:
        source.write_text(new, encoding="utf-8")
    print("T107_STATIC_TRANSFORM=PASS")
    print("MODE=" + ("APPLIED" if args.apply else "DRY_RUN"))
    print("FILES_CHANGED=1")
    print("OLD_SHA256=" + hashlib.sha256(old.encode()).hexdigest())
    print("NEW_SHA256=" + hashlib.sha256(new.encode()).hexdigest())
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
