#!/usr/bin/env python3
"""Generate local-only literal substitutions for historical privacy preflight.

Never prints or persists original matches in logs/artifacts; the untracked rules
file exists only in the disposable GitHub Actions runner.
"""
from __future__ import annotations
import ipaddress
import pathlib
import re
import subprocess
import sys

IP = re.compile(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])")
MAC = re.compile(r"\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b", re.I)
HOST = re.compile(r"\b[a-z0-9.-]+\." + "once" + r"\." + r"wiki\b", re.I)
ROOT = re.compile("/" + "data" + r"/(?:files|cert)(?:/[\w.-]+)*", re.I)
KEY = re.compile(r"(?:ghp_|github_pat_|sk-)[a-z0-9_-]{20,}|AIza[a-z0-9_-]{20,}", re.I)
DOCUMENTATION_NETWORKS = tuple(
    ipaddress.ip_network(x) for x in
    ("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", "198.18.0.0/15")
)


def read_blobs():
    lines = subprocess.check_output(["git", "rev-list", "--objects", "--all"]).splitlines()
    process = subprocess.Popen(["git", "cat-file", "--batch"],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    assert process.stdin and process.stdout
    try:
        for item in lines:
            if b" " not in item:
                continue
            oid, raw_path = item.split(b" ", 1)
            process.stdin.write(oid + b"\n")
            process.stdin.flush()
            head = process.stdout.readline().split()
            if len(head) != 3:
                raise RuntimeError("Malformed object stream")
            size = int(head[2])
            raw = process.stdout.read(size)
            process.stdout.read(1)
            if head[1] != b"blob" or size > 12 * 1024 * 1024 or b"\0" in raw[:8192]:
                continue
            path = raw_path.decode("utf-8", "replace")
            yield path, raw.decode("utf-8", "replace")
    finally:
        process.stdin.close()
        process.stdout.close()
        process.wait(timeout=20)


def main():
    hostnames, ipv4s, macs, paths = set(), set(), set(), set()
    # This is the known public Catplay placeholder used by a current unit
    # test, not an observed vehicle identifier. Do not rewrite active tests.
    fixture = subprocess.check_output([
        "git", "show",
        "HEAD:app/src/test/java/io/github/shaonialife/cardeck/airplay/SessionSetupBodyTest.kt"
    ]).decode("utf-8", "replace")
    fixture_macs = {MAC.findall(fixture)[0].lower()}
    # The MCP unit test also contains a deliberately synthetic device MAC.
    for ref in ("refs/remotes/origin/Buick-Test", "refs/heads/Buick-Test"):
        result = subprocess.run(["git", "show", f"{ref}:tools/mcp/test_server.py"],
                                capture_output=True, check=False)
        if result.returncode == 0:
            fixture_macs.update(m.lower() for m in MAC.findall(
                result.stdout.decode("utf-8", "replace")))
            break
    else:
        raise RuntimeError("Current Buick-Test public fixture could not be loaded")
    for path, content in read_blobs():
        if KEY.search(content):
            raise RuntimeError("Possible credential requires rotation and explicit handling before rewrite")
        hostnames.update(HOST.findall(content))
        paths.update(ROOT.findall(content))
        for address in IP.findall(content):
            try:
                ip = ipaddress.ip_address(address)
            except ValueError:
                continue
            if ip.is_loopback or ip.is_unspecified or str(ip) == "255.255.255.255":
                continue
            if any(ip in n for n in DOCUMENTATION_NETWORKS):
                continue
            ipv4s.add(address)
        if pathlib.PurePosixPath(path).suffix.lower() in (".md", ".txt", ".json", ".yml", ".yaml", ".properties"):
            macs.update(m for m in MAC.findall(content)
                        if m.lower() not in ("00:00:00:00:00:00", "02:00:00:00:00:00") and m.lower() not in fixture_macs)

    # Each original /24 receives its own reserved benchmark /24 to retain
    # same-network vs cross-network semantics in old tests and examples.
    prefixes = sorted({".".join(x.split(".")[:3]) for x in ipv4s})
    if len(prefixes) > 512:
        raise RuntimeError("Too many historical IP subnet groups for safe deterministic mapping")
    mapping = {prefix: "198." + ("18" if index < 256 else "19") + "." + str(index % 256)
               for index, prefix in enumerate(prefixes)}
    rules = []
    for address in sorted(ipv4s):
        segments = address.split(".")
        new = mapping[".".join(segments[:3])] + "." + segments[3]
        rules.append(("literal:" + address, new))
    for mac in sorted(macs):
        rules.append(("literal:" + mac, "[MAC]"))
    for host in sorted(hostnames):
        rules.append(("literal:" + host, "redacted-host.invalid"))
    # Longer path rules precede shorter matches to avoid leaking path suffixes.
    for path in sorted(paths, key=lambda x: (-len(x), x)):
        rules.append(("literal:" + path, "[PRIVATE_PATH]"))
    # Avoid ever publishing/printing the rules file, which temporarily contains
    # the original private strings.
    dest = pathlib.Path(sys.argv[1])
    dest.write_text("".join(f"{old}==>{new}\n" for old, new in rules), encoding="utf-8")
    dest.chmod(0o600)
    print("Preflight categories (values suppressed):",
          "IPv4", len(ipv4s), "MAC", len(macs),
          "hosts", len(hostnames), "paths", len(paths))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Historical rewrite preflight stopped:", type(error).__name__, file=sys.stderr)
        raise SystemExit(1)
