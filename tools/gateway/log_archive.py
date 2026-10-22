"""Single-writer JSONL archive. Only owned, expired segments may be deleted."""
import errno
import json
import os
import re
import stat
import time
import uuid
import heapq
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

FORMAT = {"format": "cardeck-log-archive", "version": 1}
SEGMENT = re.compile(r"(\d{13})-(\d{20})-([0-9a-f-]{36})\.jsonl")
REWRITE = re.compile(r"\.rewrite-[0-9a-f-]{36}\.tmp")


class ArchiveBusy(OSError):
    pass


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

    def append(self, device, source, lines, batch_id, vehicle_id=None):
        canonical_device(device)
        if source not in ("app", "system") or not lines or len(lines) > 8:
            raise ValueError("Invalid log batch")
        if any(not isinstance(line, str) or not 1 <= len(line) <= 256 for line in lines):
            raise ValueError("Invalid log line")
        canonical_device(batch_id)
        if vehicle_id is not None:
            canonical_device(vehicle_id)
            if uuid.UUID(vehicle_id).version != 4:
                raise ValueError("Invalid anonymous vehicle ID")
        self.cleanup()
        now_ms = int(self.clock() * 1000)
        rows = [dict(source=source, received_ms=now_ms, batch_id=batch_id, text=line,
                     **({"vehicle_id": vehicle_id} if vehicle_id is not None else {}))
                for line in lines]
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
                        offset = max(0, segment.size - limit * 4096)
                        handle.seek(offset)
                        data = handle.read(limit * 4096)
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
