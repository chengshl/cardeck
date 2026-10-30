"""E5 official signing workflow static regression; does not access secrets or sign APK."""
from pathlib import Path
import unittest

from tools.release.allocate_version import choose_version, published_apk_codes
from datetime import datetime, timezone

WORKFLOW = Path(".github/workflows/build-buick-e5.yml")
STEP = "      - name: Verify E5 production certificate against previously published signed APK"


class E5SignedReleasePreflightTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_only_e5_release_channel(self):
        w = self.workflow
        self.assertIn('test "$GITHUB_REF_NAME" = "Buick-E5"', w)
        self.assertIn('test "$RELEASE_TAG" = "buick-e5-latest"', w)
        self.assertIn('test "$APK_NAME" = "CarDeck-Buick-E5.apk"', w)
        self.assertIn('group: cardeck-global-signed-release-version', w)
        self.assertIn('cancel-in-progress: false', w)

    def test_real_apk_version_must_match_allocator(self):
        w = self.workflow
        self.assertIn('test "$CARDECK_RELEASE_BRANCH" = "Buick-E5"', w)
        self.assertIn("versionCode='$CARDECK_BUILD_VERSION_CODE'", w)
        self.assertIn("versionName='$CARDECK_RELEASE_VERSION_NAME'", w)
        self.assertLess(w.index('Assign global daily version number'), w.index('Build signed release APK'))
        self.assertLess(w.index("Build signed release APK"), w.index('versionCode=\'$CARDECK_BUILD_VERSION_CODE\''))

    def test_existing_certificate_is_required_before_publish(self):
        w = self.workflow
        block = w.split(STEP, 1)[1].split("      - uses: actions/upload-artifact@v4", 1)[0]
        self.assertIn('gh release download "$RELEASE_TAG"', block)
        self.assertIn('apksigner', block)
        self.assertIn('CN=Android Debug', block)
        self.assertIn('NEW_CERT', block)
        self.assertIn('OLD_CERT', block)
        self.assertIn('OLD_APK', block)
        self.assertIn('test -n "$OLD_CERT"', block)
        self.assertIn('test -n "$NEW_CERT"', block)
        self.assertIn('NEW_CERT,,', block)
        self.assertIn('OLD_CERT,,', block)
        self.assertIn('exit 1', block)
        self.assertLess(w.index(STEP), w.index("Publish only Buick-E5 update"))

    def test_only_apk_inputs_trigger_automatic_signing(self):
        # The 2026-10-31 test-only #79 merge unexpectedly minted an official
        # E5 APK (89313871), making older experiments/recovery non-installable
        # over that newer version. Fail closed on the exact push allowlist.
        import fnmatch
        import re

        header = self.workflow.split("concurrency:", 1)[0]
        self.assertIn("workflow_dispatch:", header)
        self.assertIn("branches: [Buick-E5]", header)
        self.assertNotIn("paths-ignore:", header)
        self.assertEqual(header.count("    paths:\n"), 1)
        patterns = re.findall(r"(?m)^      - '([^']+)'$", header.split("    paths:\n", 1)[1])
        self.assertEqual(patterns, [
            "app/src/main/**",
            "app/src/release/**",
            "app/build.gradle.kts",
            "app/proguard-rules.pro",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradle/**",
            "gradlew",
            "gradlew.bat",
        ])

        def automatically_publishes(path):
            return any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns)

        for runtime_input in (
            "app/src/main/java/io/github/shaonialife/cardeck/MainActivity.kt",
            "app/src/main/res/values/strings.xml",
            "app/src/main/AndroidManifest.xml",
            "app/src/release/AndroidManifest.xml",
            "app/build.gradle.kts",
            "app/proguard-rules.pro",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradlew",
            "gradlew.bat",
        ):
            with self.subTest(should_publish=runtime_input):
                self.assertTrue(automatically_publishes(runtime_input))

        for non_runtime_input in (
            "app/src/test/java/io/github/shaonialife/cardeck/replay/B233HandshakeReplayTest.kt",
            "app/src/androidTest/java/example/UITest.kt",
            "app/src/debug/AndroidManifest.xml",
            "app/lint-baseline.xml",
            ".github/workflows/ci-e5-upstream-replay-33.yml",
            ".github/workflows/build-buick-e5.yml",
            ".github/workflows/ci-e5-release-preflight.yml",
            ".github/scripts/test_e5_release_preflight.py",
            "tools/release/allocate_version.py",
            "tools/gateway/test_gateway.py",
            "docs/PROJECT-GOVERNANCE.md",
            "AGENTS.md",
            "README.md",
        ):
            with self.subTest(must_not_publish=non_runtime_input):
                self.assertFalse(automatically_publishes(non_runtime_input))

    def test_original_signed_release_checks_remain(self):
        w = self.workflow
        self.assertIn("python3 tools/release/allocate_version.py", w)
        self.assertIn("tools/mcp/test_server.py", w)
        self.assertIn("tools/gateway/test_gateway.py", w)
        self.assertIn(':app:testDebugUnitTest', w)
        self.assertIn('io.github.buick.e5.cardeck', w)
        self.assertIn('sha256sum "$APK_NAME"', w)
        self.assertIn('gh release upload "$RELEASE_TAG"', w)
        self.assertIn('gh release create "$RELEASE_TAG"', w)


    def test_signed_recovery_apk_is_a_global_future_official_version_floor(self):
        # E5 #33: signed official 001 < experiment 002 < data-preserving recovery 003.
        # A future official APK must exceed the recovery, not merely official 001.
        releases = [[
            {"tag_name": "buick-e5-latest", "body": "versionCode=89252977",
             "assets": [{"name": "CarDeck-Buick-E5.apk"}]},
            {"tag_name": "exp-buick-e5-iap2-stale-ack-guard-33",
             "body": "versionCode=89253482",
             "assets": [{"name": "CarDeck-Buick-E5-IAP2-ACK-Guard.apk"}]},
        ], [
            {"tag_name": "e5-stable-restore-after-iap2-ack-33",
             "body": "versionCode=89253898",
             "assets": [{"name": "CarDeck-Buick-E5-IAP2-ACK-Stable-Restore.apk"}]},
        ]]
        published_codes = published_apk_codes(releases)
        self.assertEqual(max(published_codes), 89253898)
        _, reserved_code, _ = choose_version(
            ["refs/tags/cardeck-build/v1/261029001-c89252977"],
            datetime(2026, 10, 29, 0, 0, tzinfo=timezone.utc),
            "Buick-E5",
        )
        next_code = max(reserved_code, max(published_codes) + 1)
        self.assertGreater(next_code, 89253898)

    def test_newer_other_package_release_is_global_floor_after_e5_recovery(self):
        # A later signed Test APK must raise the next E5 official code even
        # when E5 recovery 003 is the highest E5-family APK. This guards the
        # real cross-package allocation contract, not an E5-only release list.
        releases = [[
            {"tag_name": "buick-e5-latest", "body": "versionCode=89252977",
             "assets": [{"name": "CarDeck-Buick-E5.apk"}]},
            {"tag_name": "e5-stable-restore-after-iap2-ack-33",
             "body": "versionCode=89253898",
             "assets": [{"name": "CarDeck-Buick-E5-IAP2-ACK-Stable-Restore.apk"}]},
        ], [
            {"tag_name": "exp-buick-test-t89", "body": "versionCode=89270470",
             "assets": [{"name": "CarDeck-Buick-Test-T89-SAME-UID-UDP-PREFLIGHT.apk"}]},
            {"tag_name": "notes-only", "body": "versionCode=99999999",
             "assets": [{"name": "notes.txt"}]},
        ]]
        published_codes = published_apk_codes(releases)
        self.assertEqual(max(published_codes), 89270470)
        _, reserved_code, _ = choose_version(
            ["refs/tags/cardeck-build/v1/261030001-c89252977"],
            datetime(2026, 10, 30, 0, 0, tzinfo=timezone.utc),
            "Buick-E5",
        )
        next_official_code = max(reserved_code, max(published_codes) + 1)
        self.assertGreater(next_official_code, 89270470)
        self.assertGreater(next_official_code, 89253898)

    def test_signed_apk_without_version_metadata_fails_closed(self):
        releases = [[
            {"tag_name": "e5-stable-restore-after-iap2-ack-33",
             "body": "artifactKind=recovery\\n",
             "assets": [{"name": "CarDeck-Buick-E5-IAP2-ACK-Stable-Restore.apk"}]},
        ]]
        with self.assertRaisesRegex(ValueError, "versionCode"):
            published_apk_codes(releases)

    def test_non_apk_release_does_not_set_install_version_floor(self):
        releases = [[
            {"tag_name": "docs-only", "body": "",
             "assets": [{"name": "notes.txt"}]},
            {"tag_name": "buick-e5-latest", "body": "versionCode=89252977",
             "assets": [{"name": "CarDeck-Buick-E5.apk"}]},
        ]]
        self.assertEqual(published_apk_codes(releases), [89252977])



if __name__ == "__main__":
    unittest.main()
