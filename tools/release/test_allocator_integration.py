"""Offline GitHub API simulation: guard signed release allocation and retries."""
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.release import allocate_version


class AllocatorIntegrationTest(unittest.TestCase):
    def run_allocator(self, branch="Buick-E5", collision=False, invalid_legacy=False,
                      extra_release_code=None, invalid_signed_release=False):
        refs = []
        writes = []
        calls = []
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "github-env"
            settings = {"GITHUB_REPOSITORY": "chengshl/cardeck",
                        "GITHUB_SHA": "a" * 40,
                        "GITHUB_REF_NAME": branch,
                        "GITHUB_ENV": str(output)}

            def fake_gh(*args, **kwargs):
                calls.append(args)
                if args[0].endswith("/releases/tags/buick-e5-latest"):
                    return ({"body": "versionName=previous\nversionCode=89133957\n"}
                            if not invalid_legacy else {"body": "broken-release-body"})
                if args[0].endswith("/releases/tags/buick-test-latest"):
                    return {"body": "versionCode=88944806\n"}
                if args[0].endswith("/releases/tags/buick-latest"):
                    return {"body": "versionCode=88921786\\n".replace("\\n", "\n")}
                if args[0].endswith("/releases/tags/buick-main-latest"):
                    return {"body": "versionCode=88927204\\n".replace("\\n", "\n")}
                if args[0] == "--paginate":
                    self.assertEqual(args[1], "--slurp")
                    if "/releases?" in args[2]:
                        latest = [{"tag_name": "exp-rescue-latest",
                                   "body": "versionCode=89141720\\n".replace("\\n", "\n"),
                                   "assets": [{"name": "CarDeck-Rescue.apk"}]}]
                        extra = ([{"tag_name": "exp-rescue-old-page",
                                   "body": "versionCode=" + str(extra_release_code),
                                   "assets": [{"name": "CarDeck-Rescue-Older.apk"}]}]
                                 if extra_release_code is not None else [])
                        if invalid_signed_release:
                            extra.append({"tag_name": "bad-signed-release", "body": "",
                                          "assets": [{"name": "Bad.apk"}]})
                        extra.append({"tag_name": "notes-only", "body": "", "assets": []})
                        return [latest, extra]
                    return [[{"ref": ref} for ref in refs]]
                if args[:2] == ("-X", "POST"):
                    tag = next(arg.split("=", 1)[1] for arg in args if arg.startswith("ref="))
                    if collision and not writes:
                        writes.append("collision")
                        refs.append(tag)
                        raise subprocess.CalledProcessError(1, ["gh"], stderr="HTTP 422")
                    writes.append(tag)
                    refs.append(tag)
                    return {"ref": tag}
                raise AssertionError(f"Unexpected call: {args}")

            with patch.dict(os.environ, settings), patch.object(allocate_version, "gh", side_effect=fake_gh):
                if invalid_legacy or invalid_signed_release:
                    with self.assertRaises(ValueError):
                        allocate_version.main()
                    self.assertFalse(output.exists())
                    self.assertFalse(writes)
                    return
                allocate_version.main()
            values = dict(line.split("=", 1) for line in output.read_text().splitlines())
        self.assertEqual(values["CARDECK_RELEASE_BRANCH"], branch)
        self.assertGreater(int(values["CARDECK_BUILD_VERSION_CODE"]),
                           max(89141720, extra_release_code or 0))
        self.assertRegex(values["CARDECK_RELEASE_VERSION_NAME"],
                         r"^(E5|E5-TEST|BUICK|EXP-[A-Z0-9-]+-[0-9A-F]{12})-\d{9}$")
        self.assertEqual(len(refs), 2 if collision else 1)
        self.assertTrue(any(call[0] == "--paginate" and "/releases?" in call[2]
                            for call in calls))
        self.assertTrue(any(call[0] == "--paginate" and "/matching-refs/" in call[2]
                            for call in calls))
        if collision:
            self.assertIn("002-c", writes[-1])

    def test_formal_allocates_above_existing_installed_version(self):
        self.run_allocator()

    def test_test_channel_uses_same_allocator(self):
        self.run_allocator(branch="Buick-Test")

    def test_buick_official_uses_shared_allocator(self):
        self.run_allocator(branch="Buick")

    def test_experiment_uses_fingerprinted_alias(self):
        self.run_allocator(branch="experiment/buick-e5-updater-b-minimal")

    def test_conflict_retries_with_fresh_daily_sequence(self):
        self.run_allocator(collision=True)

    def test_malformed_legacy_release_fails_closed(self):
        self.run_allocator(invalid_legacy=True)

    def test_old_second_page_experiment_release_is_a_baseline(self):
        self.run_allocator(extra_release_code=150000000)

    def test_incomplete_signed_release_fails_before_allocating(self):
        self.run_allocator(invalid_signed_release=True)


if __name__ == "__main__":
    unittest.main()
