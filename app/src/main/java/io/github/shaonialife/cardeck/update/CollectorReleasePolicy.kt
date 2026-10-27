package io.github.shaonialife.cardeck.update

import org.json.JSONArray
import org.json.JSONObject

/** A strict single-purpose update channel: NEVER accept other Test/E5 APKs. */
internal object CollectorReleasePolicy {
    const val SOURCE = "experiment/buick-test-rescue-collector"
    const val APK = "CarDeck-Test-Rescue-Collector.apk"
    private const val PREFIX = "exp-buick-test-rescue-collector-"
    private const val ROOT = "https://github.com/chengshl/cardeck/releases/download/"

    data class Candidate(
        val tag: String, val versionName: String, val versionCode: Long,
        val builtAt: String, val apkUrl: String, val shaUrl: String,
        val notes: String,
    )

    fun latest(releases: JSONArray): Candidate? =
        (0 until releases.length()).mapNotNull { parse(releases.optJSONObject(it)) }
            .maxByOrNull { it.versionCode }

    fun parse(release: JSONObject?): Candidate? {
        if (release == null || release.optBoolean("draft") || !release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name")
        if (!tag.matches(Regex("exp-buick-test-rescue-collector-[0-9]{14}"))) return null
        val body = release.optString("body")
        fun field(key: String) = body.lineSequence()
            .firstOrNull { it.startsWith("$key=") }?.substringAfter('=').orEmpty().trim()
        if (field("artifactKind") != "experiment" || field("updaterSchema") != "2" ||
            field("baseBranch") != "Buick-E5" || field("sourceBranch") != SOURCE ||
            field("apkName") != APK) return null
        val name = field("versionName")
        if (!name.matches(Regex("[0-9]{14}")) || tag != PREFIX + name) return null
        val code = field("versionCode").toLongOrNull() ?: return null
        if (code <= 0 || code > Int.MAX_VALUE) return null
        val built = field("builtAt")
        if (!built.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"))) return null
        val list = release.optJSONArray("assets") ?: return null
        var binary: String? = null
        var digest: String? = null
        val prefix = ROOT + tag + "/"
        for (i in 0 until list.length()) {
            val a = list.optJSONObject(i) ?: continue
            val n = a.optString("name")
            val u = a.optString("browser_download_url")
            if (u != prefix + n) continue
            if (n == APK) binary = u
            if (n == "$APK.sha256") digest = u
        }
        if (binary == null || digest == null) return null
        val notes = body.substringAfter("## 取证专用版说明", "")
            .substringBefore("## ").trim().take(900)
        return Candidate(tag, name, code, built, binary, digest, notes)
    }
}
