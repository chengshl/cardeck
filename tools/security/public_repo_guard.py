#!/usr/bin/env python3
"""Fail CI on accidental private infrastructure or credential material in tracked files.

This is a conservative pattern scan, not a replacement for full-history secret
scanning. It never echoes matching file contents or candidate secrets.
"""
from __future__ import annotations

import ipaddress
import re
import subprocess
import sys
from pathlib import Path

DOC_RANGES = tuple(ipaddress.ip_network(net) for net in
                   ("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", "198.18.0.0/15"))
IP_PATTERN = re.compile(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])")
MAC_PATTERN = re.compile(r"\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b")
PRIVATE_DOMAIN = re.compile(r"[A-Za-z0-9.-]+\." + "once" + r"\." + "wiki\b", re.I)
PRIVATE_STORAGE = re.compile("/" + "data" + r"/(?:files|cert)(?:/|\b)")
# Never commit a Bluetooth/SSID name derived from the user's specific vehicle.
# Car model and protocol family labels (e.g. B233 and Buick-E5) remain allowed.
VEHICLE_IDENTIFIER = re.compile(
    r"(?i)\b(?:BUICK|CHEVROLET|CADILLAC)[_-]\d{3,}(?:[-_][A-Za-z0-9]+)?\b"
)
DIAGNOSTIC_FILENAME = re.compile(
    r"(?i)^(?:CarDeck-log-|CarDeck-system-crash|bugreport[-_]|sysdiagnose[-_])"
)

CREDENTIAL_ASSIGNMENT = re.compile(
    r"""(?i)\b(?:access[_-]?token|api[_-]?key|password|passwd|read[_-]?token|secret)\s*[:=]\s*["']?([A-Za-z0-9+/=_-]{24,})"""
)
PRIVATE_KEY_HEADER = re.compile(r"-----BEGIN\s+(?:RSA |EC |OPENSSH )?PRIVATE\s+KEY-----")
PROVIDER_KEYS = re.compile(
    r"(?:gh" + r"p_|github_pat_|sk-)[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|AKIA[0-9A-Z]{16}"
)
TEXT_EXTENSIONS = {
    ".kt", ".java", ".py", ".md", ".txt", ".sh", ".yml", ".yaml", ".json",
    ".xml", ".properties", ".gradle", ".kts", ".toml", ".cfg", ".ini",
}
PRIVATE_FILE_SUFFIXES = (".pem", ".p12", ".pfx", ".jks", ".keystore", ".key", ".p8")
DOC_MARKER_MAC = {"00:00:00:00:00:00", "02:00:00:00:00:00"}


def reasons(path: Path, contents: str) -> list[str]:
    issues: set[str] = set()
    if PRIVATE_DOMAIN.search(contents):
        issues.add("private project domain")
    if PRIVATE_STORAGE.search(contents):
        issues.add("private filesystem location")
    if VEHICLE_IDENTIFIER.search(contents):
        issues.add("specific vehicle Bluetooth or hotspot identifier")
    if PRIVATE_KEY_HEADER.search(contents) or PROVIDER_KEYS.search(contents):
        issues.add("possible credential or private key")
    if CREDENTIAL_ASSIGNMENT.search(contents):
        issues.add("long literal credential assignment")
    if path.suffix.lower() in {".md", ".yaml", ".yml", ".json"}:
        for mac in MAC_PATTERN.findall(contents):
            if mac.lower() not in DOC_MARKER_MAC:
                issues.add("device MAC address in document/config")
                break
    for match in IP_PATTERN.findall(contents):
        try:
            ip = ipaddress.ip_address(match)
        except ValueError:
            continue
        if ip.is_loopback or ip.is_unspecified or ip == ipaddress.ip_address("255.255.255.255"):
            continue
        if any(ip in net for net in DOC_RANGES):
            continue
        issues.add("non-documentation IPv4 address")
        break
    return sorted(issues)


def main() -> int:
    files = subprocess.check_output(["git", "ls-files", "-z"]).split(b"\0")
    violations: list[tuple[str, str]] = []
    checked = 0
    for filename in files:
        if not filename:
            continue
        path = Path(filename.decode("utf-8", "surrogateescape"))
        if DIAGNOSTIC_FILENAME.search(path.name):
            violations.append((str(path), "tracked private diagnostic attachment"))
            continue
        if path.suffix.lower() in PRIVATE_FILE_SUFFIXES or path.name.startswith(".env"):
            violations.append((str(path), "tracked private filename"))
            continue
        if path.suffix.lower() not in TEXT_EXTENSIONS and path.name not in {".gitignore", "Dockerfile", "gradlew", "AGENTS.md"}:
            continue
        raw = path.read_bytes()
        if b"\0" in raw:
            continue
        try:
            contents = raw.decode("utf-8")
        except UnicodeDecodeError:
            continue
        checked += 1
        for issue in reasons(path, contents):
            violations.append((str(path), issue))
    for filename, issue in violations:
        print(f"Public-tree finding: {filename}: {issue}")
        print(f"::error file={filename}::Public-tree privacy guard: {issue}")
    print(f"Public-tree privacy guard scanned {checked} text files; {len(violations)} finding(s).")
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
