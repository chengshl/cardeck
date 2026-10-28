import unittest
from datetime import datetime, timezone
from tools.release.allocate_version import choose_version


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

    def test_sequence_limit(self):
        now = datetime(2026, 10, 28, 14, 30, tzinfo=timezone.utc)
        with self.assertRaises(ValueError):
            choose_version(["refs/tags/cardeck-build/v1/261028999-c90000000"], now, "Buick-E5")


if __name__ == "__main__":
    unittest.main()
