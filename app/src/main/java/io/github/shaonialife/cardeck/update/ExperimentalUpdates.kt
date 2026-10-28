package io.github.shaonialife.cardeck.update

import android.app.Activity
import android.content.Context
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
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
 * E5诊断实验和各Buick安装包共用的受限GitHub实验更新入口。
 * 自动/手动检查共享更新渠道配置；安装来源为实验包时只检查同源实验，
 * 防止其他E5实验依靠更高versionCode误抢占更新。
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

    /** 目录按具体 sourceBranch 分组，保留 E5 既有跨项目浏览入口。 */
    fun loadProjects(activity: Activity, onResult: (List<ExperimentProjectCatalog.Project>?, String?) -> Unit) {
        val branch = owningBranch(activity)
        if (branch == null) {
            onResult(emptyList(), "当前APK没有受支持的试验分支")
            return
        }
        thread(name = "experiment-project-list") {
            try {
                val projects = ExperimentProjectCatalog.group(parseCandidates(
                    requestJson(activity, "https://api.github.com/repos/$REPOSITORY/releases?per_page=100"), branch))
                ui.post { if (!activity.isFinishing && !activity.isDestroyed) onResult(projects, null) }
            } catch (error: Exception) {
                ui.post { if (!activity.isFinishing && !activity.isDestroyed) onResult(null, error.message ?: "目录不可用") }
            }
        }
    }

    fun chooseProject(activity: Activity, onChanged: () -> Unit) {
        loadProjects(activity) { projects, error ->
            when {
                error != null -> message(activity, "实验目录读取失败：$error")
                projects.isNullOrEmpty() -> message(activity, "当前暂无可订阅的实验项目")
                else -> {
                    val old = UpdatePreference.selectedProject(activity)
                    val items = listOf(ExperimentPickerDialog.Item(
                        "停止关注实验项目", "保留试验频道，不自动检查具体项目", old == null)) +
                        projects.map { ExperimentPickerDialog.Item(
                            ExperimentProjectCatalog.name(it.sourceBranch), it.newest.versionName,
                            it.sourceBranch == old) }
                    ExperimentPickerDialog.show(activity, "选择实验项目",
                        "自动检查仅限所选项目；手动浏览仍可查看全部实验。", items) { index ->
                        UpdatePreference.selectProject(activity,
                            if (index == 0) null else projects[index - 1].sourceBranch)
                        onChanged()
                    }
                }
            }
        }
    }

    /** 软件更新页手动浏览历史试验包，不更改已保存的渠道选择。 */
    fun browse(activity: Activity) {
        val branch = owningBranch(activity)
        if (branch == null) message(activity, "当前APK没有受支持的试验分支。")
        else browse(activity, branch)
    }

    /**
     * 自动检查只读取当前包名对应最新合法实验预发布，不自动下载或安装。
     * 用户仍可通过browse选择任一可覆盖安装的历史实验包。
     */
    fun check(activity: Activity, silent: Boolean = false) {
        val branch = owningBranch(activity)
        if (branch == null) {
            if (!silent) message(activity, "当前APK没有受支持的试验分支。")
            return
        }
        thread(name = "experiment-release-check") {
            try {
                val releases = requestJson(activity,
                    "https://api.github.com/repos/$REPOSITORY/releases?per_page=100")
                val installedOrigin = activity.getString(
                    io.github.shaonialife.cardeck.R.string.cardeck_installed_build_origin
                )
                val sourceOnly = installedOrigin.takeIf {
                    it.startsWith("experiment/" + branchSlug(branch) + "-")
                }
                val selectedSource = UpdatePreference.selectedProject(activity)
                // Explicit subscription wins; otherwise preserve the existing installed-origin default.
                // Never let a higher version from a different project hijack a subscribed track.
                val effectiveSource = selectedSource ?: sourceOnly
                val newest = parseCandidates(releases, branch)
                    .firstOrNull { effectiveSource == null || it.sourceBranch == effectiveSource }
                val pkg = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val installed = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else {
                    @Suppress("DEPRECATION")
                    pkg.versionCode.toLong()
                }
                ui.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    when {
                        newest == null -> if (!silent) message(activity, "$branch 暂无已验证的试验版本。")
                        newest.versionCode > installed -> confirm(activity, newest)
                        !silent -> confirm(activity, newest)
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

    private fun browse(activity: Activity, branch: String) {
        thread(name = "experiment-release-list") {
            try {
                val json = requestJson(activity, "https://api.github.com/repos/$REPOSITORY/releases?per_page=100")
                val candidates = parseCandidates(json, branch)
                ui.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    if (candidates.isEmpty()) {
                        message(activity, "当前 $branch 暂无已发布的可安装实验包。\n" +
                            "Actions 构建产物不等于公开实验 Release；没有通过签名和安全校验的版本不会出现在这里。")
                    } else {
                        val pkg = activity.packageManager.getPackageInfo(activity.packageName, 0)
                        val installed = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else {
                            @Suppress("DEPRECATION")
                            pkg.versionCode.toLong()
                        }
                        val choices = candidates.map { candidate ->
                            val status = if (ExperimentInstallPolicy.mode(installed, candidate.versionCode) ==
                                ExperimentInstallPolicy.Mode.UPGRADE) "可升级" else "仅下载"
                            UpdateDialogUi.Choice(
                                title = candidate.sourceBranch.substringAfterLast('/'),
                                detail = candidate.versionName + "\n" +
                                    ReleaseBuildTime.label("builtAt=${candidate.builtAt}", candidate.versionName),
                                status = status,
                                selected = { confirm(activity, candidate) },
                            )
                        }
                        UpdateDialogUi.choices(activity, "$branch · 实验版本",
                            "已安装：${pkg.versionName ?: installed.toString()} · 选择版本查看详情", choices)
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
        val saveOnly = ExperimentInstallPolicy.mode(installed, candidate.versionCode) ==
            ExperimentInstallPolicy.Mode.SAVE_ONLY
        val warning = if (saveOnly) {
            "这份 APK 的 versionCode 不高于当前已安装版本。可以保存到下载/CarDeck 文件夹，" +
                "但手动打开也不能绕过 Android 降级限制；卸载当前应用再安装会丢失应用数据和授权。"
        } else {
            "这是实验版本，可能不稳定。返回正式版本须等待 versionCode 更高的正式构建。"
        }
        UpdateDialogUi.details(
            a = activity,
            title = "实验版本详情",
            subtitle = if (saveOnly) "版本较旧 · 只提供 APK 下载" else "可覆盖升级 · 安装前会断开车机连接",
            fields = listOf(
                "所属分支" to candidate.baseBranch,
                "实验来源" to candidate.sourceBranch,
                "当前版本" to (pkg.versionName ?: installed.toString()),
                "目标版本" to candidate.versionName,
                "构建完成" to ReleaseBuildTime.label("builtAt=${candidate.builtAt}", candidate.versionName),
                "校验方式" to "官方预发布 · SHA-256 · APK 包名/版本 · 系统签名",
            ),
            notes = candidate.notes,
            warning = warning,
            primary = if (saveOnly) "下载 APK" else "下载并安装",
            onPrimary = { download(activity, candidate, saveOnly) },
        )
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

    private fun download(activity: Activity, candidate: Candidate, saveOnly: Boolean) {
        val progress = UpdateDialogUi.progress(activity, "下载并验证实验 APK")
        thread(name = "experiment-apk-download") {
            val target = File(activity.cacheDir, "updates/experiments/${candidate.tag}/${candidate.apkName}")
            var problem: Exception? = null
            var verified = false
            target.parentFile?.mkdirs()
            for (route in networks(activity)) {
                try {
                    target.delete()
                    val expected = connection(candidate.shaUrl, route).let { connection ->
                        try {
                            require(connection.responseCode == 200) { "SHA256 HTTP ${connection.responseCode}" }
                            connection.inputStream.bufferedReader().use {
                                it.readText().trim().split(Regex("\\s+")).first().lowercase(Locale.US)
                            }
                        } finally { connection.disconnect() }
                    }
                    require(expected.matches(Regex("[0-9a-f]{64}"))) { "校验值不完整" }
                    val actual = connection(candidate.apkUrl, route).let { connection ->
                        try {
                            require(connection.responseCode == 200) { "APK HTTP ${connection.responseCode}" }
                            val digest = MessageDigest.getInstance("SHA-256")
                            val total = connection.contentLengthLong
                            connection.inputStream.use { input ->
                                target.outputStream().use { output ->
                                    val buffer = ByteArray(65536)
                                    var totalRead = 0L
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        output.write(buffer, 0, n)
                                        digest.update(buffer, 0, n)
                                        totalRead += n
                                        if (total > 0) ui.post {
                                            progress.setProgress((totalRead * 100 / total).toInt())
                                        }
                                    }
                                }
                            }
                            digest.digest().joinToString("") { "%02x".format(it) }
                        } finally { connection.disconnect() }
                    }
                    require(actual == expected) { "APK SHA-256 校验失败" }
                    val archive = activity.packageManager.getPackageArchiveInfo(target.absolutePath, 0)
                        ?: throw IllegalStateException("不是有效 APK")
                    require(archive.packageName == activity.packageName) { "APK 包名与当前 E5 应用不同" }
                    val archiveVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else {
                        @Suppress("DEPRECATION")
                        archive.versionCode.toLong()
                    }
                    require(archiveVersion == candidate.versionCode) { "APK 版本号与发布元数据不一致" }
                    verified = true
                    break
                } catch (error: Exception) {
                    problem = error
                    target.delete()
                }
            }
            // Only verified bytes go to publicly accessible Downloads.
            val exported = if (verified && saveOnly) runCatching {
                saveToDownloads(activity, target, candidate.apkName)
            } else null
            ui.post {
                progress.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@post
                when {
                    !verified -> message(activity, "APK 下载或校验失败：${problem?.message}")
                    saveOnly && exported?.isSuccess == true -> {
                        val saved = exported.getOrThrow()
                        UpdateDialogUi.details(
                            a = activity,
                            title = "APK 已保存",
                            subtitle = "下载 / CarDeck / ${candidate.apkName}",
                            notes = "安装包已验证 SHA-256、应用包名与版本号，可以在手机文件管理器中找到。",
                            warning = "旧版本无法直接覆盖。卸载当前应用再安装将丢失应用数据和授权，请自行备份。",
                            primary = "打开 APK",
                            onPrimary = { openSavedApk(activity, saved) },
                            secondary = "完成",
                        )
                    }
                    saveOnly -> message(activity,
                        "APK 已校验，但保存到下载目录失败：${exported?.exceptionOrNull()?.message}")
                    else -> install(activity, target)
                }
            }
        }
    }

    private fun saveToDownloads(activity: Activity, apk: File, name: String): Uri {
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CarDeck")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法在下载目录创建文件")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IllegalStateException("无法写入下载目录")
            stream.use { output -> apk.inputStream().use { input -> input.copyTo(output) } }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun openSavedApk(activity: Activity, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "手动打开已下载 APK"))
        } catch (error: Exception) {
            message(activity, "无法打开 APK：${error.message}。请使用文件管理器进入 下载/CarDeck。")
        }
    }

    private fun install(activity: Activity, apk: File) {
        if (!apk.isFile || apk.length() == 0L) return message(activity, "已验证的安装包不存在。")
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            UpdateDialogUi.details(
                a = activity,
                title = "允许安装实验 APK",
                notes = "校验已通过。请允许 CarDeck 安装未知应用，然后从实验列表再次安装。文件已缓存在应用内。",
                primary = "前往授权",
                onPrimary = {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
                },
                secondary = "稍后",
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
        UpdateDialogUi.message(activity, "CarDeck 实验版本", text)
    }
}
