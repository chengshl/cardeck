package io.github.shaonialife.cardeck.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class E5RecoveryPolicyTest {
    private val experiment = "EXP-E-ACK-GUARD-33-C1EC58D22EBB-261030002"
    private val recovery = "EXP-STABLE-RESTORE-00F834D0B0E5-261030003"
    private val expTag = "exp-buick-e5-iap2-stale-ack-guard-33-$experiment"
    private val recTag = "e5-stable-restore-after-iap2-ack-33-$recovery"
    private val expApk = "CarDeck-Buick-E5-IAP2-ACK-Guard.apk"
    private val recApk = "CarDeck-Buick-E5-IAP2-ACK-Stable-Restore.apk"
    private val prefix = "https://github.com/chengshl/cardeck/releases/download/"

    private fun release(tag: String, body: String, apk: String): JSONObject {
        val base = prefix + tag + "/"
        return JSONObject()
            .put("tag_name", tag).put("prerelease", true).put("draft", false)
            .put("body", body)
            .put("assets", JSONArray()
                .put(JSONObject().put("name", apk).put("browser_download_url", base + apk))
                .put(JSONObject().put("name", "$apk.sha256").put("browser_download_url", base + "$apk.sha256")))
    }
    private fun experimentRelease() = release(expTag, """
        artifactKind=experiment
        updaterSchema=2
        baseBranch=Buick-E5
        sourceBranch=${E5RecoveryPolicy.EXPERIMENT_SOURCE}
        versionName=$experiment
        versionCode=89253482
        builtAt=2026-10-30T04:00:00Z
        apkName=$expApk
    """.trimIndent(), expApk)

    private fun recoveryRelease(
        recoveryFor: String = expTag,
        source: String = "experiment/buick-e5-iap2-ack-33-stable-restore",
        code: Long = 89253898,
        kind: String = "recovery",
    ) = release(recTag, """
        artifactKind=$kind
        recoveryFor=$recoveryFor
        baseBranch=Buick-E5
        sourceBranch=$source
        stableRuntimeCommit=8b62f9a878378cf2705e93d00fcd7263d055ad4b
        versionName=$recovery
        versionCode=$code
        builtAt=2026-10-30T04:10:00Z
        apkName=$recApk
        buildCommit=ef259653e151af08afd310fbe984181e29433d72
    """.trimIndent(), recApk)

    private fun find(
        recoveryRelease: JSONObject = recoveryRelease(),
        installedOrigin: String = E5RecoveryPolicy.EXPERIMENT_SOURCE,
        installedName: String = experiment,
        installedCode: Long = 89253482,
    ): ExperimentalUpdates.Candidate? = E5RecoveryPolicy.eligible(
        JSONArray().put(experimentRelease()).put(recoveryRelease),
        installedOrigin, installedName, installedCode,
    )

    @Test fun signed002CanDiscoverOnlyItsExactHigherCodeRecovery003() {
        val candidate = find()
        assertNotNull(candidate)
        assertEquals(recTag, candidate!!.tag)
        assertEquals(89253898L, candidate.versionCode)
        assertEquals("Buick-E5", candidate.baseBranch)
        assertEquals("experiment/buick-e5-iap2-ack-33-stable-restore", candidate.sourceBranch)
        assertTrue(candidate.apkUrl.endsWith("/$recApk"))
    }

    @Test fun cannotRecoverOtherOriginOtherInstalledVersionOrAlreadyRestoredApp() {
        assertNull(find(installedOrigin = "Buick-E5"))
        assertNull(find(installedOrigin = "experiment/buick-e5-video-firstframe-observability"))
        assertNull(find(installedName = "other"))
        assertNull(find(installedCode = 89253898))
    }

    @Test fun rejectsWrongRecoveryForAndCrossExperimentSource() {
        assertNull(find(recoveryRelease = recoveryRelease(recoveryFor = "unrelated-tag")))
        assertNull(find(recoveryRelease = recoveryRelease(source = E5RecoveryPolicy.EXPERIMENT_SOURCE)))
        assertNull(find(recoveryRelease = recoveryRelease(kind = "experiment")))
        assertNull(find(recoveryRelease = recoveryRelease(code = 89253482)))
    }

    @Test fun rejectsForgedAssetUrlsAndMissingChecksum() {
        val forged = recoveryRelease()
        val assets = forged.getJSONArray("assets")
        assets.getJSONObject(0).put("browser_download_url", "https://example.com/evil.apk")
        assertNull(find(recoveryRelease = forged))
        val missing = recoveryRelease()
        missing.put("assets", JSONArray().put(missing.getJSONArray("assets").getJSONObject(0)))
        assertNull(find(recoveryRelease = missing))
    }

    @Test fun refusesAmbiguousExperimentMetadataAndUntrustedStableRuntime() {
        val duplicate = JSONArray().put(experimentRelease()).put(experimentRelease()).put(recoveryRelease())
        assertNull(E5RecoveryPolicy.eligible(duplicate, E5RecoveryPolicy.EXPERIMENT_SOURCE, experiment, 89253482))
        val altered = recoveryRelease()
        altered.put("body", altered.getString("body").replace(
            "stableRuntimeCommit=8b62f9a878378cf2705e93d00fcd7263d055ad4b",
            "stableRuntimeCommit=0000000000000000000000000000000000000000"))
        assertNull(find(recoveryRelease = altered))
    }
}
