package io.github.shaonialife.cardeck.update

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Locale

/** 只测试独立的版本名和标签策略，不初始化带 Android Looper 的更新 UI 对象。 */
class ExperimentReleaseVersionCompatibilityTest {
    private val source = "experiment/buick-test-t76-aac-causality-observe"
    private val prefix = "exp-buick-test-"

    private fun currentName(branch: String = source): String {
        val normalized = branch.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]+"), "-").trim('-')
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(branch.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
        return "EXP-" + normalized.takeLast(14) + "-" + hash + "-261029011"
    }

    @Test fun legacyAndFingerprintedReleaseTagsAreAccepted() {
        val legacy = "20261029080409"
        val fingerprint = currentName()
        assertTrue(ReleaseVersionName.matchesExperimentRelease(
            "exp-buick-test-t85-" + legacy, legacy, source, prefix))
        assertTrue(ReleaseVersionName.matchesExperimentRelease(
            "exp-buick-test-t85-" + fingerprint, fingerprint, source, prefix))
    }

    @Test fun oldReadableExpAliasRemainsAcceptedForSameSource() {
        val readable = source.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]+"), "-").trim('-').takeLast(24)
        val old = "EXP-" + readable + "-261029011"
        assertTrue(ReleaseVersionName.matchesExperimentRelease(
            "exp-buick-test-t85-" + old, old, source, prefix))
    }

    @Test fun forgedSourcePrefixAndSuffixAreRejected() {
        val modern = currentName()
        val matching = "exp-buick-test-t85-" + modern
        assertFalse(ReleaseVersionName.matchesExperimentRelease(
            matching, modern, "experiment/buick-test-another-audio-experiment", prefix))
        assertFalse(ReleaseVersionName.matchesExperimentRelease(
            "exp-buick-e5-t85-" + modern, modern, source, prefix))
        assertFalse(ReleaseVersionName.matchesExperimentRelease(
            "exp-buick-test-t85-wrong", modern, source, prefix))
        assertFalse(ReleaseVersionName.matchesExperimentRelease(
            matching, "EXP-FAKE-000000000000-261029011", source, prefix))
        assertFalse(ReleaseVersionName.matchesExperimentRelease(
            matching, modern, "Buick-Test", prefix))
    }
}
