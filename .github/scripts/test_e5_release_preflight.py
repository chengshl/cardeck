"""E5 official signing workflow static regression; does not access secrets or sign APK."""
from pathlib import Path
import unittest

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

    def test_pure_release_workflow_changes_do_not_sign(self):
        w = self.workflow
        head = w.split("concurrency:", 1)[0]
        for path in (
            "docs/**",
            ".github/workflows/build-buick-e5.yml",
            ".github/workflows/ci-e5-release-preflight.yml",
            ".github/scripts/test_e5_release_preflight.py",
        ):
            self.assertIn("'" + path + "'", head)
        self.assertIn("paths-ignore:", head)
        self.assertIn("branches: [Buick-E5]", head)

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


if __name__ == "__main__":
    unittest.main()
