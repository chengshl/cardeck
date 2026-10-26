package io.github.shaonialife.cardeck.update

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.shaonialife.cardeck.SessionController
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 各 Buick 正式分支共用的实验包手动选择入口。
 * 正式自动检查仍由 BuickUpdater 独立控制；本类不持久化/修改自动更新通道。
 * 实验包只从带受限元数据、APK + SHA256 的独立 GitHub prerelease 获取。
 */
internal object ExperimentalUpdates {
    private const val REPOSITORY = "chengshl/cardeck"
    private val ui = Handler(Looper.getMainLooper())
    private data class Route(val network: Network?)

    internal data class Candidate(
        val tag: String,
        val baseBranch: String,
        val sourceBranch: String,
        val versionName: String,
        val versionCode: Long,
        val builtAt: String,
        val apkName: String,
        val apkUrl: String,
        val shaUrl: String,
        val notes: String,
    )

    private fun owningBranch(activity: Activity): String? = when (activity.packageName) {
        "io.github.buick.e5.cardeck" -> "Buick-E5"
        "io.github.buick.main.cardeck" -> "Buick-main"
        "io.github.buick.test.cardeck" -> "Buick-Test"
        else -> null
    }

    private fun branchSlug(branch: String): String = when (branch) {
        "Buick-E5" -> "buick-e5"
        "Buick-main" -> "buick-main"
        "Buick-Test" -> "buick-test"
        else -> ""
    }

