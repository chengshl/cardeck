import unittest
from datetime import datetime, timezone
from tools.release.allocate_version import choose_version, published_apk_codes


class VersionAllocationTest(unittest.TestCase):
    def test_shared_daily_sequence_across_branches(self):
        now = datetime(2026, 10, 28, 14, 30, tzinfo=timezone.utc)
        refs = ["refs/tags/cardeck-build/v1/261028001-c90000000",
                "refs/tags/cardeck-build/v1/261028002-c90000001"]
        name, code, ref = choose_version(refs, now, "Buick-E5")
        self.assertEqual(name, "E5-261028003")
        self.assertGreater(code, 90000001)
        self.assertTrue(ref.endswith("003-c" + str(code)))

    def test_beijing_date_rollover(self):
        now = datetime(2026, 10, 28, 16, 0, tzinfo=timezone.utc)
        name, _, _ = choose_version(["refs/tags/cardeck-build/v1/261028007-c90000000"],
                                     now, "Buick-Test")
        self.assertEqual(name, "E5-TEST-261029001")

    def test_collision_prevention(self):
        now = datetime(2026, 10, 28, 14, 30, tzinfo=timezone.utc)
        name, code, _ = choose_version(["refs/tags/cardeck-build/v1/261028001-c99999999"],
                                       now, "Buick-E5")
        self.assertEqual(name, "E5-261028002")
        self.assertEqual(code, 100000000)

    def test_previous_release_code_must_be_exceeded(self):
        now = datetime(2026, 10, 28, 14, 30, tzinfo=timezone.utc)
        _, code, _ = choose_version(
            ["refs/tags/cardeck-build/v1/261028001-c89133957"], now, "Buick-E5")
        self.assertGreater(code, 89133957)

    def test_distinct_branch_aliases(self):
        now = datetime(2026, 10, 28, 14, 30, tzinfo=timezone.utc)
        for branch, prefix in (("Buick-E5", "E5-"), ("Buick-Test", "E5-TEST-"),
                               ("experiment/buick-e5-updater-b-minimal", "EXP-")):
            name, _, _ = choose_version([], now, branch)
            self.assertTrue(name.startswith(prefix))

    def test_unknown_branches_with_same_tail_have_distinct_aliases(self):
        now = datetime(2026, 10, 28, 14, 30, tzinfo=timezone.utc)
        branch_a = "experiment/first-team-a-very-long-shared-suffix"
        branch_b = "experiment/other-team-b-very-long-shared-suffix"
        name_a, _, _ = choose_version([], now, branch_a)
        name_b, _, _ = choose_version([], now, branch_b)
        self.assertNotEqual(name_a, name_b)
        self.assertRegex(name_a, r"^EXP-[A-Z0-9-]+-[0-9A-F]{12}-[0-9]{9}$")

    def test_all_signed_apk_release_pages_are_baseline(self):
        pages = [[{"tag_name": "notes-only", "body": "", "assets": []}],
                 [{"tag_name": "exp-rescue-rollback", "body": "versionCode=89141720",
                   "assets": [{"name": "CarDeck-Rescue.apk"}]}]]
        self.assertEqual(published_apk_codes(pages), [89141720])
        with self.assertRaises(ValueError):
            published_apk_codes([[{"tag_name": "bad-signed", "body": "",
                                  "assets": [{"name": "Broken.apk"}]}]])

    def test_sequence_limit(self):
        now = datetime(2026, 10, 28, 14, 30, tzinfo=timezone.utc)
        with self.assertRaises(ValueError):
            choose_version(["refs/tags/cardeck-build/v1/261028999-c90000000"], now, "Buick-E5")


if __name__ == "__main__":
    unittest.main()
