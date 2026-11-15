"""Single-writer JSONL archive. Only owned, expired segments may be deleted."""
import errno
import json
import os
import re
import stat
import time
import uuid
import heapq
import base64
import hashlib
import hmac
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

FORMAT = {"format": "cardeck-log-archive", "version": 1}
SEGMENT = re.compile(r"(\d{13})-(\d{20})-([0-9a-f-]{36})\.jsonl")
REWRITE = re.compile(r"\.rewrite-[0-9a-f-]{36}\.tmp")


class ArchiveBusy(OSError):
    pass


class CursorExpired(ValueError):
    """Retention changed a snapshot; callers must start a new history query."""


MAX_TIMESTAMP_MS = 253402300799999


def validate_history_query(device, source, limit, vehicle_id, start_ms, end_ms,
                           keyword, time_field):
    canonical_device(device)
    if source not in (None, "app", "system") or type(limit) is not int or not 1 <= limit <= 200:
        raise ValueError("Invalid query")
    if vehicle_id is not None:
        canonical_device(vehicle_id)
        if uuid.UUID(vehicle_id).version != 4:
            raise ValueError("Invalid vehicle")
    for value in (start_ms, end_ms):
        if value is not None and (type(value) is not int or not 0 <= value <= MAX_TIMESTAMP_MS):
            raise ValueError("Invalid time range")
    if start_ms is not None and end_ms is not None and start_ms > end_ms:
        raise ValueError("Invalid time range")
    if keyword is not None and (not isinstance(keyword, str) or not 1 <= len(keyword) <= 128
                                or any(ord(c) < 32 or 127 <= ord(c) <= 159
                                       or 0xD800 <= ord(c) <= 0xDFFF for c in keyword)):
        raise ValueError("Invalid keyword")
    if time_field not in ("received_ms", "occurred_ms"):
        raise ValueError("Invalid time field")


