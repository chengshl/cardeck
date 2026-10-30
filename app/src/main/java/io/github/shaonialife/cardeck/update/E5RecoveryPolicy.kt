package io.github.shaonialife.cardeck.update

import org.json.JSONArray

/**
 * Narrow E5 #33 recovery allowlist. A recovery is not an experiment update.
 * It must point to the EXACT published experiment tag for the APK installed now.
 * New recovery families require an explicit source/security review.
 */
internal object E5RecoveryPolicy {
    const val EXPERIMENT_SOURCE = "experiment/buick-e5-iap2-stale-ack-guard-33"
    private const val RECOVERY_SOURCE = "experiment/buick-e5-iap2-ack-33-stable-restore"
    private const val STABLE_RUNTIME = "8b62f9a878378cf2705e93d00fcd7263d055ad4b"
    private const val APK = "CarDeck-Buick-E5-IAP2-ACK-Stable-Restore.apk"
    private const val REPOSITORY = "chengshl/cardeck"
    private val safeTag = Regex("[A-Za-z0-9_-]{8,120}")
    private val sha = Regex("[0-9a-f]{40}")
    private val timestamp = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
    private val version = Regex("EXP-STABLE-RESTORE-[0-9A-F]{12}-[0-9]{9}")

    fun eligible(
        releases: JSONArray,
        installedOrigin: String,
        installedName: String,
        installedCode: Long,
    ): ExperimentalUpdates.Candidate? {
        if (installedOrigin != EXPERIMENT_SOURCE || installedCode <= 0L) return null
        val installedRelease = ExperimentalUpdates.parseCandidates(releases, "Buick-E5")
            .singleOrNull {
                it.sourceBranch == installedOrigin &&
                    it.versionName == installedName &&
                    it.versionCode == installedCode
            } ?: return null
        val candidates = mutableListOf<ExperimentalUpdates.Candidate>()
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (!release.optBoolean("prerelease") || release.optBoolean("draft")) continue
            val tag = release.optString("tag_name")
            if (!safeTag.matches(tag)) continue
            val body = release.optString("body")
            fun field(key: String) = ReleaseBuildTime.metadata(body, key)
            if (field("artifactKind") != "recovery" ||
                field("recoveryFor") != installedRelease.tag ||
                field("baseBranch") != "Buick-E5" ||
                field("sourceBranch") != RECOVERY_SOURCE ||
                field("stableRuntimeCommit") != STABLE_RUNTIME) continue
            val name = field("versionName") ?: continue
            if (!version.matches(name) ||
                tag != "e5-stable-restore-after-iap2-ack-33-$name") continue
            val code = field("versionCode")?.toLongOrNull() ?: continue
            if (code <= installedCode || code > Int.MAX_VALUE) continue
            val builtAt = field("builtAt") ?: continue
            if (!timestamp.matches(builtAt)) continue
            if (field("apkName") != APK) continue
            if (field("buildCommit")?.matches(sha) != true) continue
            val assets = release.optJSONArray("assets") ?: continue
            val prefix = "https://github.com/$REPOSITORY/releases/download/$tag/"
            var apkUrl: String? = null
            var shaUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val url = asset.optString("browser_download_url")
                when (asset.optString("name")) {
                    APK -> if (url == prefix + APK) apkUrl = url
                    "$APK.sha256" -> if (url == prefix + "$APK.sha256") shaUrl = url
                }
            }
            if (apkUrl == null || shaUrl == null) continue
            candidates.add(ExperimentalUpdates.Candidate(
                tag, "Buick-E5", RECOVERY_SOURCE, name, code, builtAt, APK,
                apkUrl, shaUrl, UpdateNotes.extract(body)
            ))
        }
        return candidates.maxByOrNull { it.versionCode }
    }
}
