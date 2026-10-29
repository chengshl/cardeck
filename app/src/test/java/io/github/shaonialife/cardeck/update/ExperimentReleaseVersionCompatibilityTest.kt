package io.github.shaonialife.cardeck.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Locale

/** Release 版本名兼容与 sourceBranch 精确绑定；不执行音频协议操作。 */
class ExperimentReleaseVersionCompatibilityTest {
    private val source = "experiment/buick-test-t76-aac-causality-observe"
    private val apk = "CarDeck-Buick-Test-T85-DELAYED-AUDIO-CLAIM.apk"

    private fun currentName(): String {
        val normalized = source.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]+"), "-").trim('-')
        val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
        return "EXP-" + normalized.takeLast(14) + "-" + hash + "-261029011"
    }

    private fun release(name: String, branch: String = source, code: Long = 89165050L): JSONObject {
        val tag = "exp-buick-test-t85-" + name
        val root = "https://github.com/chengshl/cardeck/releases/download/" + tag + "/"
        return JSONObject().apply {
            put("tag_name", tag)
            put("prerelease", true)
            put("draft", false)
            put("body", """artifactKind=experiment
updaterSchema=2
baseBranch=Buick-Test
sourceBranch=$branch
versionName=$name
versionCode=$code
builtAt=2026-10-29T02:10:00Z
apkName=$apk
commit=0123456789abcdef0123456789abcdef01234567
""")
            put("assets", JSONArray()
                .put(JSONObject().put("name", apk).put("browser_download_url", root + apk))
                .put(JSONObject().put("name", "$apk.sha256").put("browser_download_url", root + apk + ".sha256")))
        }
    }

    @Test fun legacyAndFingerprintVersionRemainDiscoverable() {
        val candidates = ExperimentalUpdates.parseCandidates(JSONArray()
            .put(release("20261029080409", code = 89165049L))
            .put(release(currentName(), code = 89165050L)), "Buick-Test")
        assertEquals(2, candidates.size)
        assertEquals(currentName(), candidates.first().versionName)
        assertEquals(source, candidates.first().sourceBranch)
    }

    @Test fun foreignSourceAndSpoofedNameDoNotEnterAudioChannel() {
        val fingerprint = currentName()
        val other = "experiment/buick-test-other-audio-experiment"
        val mismatchedTag = release(fingerprint)
        mismatchedTag.put("tag_name", "exp-buick-test-t85-something-else")
        val c = ExperimentalUpdates.parseCandidates(JSONArray()
            .put(release(fingerprint, branch = other))
            .put(release("EXP-FAKE-000000000000-261029011"))
            .put(mismatchedTag)
            .put(release(fingerprint)), "Buick-Test")
        assertEquals(1, c.size)
        assertEquals(source, c.first().sourceBranch)
    }

    @Test fun experimentalApkMustMatchChannelAndNotBeDraft() {
        val draft = release(currentName())
        draft.put("draft", true)
        val wrongBase = release(currentName())
        wrongBase.put("body", wrongBase.getString("body").replace("baseBranch=Buick-Test", "baseBranch=Buick-E5"))
        assertTrue(ExperimentalUpdates.parseCandidates(JSONArray()
            .put(draft).put(wrongBase), "Buick-Test").isEmpty())
    }
}