    /** 拒绝跨分支、非预发布、缺少真实构建元数据和无校验文件的条目。 */
    internal fun parseCandidates(releases: JSONArray, branch: String): List<Candidate> {
        val slug = branchSlug(branch)
        if (slug.isEmpty()) return emptyList()
        val prefix = "exp-$slug-"
        val sourcePrefix = "experiment/$slug-"
        val results = mutableListOf<Candidate>()
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (!release.optBoolean("prerelease") || release.optBoolean("draft")) continue
            val tag = release.optString("tag_name")
            if (!tag.startsWith(prefix) || !tag.matches(Regex("[A-Za-z0-9_-]{8,100}"))) continue
            val body = release.optString("body")
            if (ReleaseBuildTime.metadata(body, "artifactKind") != "experiment") continue
            if (ReleaseBuildTime.metadata(body, "baseBranch") != branch) continue
            val source = ReleaseBuildTime.metadata(body, "sourceBranch") ?: continue
            if (!source.startsWith(sourcePrefix) || !source.matches(Regex("experiment/[A-Za-z0-9_/-]+"))) continue
            val name = ReleaseBuildTime.metadata(body, "versionName") ?: continue
            if (!name.matches(Regex("[0-9]{14}"))) continue
            val code = ReleaseBuildTime.metadata(body, "versionCode")?.toLongOrNull() ?: continue
            if (code < 1L || code > Int.MAX_VALUE) continue
            val builtAt = ReleaseBuildTime.metadata(body, "builtAt") ?: continue
            if (!builtAt.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"))) continue
            val apk = ReleaseBuildTime.metadata(body, "apkName") ?: continue
            if (!apk.matches(Regex("[A-Za-z0-9_-]+\\.apk"))) continue
            // 只信任官方仓库Release对应的下载目录，防止元数据跨站引流。
            val assets = release.optJSONArray("assets") ?: continue
            val prefixUrl = "https://github.com/$REPOSITORY/releases/download/$tag/"
            var apkUrl: String? = null
            var shaUrl: String? = null
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val url = asset.optString("browser_download_url")
                if (!url.startsWith(prefixUrl)) continue
                when (asset.optString("name")) {
                    apk -> if (url == prefixUrl + apk) apkUrl = url
                    "$apk.sha256" -> if (url == prefixUrl + "$apk.sha256") shaUrl = url
                }
            }
            if (apkUrl == null || shaUrl == null) continue
            results.add(Candidate(tag, branch, source, name, code, builtAt, apk, apkUrl, shaUrl,
                UpdateNotes.extract(body)))
        }
        return results.sortedByDescending { it.versionCode }
    }

    fun showChannelPicker(activity: Activity) {
        val branch = owningBranch(activity)
        if (branch == null) {
            message(activity, "当前包未配置独立的别克更新通道。")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("在线更新 · $branch")
            .setItems(arrayOf("正式版本 · 当前分支自动更新通道", "实验版本 · 手动查看和安装（不会自动升级）")) { _, selected ->
                if (selected == 0) BuickUpdater.check(activity) else browse(activity, branch)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun browse(activity: Activity, branch: String) {
        thread(name = "experiment-release-list") {
            try {
                val json = requestJson(activity, "https://api.github.com/repos/$REPOSITORY/releases?per_page=100")
                val candidates = parseCandidates(json, branch)
                ui.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    if (candidates.isEmpty()) {
                        message(activity, "当前 $branch 暂无已发布的可安装实验包。\\n" +
                            "Actions 构建产物不等于公开实验 Release；没有通过签名和安全校验的版本不会出现在这里。")
                    } else {
                        val names = candidates.map {
                            "${it.sourceBranch.substringAfterLast('/')} · ${it.versionName}\\n" +
                                ReleaseBuildTime.label("builtAt=${it.builtAt}", it.versionName)
                        }.toTypedArray()
                        AlertDialog.Builder(activity)
                            .setTitle("$branch · 实验版本（手动安装）")
                            .setItems(names) { _, index -> confirm(activity, candidates[index]) }
                            .setNegativeButton("返回", null)
                            .show()
                    }
                }
            } catch (error: Exception) {
                ui.post { if (!activity.isFinishing && !activity.isDestroyed) message(activity, "实验版本查询失败：${error.message}") }
            }
        }
    }

    private fun confirm(activity: Activity, candidate: Candidate) {
        val pkg = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val installed = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }
        val reason = when {
            candidate.versionCode < installed -> "\\n\\n此实验包版本号低于已安装版本，Android 不允许覆盖降级。"
            candidate.versionCode == installed -> "\\n\\n版本号相同，覆盖安装可能被拒绝。"
            else -> ""
        }
        val allowed = candidate.versionCode > installed
        AlertDialog.Builder(activity)
            .setTitle("确认安装实验版本")
            .setMessage(
                "目标：${candidate.baseBranch}\\n" +
                    "实验：${candidate.sourceBranch}\\n" +
                    "版本：${candidate.versionName}\\n" +
                    ReleaseBuildTime.label("builtAt=${candidate.builtAt}", candidate.versionName) + "\\n" +
                    "校验：官方预发布 + SHA-256 + 系统签名校验\\n\\n" +
                    "更新说明：\\n${candidate.notes}\\n\\n" +
                    "实验包不是稳定更新。返回正式版本需等待版本号更高的正式构建，不能随意降级。" + reason,
            )
            .setNegativeButton("取消", null)
            .setPositiveButton(if (allowed) "下载并安装" else "无法覆盖") { _, _ ->
                if (allowed) download(activity, candidate)
            }
            .show()
    }

    private fun networks(activity: Activity): List<Route> {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val choices = runCatching {
            cm.allNetworks.mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val allowed = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || vpn || network == cm.boundNetworkForProcess
                if (!allowed) return@mapNotNull null
                val rank = when {
                    vpn -> 0
                    network == cm.boundNetworkForProcess && wifi -> 1
                    wifi -> 2
                    cellular -> 3
                    else -> 4
                }
                rank to Route(network)
            }.sortedBy { it.first }.map { it.second }
        }.getOrDefault(emptyList())
        // 不改变进程绑定，以免更新检查打断视频/音频。
        return choices + Route(null)
    }

    private fun connection(url: String, route: Route): HttpURLConnection {
        val remote = URL(url)
        return ((route.network?.openConnection(remote) ?: remote.openConnection()) as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 45000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CarDeck-Experiment-Updater")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
    }

    private fun requestJson(activity: Activity, url: String): JSONArray {
        var problem: Exception? = null
        for (route in networks(activity)) {
            try {
                val c = connection(url, route)
                try {
                    require(c.responseCode == 200) { "GitHub HTTP ${c.responseCode}" }
                    return JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    c.disconnect()
                }
            } catch (error: Exception) { problem = error }
        }
        throw IllegalStateException("全部网络线路失败：${problem?.message}", problem)
    }

    private fun download(activity: Activity, candidate: Candidate) {
        val progress = ProgressDialog(activity).apply {
            setTitle("实验版本 · 下载并验证")
            setMessage("正在准备下载…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }
        thread(name = "experiment-apk-download") {
            val target = File(activity.cacheDir, "updates/experiments/${candidate.tag}/${candidate.apkName}")
            var problem: Exception? = null
            var verified = false
            target.parentFile?.mkdirs()
            for (route in networks(activity)) {
                try {
                    target.delete()
                    val expected = connection(candidate.shaUrl, route).let { c ->
                        try {
                            require(c.responseCode == 200) { "SHA256 HTTP ${c.responseCode}" }
                            c.inputStream.bufferedReader().use { it.readText().trim().split(Regex("\\s+")).first().lowercase(Locale.US) }
                        } finally { c.disconnect() }
                    }
                    require(expected.matches(Regex("[0-9a-f]{64}"))) { "校验值不完整" }
                    val actual = connection(candidate.apkUrl, route).let { c ->
                        try {
                            require(c.responseCode == 200) { "APK HTTP ${c.responseCode}" }
                            val digest = MessageDigest.getInstance("SHA-256")
                            val total = c.contentLengthLong
                            c.inputStream.use { input ->
                                target.outputStream().use { output ->
                                    val buffer = ByteArray(65536)
                                    var readTotal = 0L
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        output.write(buffer, 0, n)
                                        digest.update(buffer, 0, n)
                                        readTotal += n
                                        if (total > 0) ui.post { progress.progress = (readTotal * 100 / total).toInt() }
                                    }
                                }
                            }
                            digest.digest().joinToString("") { "%02x".format(it) }
                        } finally { c.disconnect() }
                    }
                    require(actual == expected) { "APK SHA-256 校验失败" }
                    val archive = activity.packageManager.getPackageArchiveInfo(target.absolutePath, 0)
                        ?: throw IllegalStateException("不是有效 APK")
                    require(archive.packageName == activity.packageName) { "包名与当前分支不一致" }
                    val archiveVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else {
                        @Suppress("DEPRECATION")
                        archive.versionCode.toLong()
                    }
                    require(archiveVersion == candidate.versionCode) { "APK版本号与发布元数据不一致" }
                    verified = true
                    break
                } catch (error: Exception) {
                    problem = error
                    target.delete()
                }
            }
            ui.post {
                progress.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (!verified) {
                    message(activity, "实验包下载/校验失败：${problem?.message}")
                } else {
                    install(activity, target)
                }
            }
        }
    }

    private fun install(activity: Activity, apk: File) {
        if (!apk.isFile || apk.length() == 0L) return message(activity, "已验证的安装包不存在。")
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("允许安装实验 APK")
                .setMessage("已验证 SHA-256。请授权 CarDeck 安装未知应用，然后重新进入实验列表安装。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("前往授权") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
                }.show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updatefiles", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // 安装前主动退役车机连接，避免虚拟屏接走PackageInstaller确认窗口。
            SessionController.disconnect(activity.applicationContext)
            activity.startActivity(Intent.createChooser(intent, "选择安装程序"))
        } catch (error: Exception) {
            message(activity, "无法打开系统安装器：${error.message}。安装包仍保存在缓存中。")
        }
    }

    private fun message(activity: Activity, text: String) {
        AlertDialog.Builder(activity).setTitle("CarDeck 实验版本")
            .setMessage(text).setPositiveButton("确定", null).show()
    }
}