def canonical_device(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError("Invalid installation ID")
    return value


def verify_mount(root, expected):
    if not expected:
        return
    root = str(Path(root).resolve(strict=True))
    matches = []
    with open("/proc/self/mountinfo", encoding="utf-8") as handle:
        for line in handle:
            left, right = line.rstrip().split(" - ", 1)
            fields, mount = left.split(), right.split()
            point = fields[4].replace("\\040", " ").replace("\\134", "\\")
            if root == point or root.startswith(point.rstrip("/") + "/"):
                matches.append((len(point), mount[0], mount[1], mount[2]))
    if not matches:
        raise OSError("Expected shared filesystem is unavailable")
    _, filesystem, source, options = max(matches)
    if (filesystem != expected["filesystem"] or source != expected["source"]
            or "nobrl" in options.split(",")):
        raise OSError("Shared filesystem identity or locking check failed")


def initialize_root(root, expected_mount=None):
    """Explicit bootstrap only; never adopt a directory containing unrelated files."""
    root = Path(root)
    if root.is_symlink() or not root.is_dir():
        raise OSError("Archive root must be an existing dedicated directory")
    verify_mount(root, expected_mount)
    marker = root / ".cardeck-archive.json"
    if marker.is_symlink():
        raise OSError("Invalid ownership marker")
    if marker.exists():
        if json.loads(marker.read_text(encoding="utf-8")) != FORMAT:
            raise OSError("Archive ownership mismatch")
        return
    if any(root.iterdir()):
        raise OSError("Refusing to adopt a nonempty directory")
    fd = os.open(marker, os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_BINARY", 0), 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(json.dumps(FORMAT).encode("utf-8"))
        handle.flush()
        os.fsync(handle.fileno())


@dataclass
class Segment:
    path: Path
    device: str
    source: str
    first_ms: int
    sequence: int
    size: int


class LogArchive:
    def __init__(self, root, max_file_bytes=16 * 1024 * 1024,
                 retention_seconds=7 * 86400, utc_offset_hours=8,
                 expected_mount=None, clock=time.time):
        if not 1024 <= max_file_bytes <= 1024 * 1024 * 1024:
            raise ValueError("Invalid segment limit")
        if not 0 < retention_seconds <= 7 * 86400:
            raise ValueError("Retention cannot exceed seven days")
        self.root = Path(root)
        if self.root.is_symlink() or not self.root.is_dir():
            raise OSError("Persistent archive is unavailable")
        verify_mount(self.root, expected_mount)
        marker = self.root / ".cardeck-archive.json"
        if marker.is_symlink() or json.loads(marker.read_text(encoding="utf-8")) != FORMAT:
            raise OSError("Archive ownership mismatch")
        self.root = self.root.resolve(strict=True)
        self.max_file_bytes = max_file_bytes
        self.retention_seconds = retention_seconds
        self.zone = timezone(timedelta(hours=utc_offset_hours))
        self.clock = clock
        self.segments = {}
        self.expiry = []
        self.current = {}
        self.sequence = 0
        self.deleted_expired = 0
        self.compacted_files = 0
        self.last_cleanup_ms = None
        self.lock = None
        self._query_key = os.urandom(32)
        try:
            path = self.root / ".writer.lock"
            if path.is_symlink():
                raise OSError("Invalid lock path")
            flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0)
            self.lock = os.fdopen(os.open(path, flags, 0o600), "r+b")
            if os.name == "nt":
                import msvcrt
                if os.fstat(self.lock.fileno()).st_size == 0:
                    self.lock.write(b"0")
                    self.lock.flush()
                self.lock.seek(0)
                msvcrt.locking(self.lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                try:
                    fcntl.flock(self.lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                except OSError as error:
                    if error.errno in (errno.EACCES, errno.EAGAIN):
                        raise ArchiveBusy("Archive already has a writer") from None
                    raise
            self.base = self.root / "logs"
            if self.base.is_symlink():
                raise OSError("Invalid archive directory")
            self.base.mkdir(exist_ok=True)
            self._scan()
            self.cleanup()
        except Exception:
            self.close()
            raise

    def _inside(self, path):
        path = Path(path)
        if path.is_symlink() or not path.resolve().is_relative_to(self.root):
            raise OSError("Archive path escaped its dedicated root")
        node = self.root
        for part in path.relative_to(self.root).parts:
            node = node / part
            if node.is_symlink():
                raise OSError("Symlinks are not allowed in archive paths")
        return path

    def _scan(self):
        for device in self.base.iterdir():
            if device.is_symlink():
                raise OSError("Symlinks are not allowed in the archive")
            try:
                canonical_device(device.name)
            except (ValueError, TypeError, AttributeError):
                continue
            if not device.is_dir():
                continue
            for day in device.iterdir():
                self._inside(day)
                try:
                    datetime.strptime(day.name, "%Y-%m-%d")
                except ValueError:
                    continue
                if not day.is_dir():
                    continue
                for source in day.iterdir():
                    self._inside(source)
                    if source.name not in ("app", "system") or not source.is_dir():
                        continue
                    for path in source.iterdir():
                        self._inside(path)
                        if REWRITE.fullmatch(path.name) and path.is_file():
                            # A crashed rewrite never replaced the original; its duplicate is uncommitted.
                            path.unlink()
                            continue
                        match = SEGMENT.fullmatch(path.name)
                        if not match or not path.is_file():
                            continue
                        first, sequence = int(match[1]), int(match[2])
                        segment = Segment(path, device.name, source.name, first, sequence, path.stat().st_size)
                        self.segments[path] = segment
                        heapq.heappush(self.expiry, (first, sequence, path))
                        self.sequence = max(self.sequence, sequence)

    def _new_segment(self, device, source, now_ms):
        day = datetime.fromtimestamp(now_ms / 1000, self.zone).strftime("%Y-%m-%d")
        parent = self.base / device / day / source
        self._inside(parent)
        parent.mkdir(parents=True, exist_ok=True)
        self.sequence += 1
        path = parent / f"{now_ms:013d}-{self.sequence:020d}-{uuid.uuid4()}.jsonl"
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0), 0o600)
        os.close(fd)
        segment = Segment(path, device, source, now_ms, self.sequence, 0)
        self.segments[path] = segment
        heapq.heappush(self.expiry, (now_ms, self.sequence, path))
        self.current[(device, source)] = segment
        return segment

    def append(self, device, source, lines, batch_id, vehicle_id=None, line_times_ms=None):
        canonical_device(device)
        if source not in ("app", "system") or not lines or len(lines) > 8:
            raise ValueError("Invalid log batch")
        if any(not isinstance(line, str) or not 1 <= len(line) <= 256 for line in lines):
            raise ValueError("Invalid log line")
        if line_times_ms is not None and (not isinstance(line_times_ms, list)
                or len(line_times_ms) != len(lines)
                or any(type(value) is not int or not 0 <= value <= MAX_TIMESTAMP_MS for value in line_times_ms)):
            raise ValueError("Invalid log timestamps")
        canonical_device(batch_id)
        if vehicle_id is not None:
            canonical_device(vehicle_id)
            if uuid.UUID(vehicle_id).version != 4:
                raise ValueError("Invalid anonymous vehicle ID")
        self.cleanup()
        now_ms = int(self.clock() * 1000)
        rows = [dict(source=source, received_ms=now_ms, batch_id=batch_id, text=line,
                     **({"vehicle_id": vehicle_id} if vehicle_id is not None else {}),
                     **({"occurred_ms": line_times_ms[index]} if line_times_ms is not None else {}))
                for index, line in enumerate(lines)]
        encoded = [(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8") for row in rows]
        if any(len(row) > self.max_file_bytes for row in encoded):
            raise ValueError("A record exceeds the segment limit")
        fd, opened = None, None
        try:
            for data in encoded:
                segment = self.current.get((device, source))
                if (segment is None or segment.first_ms // 3600000 != now_ms // 3600000
                        or segment.size + len(data) > self.max_file_bytes):
                    segment = self._new_segment(device, source, now_ms)
                if opened is not segment:
                    if fd is not None:
                        os.fsync(fd)
                        os.close(fd)
                        fd = None
                    self._inside(segment.path)
                    fd = os.open(segment.path, os.O_WRONLY | os.O_APPEND | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0))
                    opened = segment
                remaining = memoryview(data)
                while remaining:
                    count = os.write(fd, remaining)
                    if count <= 0:
                        raise OSError("Archive write made no progress")
                    segment.size += count
                    remaining = remaining[count:]
            if fd is not None:
                os.fsync(fd)  # ACK is sent only after every touched segment has been flushed.
        except Exception:
            # Never append a later acknowledged record behind a partially written JSON line.
            self.current.pop((device, source), None)
            raise
        finally:
            if fd is not None:
                os.close(fd)
        return rows

    def append_v2(self, device, source, lines, batch_id, vehicle_id, digest, line_times_ms=None):
        canonical_device(device); canonical_device(batch_id)
        if vehicle_id is not None: canonical_device(vehicle_id)
        if (source not in ("app", "system") or not 1 <= len(lines) <= 8
                or any(not isinstance(line, str) or not line or len(line.encode("utf-8")) > 12288 for line in lines)
                or not re.fullmatch(r"[0-9a-f]{64}", digest)):
            raise ValueError("Invalid v2 batch")
        if line_times_ms is not None and (not isinstance(line_times_ms, list)
                or len(line_times_ms) != len(lines)
                or any(type(t) is not int or not 0 <= t <= MAX_TIMESTAMP_MS for t in line_times_ms)):
            raise ValueError("Invalid occurred timestamps")
        self.cleanup()
        matches = [s for s in self.segments.values() if s.device == device
                   and s.path.name.endswith("-" + batch_id + ".jsonl")]
        if matches:
            if len(matches) != 1: raise ValueError("Ambiguous durable receipt")
            path = self._inside(matches[0].path)
            with path.open("rb") as handle: raw = handle.read(200000)
            rows = [json.loads(line) for line in raw.splitlines()]
            if (not raw.endswith(b"\n") or len(rows) != len(lines)
                    or any(r.get("batch_digest") != digest or r.get("schema_version") != 2
                        or r.get("ordinal") != i or r.get("text") != lines[i]
                        or r.get("source") != source or r.get("vehicle_id") != vehicle_id
                        or r.get("batch_id") != batch_id
                        or r.get("occurred_ms") != (line_times_ms[i] if line_times_ms is not None else None)
                        for i, r in enumerate(rows))):
                raise ValueError("Conflicting durable batch identity")
            # Complete an uncertain prior commit before acknowledging a retry.
            with path.open("rb") as handle: os.fsync(handle.fileno())
            directory = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
            try: os.fsync(directory)
            finally: os.close(directory)
            return rows
        now_ms = int(self.clock() * 1000)
        rows = [dict(source=source, received_ms=now_ms, batch_id=batch_id, text=line,
                     schema_version=2, batch_digest=digest, ordinal=i,
                     **({"vehicle_id": vehicle_id} if vehicle_id is not None else {}),
                     **({"occurred_ms": line_times_ms[i]} if line_times_ms is not None else {}))
                for i, line in enumerate(lines)]
        data = ("\n".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) for r in rows) + "\n").encode()
        if len(data) > self.max_file_bytes: raise ValueError("Batch exceeds segment limit")
        day = datetime.fromtimestamp(now_ms / 1000, self.zone).strftime("%Y-%m-%d")
        parent = self.base / device / day / source
        self._inside(parent); parent.mkdir(parents=True, exist_ok=True)
        self.sequence += 1
        target = parent / f"{now_ms:013d}-{self.sequence:020d}-{batch_id}.jsonl"
        temp = parent / f".rewrite-{uuid.uuid4()}.tmp"
        self._inside(target); self._inside(temp)
        # A failed fsync/rename returns no ACK; an interrupted temp is never queried.
        # Request a private creation mode without chmod: CIFS ACLs may disallow
        # changing mode after creation even when file creation/write is authorized.
        flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_NOFOLLOW", 0)
        with os.fdopen(os.open(temp, flags, 0o600), "wb") as handle:
            handle.write(data); handle.flush(); os.fsync(handle.fileno())
        os.replace(temp, target)
        # Track the committed name even if the directory flush returns an error.
        # A retry must find this batch, rather than creating a second segment.
        segment = Segment(target, device, source, now_ms, self.sequence, len(data))
        self.segments[target] = segment
        heapq.heappush(self.expiry, (now_ms, self.sequence, target))
        directory = os.open(parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try: os.fsync(directory)
        finally: os.close(directory)
        return rows

    def cleanup(self):
        now_ms = int(self.clock() * 1000)
        cutoff = now_ms - int(self.retention_seconds * 1000)
        while self.expiry and self.expiry[0][0] <= cutoff:
            first, sequence, path = self.expiry[0]
            segment = self.segments.get(path)
            if segment is None or segment.first_ms != first or segment.sequence != sequence:
                heapq.heappop(self.expiry)
                continue
            self._inside(segment.path)
            if (segment.first_ms // 3600000 + 1) * 3600000 > cutoff:
                replacement = self._compact_boundary(segment, cutoff)
                if replacement is not None:
                    heapq.heappop(self.expiry)
                    self.segments.pop(segment.path)
                    self.segments[replacement.path] = replacement
                    if self.current.get((segment.device, segment.source)) is segment:
                        self.current[(segment.device, segment.source)] = replacement
                    heapq.heappush(self.expiry, (replacement.first_ms, replacement.sequence, replacement.path))
                    self.compacted_files += 1
                    continue
            try:
                segment.path.unlink()
            except FileNotFoundError:
                pass
            self.segments.pop(segment.path)
            heapq.heappop(self.expiry)
            if self.current.get((segment.device, segment.source)) is segment:
                self.current.pop((segment.device, segment.source))
            self.deleted_expired += 1
            parent = segment.path.parent
            for _ in range(3):
                self._inside(parent)
                try:
                    parent.rmdir()  # Empty source/date/device directories only; never recursive deletion.
                except OSError as error:
                    if error.errno in (errno.ENOTEMPTY, errno.EEXIST, errno.ENOENT):
                        break
                    raise
                parent = parent.parent
        self.last_cleanup_ms = now_ms

    def _compact_boundary(self, segment, cutoff):
        """Remove expired rows without prematurely deleting younger rows in the boundary file."""
        temp = segment.path.parent / f".rewrite-{uuid.uuid4()}.tmp"
        output = None
        first_ms = None
        size = 0
        try:
            with segment.path.open("rb") as original:
                for data in original:
                    try:
                        row = json.loads(data)
                    except (ValueError, UnicodeDecodeError):
                        continue
                    if not isinstance(row, dict) or type(row.get("received_ms")) is not int or row["received_ms"] <= cutoff:
                        continue
                    if output is None:
                        first_ms = row["received_ms"]
                        output = temp.open("xb")
                    output.write(data)
                    size += len(data)
            if output is None:
                return None
            output.flush()
            os.fsync(output.fileno())
            output.close()
            output = None
            identity = SEGMENT.fullmatch(segment.path.name)[3]
            path = segment.path.with_name(f"{first_ms:013d}-{segment.sequence:020d}-{identity}.jsonl")
            self._inside(path)
            os.replace(temp, path)  # Original remains intact until its replacement has been flushed.
            segment.path.unlink()
            return Segment(path, segment.device, segment.source, first_ms, segment.sequence, size)
        finally:
            if output is not None:
                output.close()
            if temp.exists():
                self._inside(temp)
                temp.unlink()

    def query(self, device, source=None, limit=200, vehicle_id=None, start_ms=None,
              end_ms=None, keyword=None, cursor=None, cursor_key=None,
              time_field="received_ms", scan_budget_bytes=4 * 1024 * 1024):
        """Bounded forward scan of a signed archive snapshot, without exposing paths.

        A cursor points after the last inspected record, including nonmatches.
        Retention rewrites invalidate it explicitly rather than shifting offsets.
        Empty nonterminal pages are possible for sparse searches.
        """
        validate_history_query(device, source, limit, vehicle_id, start_ms, end_ms, keyword, time_field)
        if type(scan_budget_bytes) is not int or not 1 <= scan_budget_bytes <= 16 * 1024 * 1024:
            raise ValueError("Invalid scan budget")
        key = self._query_key if cursor_key is None else cursor_key
        if not isinstance(key, bytes) or not key:
            raise ValueError("Invalid cursor key")
        filters = [device, source, limit, vehicle_id, start_ms, end_ms, keyword, time_field]
        fingerprint = hashlib.sha256(json.dumps(filters, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
        now_ms = int(self.clock() * 1000)
        state = None
        if cursor is not None:
            try:
                if not isinstance(cursor, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,4096}", cursor):
                    raise ValueError()
                packed = base64.b64decode(cursor + "=" * (-len(cursor) % 4), altchars=b"-_", validate=True)
                body, signature = packed[:-32], packed[-32:]
                if not hmac.compare_digest(signature, hmac.digest(key, body, "sha256")):
                    raise ValueError()
                state = json.loads(body)
                if (not isinstance(state, dict) or set(state) != {"v", "q", "seq", "manifest", "caps", "last", "snapshot_ms", "cutoff_ms"}
                        or state["v"] != 1 or state["q"] != fingerprint
                        or type(state["seq"]) is not int or not 0 <= state["seq"] < 2 ** 63
                        or not isinstance(state["manifest"], str) or not re.fullmatch(r"[0-9a-f]{64}", state["manifest"])
                        or not isinstance(state["last"], list) or len(state["last"]) != 2
                        or any(type(n) is not int or n < 0 for n in state["last"])
                        or state["last"][0] > state["seq"]
                        or not isinstance(state["caps"], dict) or len(state["caps"]) > 2
                        or any(not re.fullmatch(r"[0-9]+", s) or int(s) > state["seq"]
                               or type(n) is not int or not 0 <= n <= self.max_file_bytes
                               for s, n in state["caps"].items())
                        or any(type(state[s]) is not int or not 0 <= state[s] <= MAX_TIMESTAMP_MS
                               for s in ("snapshot_ms", "cutoff_ms"))):
                    raise ValueError()
            except (ValueError, TypeError, KeyError, UnicodeError):
                raise ValueError("Invalid history cursor") from None
        max_sequence = state["seq"] if state is not None else self.sequence
        segments = sorted((s for s in self.segments.values() if s.device == device
                           and (source is None or s.source == source) and s.sequence <= max_sequence),
                          key=lambda s: s.sequence)
        manifest = hashlib.sha256(json.dumps([(s.sequence, s.source, s.path.name) for s in segments],
                                             separators=(",", ":")).encode()).hexdigest()
        if state is not None and state["manifest"] != manifest:
            raise CursorExpired("History snapshot expired; restart the query")
        if state is None:
            active = {s.sequence for (dev, kind), s in self.current.items()
                      if dev == device and (source is None or source == kind)}
            state = {"v": 1, "q": fingerprint, "seq": max_sequence, "manifest": manifest,
                     "caps": {str(s.sequence): s.size for s in segments if s.sequence in active},
                     "last": [0, 0], "snapshot_ms": now_ms,
                     "cutoff_ms": max(0, now_ms - int(self.retention_seconds * 1000))}
        cutoff = max(state["cutoff_ms"], now_ms - int(self.retention_seconds * 1000))
        needle = keyword.casefold() if keyword is not None else None
        rows, scanned, scanned_lines = [], 0, 0
        limited, has_more = False, False
        for index, segment in enumerate(segments):
            cap = state["caps"].get(str(segment.sequence), segment.size)
            if segment.sequence < state["last"][0] or cap == 0:
                continue
            offset = state["last"][1] if segment.sequence == state["last"][0] else 0
            if offset > cap:
                raise CursorExpired("History snapshot expired; restart the query")
            self._inside(segment.path)
            with segment.path.open("rb") as handle:
                continuation = False
                if offset:
                    handle.seek(offset - 1)
                    continuation = handle.read(1) != b"\n"
                handle.seek(offset)
                while handle.tell() < cap:
                    raw = handle.readline(min(25000, cap - handle.tell()))
                    if not raw:
                        raise CursorExpired("History snapshot expired; restart the query")
                    scanned += len(raw)
                    position = handle.tell()
                    state["last"] = [segment.sequence, position]
                    complete = raw.endswith(b"\n")
                    was_continuation, continuation = continuation, not complete
                    if complete:
                        scanned_lines += 1
                    if complete and not was_continuation:
                        try:
                            row = json.loads(raw)
                        except (ValueError, UnicodeError):
                            row = None
                        if (isinstance(row, dict) and type(row.get("received_ms")) is int
                                and state["cutoff_ms"] < row["received_ms"] <= state["snapshot_ms"]
                                and row.get("source") == segment.source and isinstance(row.get("text"), str)
                                and (vehicle_id is None or row.get("vehicle_id") == vehicle_id)):
                            stamp = row.get(time_field)
                            if (type(stamp) is int and (start_ms is None or stamp >= start_ms)
                                    and (end_ms is None or stamp <= end_ms)
                                    and (needle is None or needle in row["text"].casefold())):
                                if row["received_ms"] <= cutoff:
                                    # Cleanup can lag by a minute. Never silently
                                    # skip a snapshot match that aged out mid-query.
                                    raise CursorExpired("History snapshot expired; restart the query")
                                rows.append(row)
                    if len(rows) >= limit or scanned >= scan_budget_bytes:
                        has_more = position < cap or any(
                            state["caps"].get(str(s.sequence), s.size) > 0 for s in segments[index + 1:])
                        limited = has_more and scanned >= scan_budget_bytes and len(rows) < limit
                        break
                if len(rows) >= limit or scanned >= scan_budget_bytes:
                    break
        next_cursor = None
        if has_more:
            body = json.dumps(state, separators=(",", ":")).encode()
            next_cursor = base64.urlsafe_b64encode(body + hmac.digest(key, body, "sha256")).decode().rstrip("=")
        return {"logs": rows, "has_more": has_more, "next_cursor": next_cursor,
                "mode": "history", "order": "archive", "time_field": time_field,
                "snapshot_ms": state["snapshot_ms"], "scan_limited": limited, "scanned_lines": scanned_lines}

    def tail(self, device, source=None, limit=200, vehicle_id=None):
        canonical_device(device)
        if source not in (None, "app", "system") or not 1 <= limit <= 200:
            raise ValueError("Invalid query")
        if vehicle_id is not None:
            canonical_device(vehicle_id)
            if uuid.UUID(vehicle_id).version != 4:
                raise ValueError("Invalid anonymous vehicle ID")
        cutoff = int(self.clock() * 1000) - int(self.retention_seconds * 1000)
        combined = []
        for kind in ((source,) if source else ("app", "system")):
            rows = []
            segments = sorted((s for s in self.segments.values() if s.device == device and s.source == kind),
                              key=lambda item: (item.first_ms, item.sequence), reverse=True)
            for segment in segments:
                self._inside(segment.path)
                with segment.path.open("rb") as handle:
                    if vehicle_id is not None:
                        # Scan the full bounded segment, not only its latest
                        # lines: old vehicle A must remain discoverable after
                        # vehicle B uploads a large volume of newer logs.
                        parts = handle.read().splitlines()
                    else:
                        offset = max(0, segment.size - limit * 25000)
                        handle.seek(offset)
                        data = handle.read(limit * 25000)
                        parts = data.splitlines()
                        if offset:
                            parts = parts[1:]
                valid = []
                for line in parts:
                    try:
                        value = json.loads(line)
                    except (ValueError, UnicodeDecodeError):
                        continue  # An interrupted, unacknowledged last record may be incomplete.
                    if (isinstance(value, dict) and type(value.get("received_ms")) is int
                            and value["received_ms"] > cutoff and value.get("source") == kind
                            and isinstance(value.get("text"), str)
                            and (vehicle_id is None or value.get("vehicle_id") == vehicle_id)):
                        valid.append(value)
                rows = valid[-limit:] + rows
                if len(rows) >= limit:
                    break
            combined.extend(rows[-limit:])
        return sorted(combined, key=lambda row: row["received_ms"])[-limit:]

    def list_vehicles(self, device, limit=32):
        """Anonymous IDs with latest seen timestamp, derived from retained rows."""
        canonical_device(device)
        if not 1 <= limit <= 32:
            raise ValueError("Invalid limit")
        cutoff = int(self.clock() * 1000) - int(self.retention_seconds * 1000)
        seen = {}
        segments = sorted((s for s in self.segments.values() if s.device == device),
                          key=lambda s: (s.first_ms, s.sequence))
        for segment in segments:
            self._inside(segment.path)
            with segment.path.open("rb") as handle:
                for raw in handle:
                    try:
                        item = json.loads(raw)
                    except (ValueError, UnicodeDecodeError):
                        continue
                    if not isinstance(item, dict) or not isinstance(item.get("vehicle_id"), str):
                        continue
                    identity = item["vehicle_id"]
                    try:
                        canonical_device(identity)
                        if uuid.UUID(identity).version != 4:
                            continue
                    except (ValueError, AttributeError):
                        continue
                    received = item.get("received_ms")
                    if type(received) is int and received > cutoff:
                        seen[identity] = max(seen.get(identity, 0), received)
        return [{"vehicle_id": k, "last_log_ms": v}
                for k, v in sorted(seen.items(), key=lambda x: x[1], reverse=True)[:limit]]

    def stats(self):
        space = os.statvfs(self.root) if hasattr(os, "statvfs") else None
        return {"persistent": True, "retention_seconds": self.retention_seconds,
                "max_file_bytes": self.max_file_bytes, "total_size_limit_bytes": None,
                "files": len(self.segments), "bytes": sum(s.size for s in self.segments.values()),
                "free_bytes": space.f_bavail * space.f_frsize if space else None,
                "deleted_expired_files": self.deleted_expired, "last_cleanup_ms": self.last_cleanup_ms}

    def close(self):
        if self.lock is not None:
            self.lock.close()
            self.lock = None
