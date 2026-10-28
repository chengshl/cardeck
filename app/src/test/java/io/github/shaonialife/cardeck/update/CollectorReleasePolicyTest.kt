package io.github.shaonialife.cardeck.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CollectorReleasePolicyTest {
    private fun release(source: String = CollectorReleasePolicy.SOURCE,
                        apk: String = CollectorReleasePolicy.APK, code: Long = 90010001L): JSONObject {
        val version = "20261027194500"
        val tag = "exp-cardeck-rescue-standalone-$version"
        val prefix = "https://github.com/chengshl/cardeck/releases/download/$tag/"
        return JSONObject().apply {
            put("prerelease", true)
            put("draft", false)
            put("tag_name", tag)
            put("body", """artifactKind=experiment
updaterSchema=2
baseBranch=Buick-E5
sourceBranch=$source
versionName=$version
versionCode=$code
builtAt=2026-10-27T11:45:00Z
apkName=$apk

## 取证专用版说明
日志修复
""")
            put("assets", JSONArray().put(JSONObject().put("name", apk)
                .put("browser_download_url", prefix + apk))
                .put(JSONObject().put("name", "$apk.sha256")
                    .put("browser_download_url", prefix + "$apk.sha256")))
        }
    }

    @Test fun exactCollectorOnly() {
        val valid = release()
        assertNotNull(CollectorReleasePolicy.parse(valid))
        assertNull(CollectorReleasePolicy.parse(release("experiment/buick-e5-detailed-diagnostics")))
        assertNull(CollectorReleasePolicy.parse(release(apk = "CarDeck-Buick-Test.apk")))
        assertNull(CollectorReleasePolicy.parse(release(apk = "CarDeck-Test-Rescue-Collector.apk")))
        val previousCollector = release()
        previousCollector.put("tag_name", "exp-buick-test-rescue-collector-20261027194500")
        assertNull(CollectorReleasePolicy.parse(previousCollector))
        assertNull(CollectorReleasePolicy.parse(release(code = 0L)))
        valid.put("prerelease", false)
        assertNull(CollectorReleasePolicy.parse(valid))
    }

    @Test fun newestByCode() {
        val list = JSONArray().put(release(code = 101L))
            .put(release(code = 201L))
            .put(release("experiment/other-test", code = 999L))
        assertEquals(201L, CollectorReleasePolicy.latest(list)?.versionCode)
    }

    @Test fun maliciousExternalApkRejected() {
        val release = release()
        release.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.invalid/app.apk")
        assertNull(CollectorReleasePolicy.parse(release))
    }
}
