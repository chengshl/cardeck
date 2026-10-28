package io.github.shaonialife.cardeck.update

import android.app.Activity
import io.github.shaonialife.cardeck.ui.DarkMessageDialog
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
            if (ReleaseBuildTime.metadata(body, "updaterSchema") != "2") continue
            if (ReleaseBuildTime.metadata(body, "baseBranch") != branch) continue
            val source = ReleaseBuildTime.metadata(body, "sourceBranch") ?: continue
            if (!source.startsWith(sourcePrefix) || !source.matches(Regex("experiment/[A-Za-z0-9_/-]+"))) continue
            val name = ReleaseBuildTime.metadata(body, "versionName") ?: continue
            if (!ReleaseVersionName.isExperimental(name, source)) continue
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

    /** 请求官方Release后整理属于当前APK的实验项目；查询失败不能伪装为空列表。 */
    fun loadProjects(
        activity: Activity,
        onResult: (List<ExperimentProjectCatalog.Project>?, String?) -> Unit,
    ) {
        val branch = owningBranch(activity)
        if (branch == null) {
            onResult(emptyList(), "当前APK没有受支持的试验项目")
            return
        }
        thread(name = "experiment-project-list") {
            try {
                val candidates = parseCandidates(
                    requestJson(activity, "https://api.github.com/repos/$REPOSITORY/releases?per_page=100"),
                    branch,
                )
                val projects = ExperimentProjectCatalog.group(candidates)
                ui.post {
                    if (!activity.isFinishing && !activity.isDestroyed) onResult(projects, null)
                }
            } catch (error: Exception) {
                ui.post {
                    if (!activity.isFinishing && !activity.isDestroyed)
                        onResult(null, error.message ?: "实验目录不可用")
                }
            }
        }
    }

    /** 只从可信Release发现功能线。空目录时保持空选择，不订阅任意其他项目。 */
    fun chooseProject(activity: Activity, onChanged: () -> Unit) {
        loadProjects(activity) { projects, error ->
            when {
                error != null -> message(activity, "无法读取实验目录：$error")
                projects == null || projects.isEmpty() -> message(activity,
                    "当前暂无已发布的实验安装包。\n实验分支入口会保留，正式更新不受影响。")
                else -> {
                    val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
                    val currentCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else {
                        @Suppress("DEPRECATION")
                        installed.versionCode.toLong()
                    }
                    val old = UpdatePreference.selectedProject(activity)
                    val items = listOf(ExperimentPickerDialog.Item(
                        "停止关注实验项目", "保留试验频道，暂时不检查具体项目", old == null,
                    )) + projects.map {
                        ExperimentPickerDialog.Item(
                            ExperimentProjectCatalog.name(it.sourceBranch),
                            it.newest.versionName + " · " +
                                ExperimentProjectCatalog.installState(currentCode, it.newest),
                            it.sourceBranch == old,
                        )
                    }
                    ExperimentPickerDialog.show(
                        activity, "选择实验项目",
                        "选择后会一直记住；自动检查不会切换到其他项目。",
                        items,
                    ) { chosen ->
                        val source = if (chosen == 0) null else projects[chosen - 1].sourceBranch
                        UpdatePreference.selectProject(activity, source)
                        onChanged()
                    }
                }
            }
        }
    }

    /** 软件更新页手动浏览历史试验包，不更改已保存的渠道选择。 */
    fun browse(activity: Activity) {
        val branch = owningBranch(activity)
        val source = UpdatePreference.selectedProject(activity)
        when {
            branch == null -> message(activity, "当前APK没有受支持的试验分支。")
            source == null -> message(activity, "请先在软件更新页选择要跟踪的实验项目。")
            else -> browse(activity, branch, source)
        }
    }

    /**
     * 自动检查只读取当前包名对应最新合法实验预发布，不自动下载或安装。
     * 用户仍可通过browse选择任一可覆盖安装的历史实验包。
     */
    fun check(activity: Activity, silent: Boolean = false) {
        val branch = owningBranch(activity)
        val selectedSource = UpdatePreference.selectedProject(activity)
        if (branch == null || selectedSource == null) {
            if (!silent) message(activity, "请先在软件更新页选择要跟踪的实验项目。")
            return
        }
        thread(name = "experiment-release-check") {
            try {
                val releases = requestJson(activity,
                    "https://api.github.com/repos/$REPOSITORY/releases?per_page=100")
                val newest = ExperimentProjectCatalog.latest(parseCandidates(releases, branch), selectedSource)
                val pkg = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val installed = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else {
                    @Suppress("DEPRECATION")
                    pkg.versionCode.toLong()
                }
                ui.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    when {
                        newest == null -> if (!silent) message(activity, "当前关注的实验项目尚未发布符合要求的APK。\n不会自动选择其他项目。")
                        newest.versionCode > installed -> confirm(activity, newest)
                        !silent -> message(activity,
                            "已安装版本：" + (pkg.versionName ?: installed.toString()) + "\n" +
                            "所选项目：" + ExperimentProjectCatalog.name(selectedSource) + "\n" +
                            "已发布版本：" + newest.versionName + "\n" +
                            ReleaseBuildTime.label("builtAt=" + newest.builtAt, newest.versionName) +
                            "\n" + ExperimentProjectCatalog.installState(installed, newest) +
                            "\n按方案B可从该实验源码重新构建更高版本号的签名包，无需卸载。")
                    }
                }
            } catch (error: Exception) {
                ui.post {
                    if (!silent && !activity.isFinishing && !activity.isDestroyed)
                        message(activity, "检查试验分支失败：" + error.message)
                }
            }
        }
    }

    private fun browse(activity: Activity, branch: String, selectedSource: String) {
        thread(name = "experiment-release-list") {
            try {
                val json = requestJson(activity, "https://api.github.com/repos/$REPOSITORY/releases?per_page=100")
                val candidates = parseCandidates(json, branch)
                    .filter { it.sourceBranch == selectedSource }
                ui.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    if (candidates.isEmpty()) {
                        message(activity, "所选项目 ${ExperimentProjectCatalog.name(selectedSource)} 暂无已发布的实验APK。\n" +
                            "Actions 构建产物不等于公开实验 Release；没有通过签名和安全校验的版本不会出现在这里。")
                    } else {
                        val names = candidates.map {
                            ExperimentPickerDialog.Item(
                                it.versionName,
                                ReleaseBuildTime.label("builtAt=" + it.builtAt, it.versionName) +
                                    " · " + ExperimentProjectCatalog.installState(
                                        installedVersion(activity), it,
                                    ),
                            )
                        }
                        ExperimentPickerDialog.show(
                            activity, "所选项目 · 历史构建",
                            ExperimentProjectCatalog.name(selectedSource) + " · 只显示此功能线的发布",
                            names,
                        ) { index -> confirm(activity, candidates[index]) }
                    }
                }
            } catch (error: Exception) {
                ui.post { if (!activity.isFinishing && !activity.isDestroyed) message(activity, "实验版本查询失败：${error.message}") }
            }
        }
    }

    private fun installedVersion(activity: Activity): Long {
        val pkg = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }
    }

    private fun confirm(activity: Activity, candidate: Candidate) {
        val installed = installedVersion(activity)
        val allowed = candidate.versionCode > installed
        val details = buildString {
            append("实验项目：")
            append(ExperimentProjectCatalog.name(candidate.sourceBranch))
            append("\n代码来源：")
            append(candidate.sourceBranch)
            append("\n版本：")
            append(candidate.versionName)
            append("\n")
            append(ReleaseBuildTime.label("builtAt=" + candidate.builtAt, candidate.versionName))
            append("\n版本比较：")
            append(ExperimentProjectCatalog.installState(installed, candidate))
            append("\n\n安全校验：官方Release · APK SHA-256 · 系统签名")
            append("\n\n更新说明：\n")
            append(candidate.notes)
            append("\n\n返回正式版同样需要版本号更高的正式构建，不能直接降级。")
        }
        val actions = if (allowed) listOf(
            DarkMessageDialog.Action("取消") {},
            DarkMessageDialog.Action("下载并安装", DarkMessageDialog.Emphasis.PRIMARY) {
                download(activity, candidate)
            },
        ) else listOf(DarkMessageDialog.Action("返回") {})
        DarkMessageDialog.show(activity, "确认实验版本", details, actions)
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
        val progress = ExperimentDownloadDialog.show(activity)
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
            DarkMessageDialog.show(
                activity,
                "允许安装实验 APK",
                "已验证 SHA-256。请允许当前 CarDeck 安装未知应用，再返回软件更新页面安装。",
                listOf(
                    DarkMessageDialog.Action("稍后") {},
                    DarkMessageDialog.Action("前往授权", DarkMessageDialog.Emphasis.PRIMARY) {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")))
                    },
                ),
            )
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
        DarkMessageDialog.show(activity, "CarDeck 软件更新", text,
            listOf(DarkMessageDialog.Action("知道了") {}))
    }
}
