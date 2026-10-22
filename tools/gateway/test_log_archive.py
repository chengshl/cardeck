"""Archive tests use tiny segments and a fake clock; they never allocate gigabytes."""
import errno
import json
import os
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
from log_archive import LogArchive, initialize_root


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="cardeck-owned-test-", dir=os.environ.get("CARDECK_TEST_TEMP"))
        self.root = Path(self.temp.name).resolve()
        self.assertIn("cardeck-owned-test-", self.root.name)
        initialize_root(self.root)
        self.now = 1792638000.0
        self.store = LogArchive(self.root, max_file_bytes=1800, clock=lambda: self.now)
        self.device = str(uuid.uuid4())

    def tearDown(self):
        self.store.close()
        # TemporaryDirectory owns this exact generated target; no user paths are removed.
        self.assertIn("cardeck-owned-test-", self.root.name)
        self.temp.cleanup()

    def append(self, source="app", lines=None, device=None):
        return self.store.append(device or self.device, source, lines or ["diagnostic line"], str(uuid.uuid4()))

    def test_separates_device_beijing_date_source_and_rotates_without_total_quota(self):
        for _ in range(12):
            self.append(lines=["测" * 256] * 8)
        other = str(uuid.uuid4())
        self.append(source="system", lines=["confirmed snapshot"])
        self.append(device=other, lines=["another installation"])
        files = list((self.root / "logs").glob("*/*/*/*.jsonl"))
        self.assertGreater(len(files), 10)
        self.assertTrue(all(path.stat().st_size <= 1800 for path in files))
        self.assertTrue(any(path.parts[-2] == "system" and self.device in path.parts for path in files))
        self.assertEqual(96, len(self.store.tail(self.device, "app")))
        self.assertEqual("confirmed snapshot", self.store.tail(self.device, "system")[0]["text"])
        self.assertEqual("another installation", self.store.tail(other)[0]["text"])
        self.assertIsNone(self.store.stats()["total_size_limit_bytes"])
        self.assertTrue(any("2026-10-22" in path.parts for path in files))

    def test_restart_recovers_logs_and_does_not_require_device_registration(self):
        self.append(lines=["persisted before restart"])
        with next(iter(self.store.segments)).open("ab") as handle:
            handle.write(b'{"text":')
        self.store.close()
        self.store = LogArchive(self.root, max_file_bytes=1800, clock=lambda: self.now)
        self.assertEqual("persisted before restart", self.store.tail(self.device)[0]["text"])
        self.assertEqual(1, self.store.stats()["files"])

    def test_boundary_cleanup_keeps_younger_rows_in_the_same_file(self):
        self.now = (int(self.now) // 3600) * 3600
        start = self.now
        self.append(lines=["older than seven days"])
        self.now += 1800
        self.append(lines=["not yet seven days old"])
        self.assertEqual(1, len(self.store.segments))
        self.now = start + 7 * 86400 + 1
        self.store.cleanup()
        self.assertEqual(["not yet seven days old"], [row["text"] for row in self.store.tail(self.device)])
        physical = [json.loads(line)["text"] for path in self.store.segments for line in path.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(["not yet seven days old"], physical)

    def test_only_expired_files_are_deleted_including_after_restart(self):
        self.append(lines=["expired"])
        first = next(iter(self.store.segments))
        unrelated = self.root / "do-not-delete.txt"
        unrelated.write_text("not an archive segment", encoding="utf-8")
        self.now += 7 * 86400 - 1
        self.store.cleanup()
        self.assertTrue(first.exists())
        self.append(source="system", lines=["younger than seven days"])
        self.now += 2
        self.store.close()
        self.store = LogArchive(self.root, max_file_bytes=1800, clock=lambda: self.now)
        self.assertFalse(first.exists())
        self.assertEqual([], self.store.tail(self.device, "app"))
        self.assertEqual("younger than seven days", self.store.tail(self.device, "system")[0]["text"])
        self.assertTrue(unrelated.exists())

    def test_idle_cleanup_and_partial_expiry_are_bounded_by_hourly_segments(self):
        self.append(lines=["hour one"])
        self.now += 3600
        self.append(lines=["hour two"])
        self.assertEqual(2, len(self.store.segments))
        self.now += 7 * 86400
        self.store.cleanup()
        self.assertEqual(0, self.store.stats()["files"])

    def test_partial_disk_full_write_is_not_reused_and_fresh_logs_are_not_deleted(self):
        self.append(source="system", lines=["keep even when disk is full"])
        original_write = os.write
        calls = 0
        def partial(fd, data):
            nonlocal calls
            calls += 1
            if calls == 1:
                return original_write(fd, data[:5])
            raise OSError(errno.ENOSPC, "synthetic full disk")
        with patch("log_archive.os.write", side_effect=partial):
            with self.assertRaises(OSError):
                self.append(lines=["unacknowledged partial"])
        self.append(lines=["acknowledged after recovery"])
        self.assertEqual(["acknowledged after recovery"], [row["text"] for row in self.store.tail(self.device, "app")])
        self.assertEqual("keep even when disk is full", self.store.tail(self.device, "system")[0]["text"])

    def test_path_traversal_and_unowned_directories_are_rejected(self):
        with self.assertRaises((ValueError, AttributeError)):
            self.append(device="../another-path")
        with self.assertRaises(ValueError):
            self.store.tail(self.device, "../system")
        other = self.root / "unowned"
        other.mkdir()
        (other / "keep").write_text("keep", encoding="utf-8")
        with self.assertRaises(OSError):
            initialize_root(other)
        self.assertTrue((other / "keep").exists())

    def test_second_writer_cannot_open_same_archive(self):
        with self.assertRaises(OSError):
            LogArchive(self.root, clock=lambda: self.now)
        self.append()
        self.assertEqual(1, len(self.store.tail(self.device)))

    def test_fsync_failure_is_reported_and_never_silently_acknowledged(self):
        with patch("log_archive.os.fsync", side_effect=OSError("synthetic flush failure")):
            with self.assertRaises(OSError):
                self.append()


if __name__ == "__main__":
    unittest.main(verbosity=2)
