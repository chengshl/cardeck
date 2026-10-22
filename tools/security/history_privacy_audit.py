#!/usr/bin/env python3
"""Read-only audit of all Git objects reachable from local fetched refs.

No detected addresses, emails, credentials or raw blobs are printed or uploaded.
The JSON report contains file paths, finding categories and counts only.
"""
from __future__ import annotations

import collections
import ipaddress
import json
import pathlib
import re
import subprocess
import sys

PROJECT_HOST = re.compile(r"\b[a-z0-9.-]+\." + "once" + r"\." + "wiki\b", re.I)
STORAGE_PATH = re.compile("/" + "data" + r"/(?:files|cert)(?:/|\b)", re.I)
IPV4 = re.compile(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])")
MAC = re.compile(r"\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b", re.I)
KEY_HEADER = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?" + r"PRIVATE KEY-----")
PROVIDER_KEY = re.compile(r"(?:ghp_|github_pat_|sk-)[a-z0-9_-]{20,}|AIza[a-z0-9_-]{20,}|AKIA[0-9A-Z]{16}", re.I)
LONG_SECRET = re.compile(
    r"""(?i)(?:token|password|api[_-]?key|secret|credential)\s*[:=]\s*["']?[A-Za-z0-9+/_=-]{24,}"""
)
DOCUMENTATION_NETWORKS = tuple(
    ipaddress.ip_network(s) for s in
    ("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", "198.18.0.0/15")
)
DOC_EXTS = {".md", ".txt", ".yaml", ".yml", ".json", ".toml", ".properties"}
SKIP_EXTS = {".apk", ".jpg", ".jpeg", ".png", ".webp", ".woff", ".ttf", ".ico", ".jar"}
MAX_SIZE = 12 * 1024 * 1024


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args], stderr=subprocess.DEVNULL)


def report_refs() -> dict:
    lines = git("for-each-ref", "--format=%(refname)").decode().splitlines()
    groups = collections.Counter()
    for name in lines:
        if name.startswith("refs/remotes/origin/"):
            groups["remote_branches"] += 1
        elif name.startswith("refs/tags/"):
            groups["tags"] += 1
        elif name.startswith("refs/audit-pr/"):
            groups["pull_request_refs"] += 1
        else:
            groups["other_refs"] += 1
    return dict(groups)


def audit_blob(path: str, raw: bytes) -> dict[str, int]:
    if b"\0" in raw[:8192] or pathlib.PurePosixPath(path).suffix.lower() in SKIP_EXTS:
        return {}
    value = raw.decode("utf-8", errors="replace")
    issues: dict[str, int] = {}
    def flag(key: str, count: int) -> None:
        if count:
            issues[key] = count

    flag("project_domain", len(PROJECT_HOST.findall(value)))
    flag("private_storage_path", len(STORAGE_PATH.findall(value)))
    flag("private_key_header", len(KEY_HEADER.findall(value)))
    flag("provider_key_pattern", len(PROVIDER_KEY.findall(value)))
    flag("long_secret_assignment", len(LONG_SECRET.findall(value)))

    addresses = []
    for match in IPV4.findall(value):
        try:
            ip = ipaddress.ip_address(match)
        except ValueError:
            continue
        if ip.is_loopback or ip.is_unspecified or ip == ipaddress.ip_address("255.255.255.255"):
            continue
        if any(ip in net for net in DOCUMENTATION_NETWORKS):
            continue
        addresses.append(ip)
    flag("non_documentation_ipv4", len(addresses))
    if pathlib.PurePosixPath(path).suffix.lower() in DOC_EXTS:
        flag("device_mac_in_document", sum(
            m.lower() not in ("00:00:00:00:00:00", "02:00:00:00:00:00")
            for m in MAC.findall(value)
        ))
    return issues


def audit_objects() -> dict:
    # rev-list emits each reachable object once and supplies a representative path.
    lines = git("rev-list", "--objects", "--all").splitlines()
    objects = [(line.split(b" ", 1)[0].decode(),
                line.split(b" ", 1)[1].decode("utf-8", "replace") if b" " in line else "")
               for line in lines]
    findings: dict[str, collections.Counter] = {}
    counts = collections.Counter()
    p = subprocess.Popen(
        ["git", "cat-file", "--batch"], stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL
    )
    assert p.stdin is not None and p.stdout is not None
    try:
        for object_id, path in objects:
            if not path:
                continue
            p.stdin.write((object_id + "\n").encode())
            p.stdin.flush()
            header = p.stdout.readline()
            parts = header.strip().split()
            if len(parts) != 3:
                raise RuntimeError("Unexpected git cat-file header")
            object_type, size = parts[1], int(parts[2])
            raw = p.stdout.read(size)
            p.stdout.read(1)  # trailing line-feed
            if len(raw) != size:
                raise RuntimeError("Truncated git blob read")
            if object_type != b"blob":
                continue
            counts["historical_blobs_checked"] += 1
            if size > MAX_SIZE:
                counts["large_blobs_not_content_scanned"] += 1
                continue
            issues = audit_blob(path, raw)
            if issues:
                counts["blobs_with_findings"] += 1
                findings.setdefault(path, collections.Counter()).update(issues)
    finally:
        p.stdin.close()
        p.stdout.close()
        p.wait(timeout=20)
    # No matched values are included in this report.
    return {
        "counts": dict(counts),
        "finding_categories": dict(sum((c for c in findings.values()), collections.Counter())),
        "affected_paths": [
            {"path": k, "categories": dict(v)}
            for k, v in sorted(findings.items())
        ],
    }


def audit_commit_emails() -> dict:
    entries = git("log", "--all", "--format=%ae%x09%ce").decode("utf-8", "replace").splitlines()
    direct = 0
    for row in entries:
        for address in row.split("\t"):
            if "@" in address and not address.lower().endswith("noreply.github.com"):
                direct += 1
    return {"commits_checked": len(entries), "author_committer_direct_email_occurrences": direct}


def main() -> int:
    data = {
        "scope": "all locally fetched reachable refs (branches, tags, available PR heads)",
        "refs": report_refs(),
        "objects": audit_objects(),
        "commit_metadata": audit_commit_emails(),
        "limitations": [
            "Not a scan of GitHub caches, deleted refs, Actions logs or external forks",
            "No secret validity test; rotate any independently verified exposed credential",
            "This is read-only and does not rewrite commits or publish sensitive values"
        ],
    }
    dest = pathlib.Path("history-privacy-audit-report.json")
    dest.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = data["objects"]
    print("History privacy audit completed (no sensitive values printed).")
    print("Historical blobs checked:", summary["counts"].get("historical_blobs_checked", 0))
    print("Blobs flagged:", summary["counts"].get("blobs_with_findings", 0))
    print("Affected paths:", len(summary["affected_paths"]))
    print("Finding categories:", sorted(summary["finding_categories"]))
    print("Commit records scanned:", data["commit_metadata"]["commits_checked"])
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print("History audit failed safely:", type(exc).__name__, file=sys.stderr)
        sys.exit(1)
