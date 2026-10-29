package io.github.shaonialife.cardeck.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseVersionNameTest {
    @Test fun formalAcceptsNewAndLegacyNames() {
        assertEquals("E5-261029001",
            ReleaseVersionName.display("versionName=E5-261029001", "Buick-E5", 89134000))
        assertEquals("20261028232557",
            ReleaseVersionName.display("versionName=20261028232557", "Buick-E5", 89133957))
    }

    @Test fun testAcceptsSharedSequenceAndRejectsWrongChannel() {
        assertEquals("E5-TEST-261029002",
            ReleaseVersionName.display("versionName=E5-TEST-261029002", "Buick-Test", 89134002))
        assertEquals("89134002",
            ReleaseVersionName.display("versionName=E5-261029001", "Buick-Test", 89134002))
    }

    @Test fun experimentNamesAreBoundToTheirSourceBranch() {
        val source = "experiment/buick-e5-updater-b-minimal"
        val suffix = source.uppercase(java.util.Locale.ROOT)
            .replace(Regex("""[^A-Z0-9]+"""), "-").trim('-').takeLast(24)
        val name = "EXP-" + suffix + "-261029003"
        org.junit.Assert.assertTrue(ReleaseVersionName.isExperimental(name, source))
        org.junit.Assert.assertTrue(
            ReleaseVersionName.isExperimental("20261026185326", source))
        org.junit.Assert.assertFalse(
            ReleaseVersionName.isExperimental(name, "experiment/buick-e5-other-project"))
        org.junit.Assert.assertFalse(
            ReleaseVersionName.isExperimental("EXP-OTHER-261029003", source))
    }

    @Test fun fingerprintedExperimentNameTracksExactSource() {
        val source = "experiment/buick-e5-updater-b-minimal"
        val normalized = source.uppercase(java.util.Locale.ROOT)
            .replace(Regex("""[^A-Z0-9]+"""), "-").trim('-')
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
        val fingerprint = digest.take(6).joinToString("") {
            "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xff)
        }
        val name = "EXP-" + normalized.takeLast(14) + "-" + fingerprint + "-261029003"
        org.junit.Assert.assertTrue(ReleaseVersionName.isExperimental(name, source))
        org.junit.Assert.assertFalse(
            ReleaseVersionName.isExperimental(name, "experiment/another-e5-updater-b-minimal"))
    }

    @Test fun malformedNamesFallBackToVersionCode() {
        for (bad in listOf("E5-261029001-danger", "E5-26102901", "../E5-261029001")) {
            assertEquals("89134001",
                ReleaseVersionName.display("versionName=$bad", "Buick-E5", 89134001))
        }
        assertEquals("89134001", ReleaseVersionName.display("", "Buick-E5", 89134001))
    }
}
