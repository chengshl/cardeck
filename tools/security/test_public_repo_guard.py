"""Regression checks for user-specific identifiers in the public Git tree."""
import unittest
from pathlib import Path
from public_repo_guard import reasons


class PublicRepoGuardTests(unittest.TestCase):
    def test_user_vehicle_name_in_notes_is_rejected(self):
        synthetic = "BUICK" + "_" + "2468"
        self.assertIn(
            "specific vehicle Bluetooth or hotspot identifier",
            reasons(Path("docs/receiver.md"), "Observed device: " + synthetic),
        )

    def test_model_name_and_redacted_value_are_allowed(self):
        self.assertEqual(
            [],
            reasons(Path("docs/receiver.md"), "Buick-E5 B233 [CAR_BT_NAME]"),
        )

    def test_explicit_private_ip_rejected(self):
        self.assertIn(
            "non-documentation IPv4 address",
            reasons(Path("docs/receiver.md"), "LAN 10." + "1.2.3"),
        )

    def test_documentation_ip_is_allowed(self):
        self.assertEqual([], reasons(Path("docs/receiver.md"), "192.0.2.8"))

    def test_live_private_domain_is_rejected(self):
        domain = "car" + "deck." + "once" + ".wiki"
        self.assertIn("private project domain", reasons(Path("docs/receiver.md"), domain))


if __name__ == "__main__":
    unittest.main()
