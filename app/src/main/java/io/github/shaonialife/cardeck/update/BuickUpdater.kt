package io.github.shaonialife.cardeck.update

import android.app.Activity
import android.app.AlertDialog
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
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Only this branch's own release is accepted; never switch update sources automatically. */
object BuickUpdater {
    private const val RELEASE_TAG = "buick-e5-latest"
    private const val SOURCE_BRANCH = "Buick-E5"
    private const val APK_NAME = "CarDeck-Buick-E5.apk"
    private const val RELEASES = "https://api.github.com/repos/chengshl/cardeck/releases/tags/buick-e5-latest"
    private const val PREF = "buick_e5_updater"
    private const val KEY_AUTO = "auto_check"
    private val main = Handler(Looper.getMainLooper())
    private fun downloadedApk(activity: Activity) = File(activity.cacheDir, "updates/buick-e5-latest/CarDeck-Buick-E5.apk")

    /**
     * Keep CarPlay process-bound to E5 Wi-Fi; route updater requests independently.
     * Binding the *whole* process to the head-unit hotspot otherwise causes
     * normal URL.openConnection() requests to bypass an active Android VPN.
     * Network.openConnection() binds only this HTTPS transaction, without
     * changing CarPlay RTSP, event, multicast or Bluetooth transport.
     */
    private data class UpdateRoute(val network: Network?, val label: String)

    private fun updateRoutes(activity: Activity): List<UpdateRoute> {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = runCatching {
            cm.allNetworks.mapNotNull { network ->
                val capabilities = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                val vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                // Some head-unit hotspots actually provide working Internet, even
                // when Android has not finished / cannot complete validation.
                val processBound = network == cm.boundNetworkForProcess
                if (!internet && !vpn && !processBound) return@mapNotNull null
                val rank = when {
                    vpn -> 0
                    processBound && wifi -> 1
                    wifi -> 2
                    cellular -> 3
                    else -> 4
                }
                val label = when {
                    vpn -> "VPN"
                    processBound && wifi -> "车机 Wi-Fi"
                    wifi -> "Wi-Fi"
                    cellular -> "移动数据"
                    else -> "系统网络"
                } + if (validated) "（已验证）" else "（未验证）"
                Triple(rank, network, label)
            }.sortedBy { it.first }
                .map { UpdateRoute(it.second, it.third) }
        }.getOrElse {
            Log.w("BuickUpdater", "Enumerating networks failed", it)
            emptyList()
        }
        // Last resort: retain the existing process-bound route rather than
        // unbinding it and risking disruption of an active CarPlay session.
        return candidates + UpdateRoute(null, "当前进程默认网络")
    }

    private fun openHttp(
        url: String,
        route: UpdateRoute,
        connectMs: Int,
        readMs: Int,
    ): HttpURLConnection {
        val remote = URL(url)
        return ((route.network?.openConnection(remote) ?: remote.openConnection()) as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CarDeck-Buick-E5-Updater")
        }
    }

    private fun <T> tryRoutes(
        activity: Activity,
        task: String,
        operation: (UpdateRoute) -> T,
    ): T {
        var lastError: Exception? = null
        for (route in updateRoutes(activity)) {
            try {
                Log.i("BuickUpdater", "$task: trying ${route.label}")
                return operation(route)
            } catch (error: Exception) {
                lastError = error
                Log.w("BuickUpdater", "$task via ${route.label} failed: ${error.message}")
            }
        }
        throw IllegalStateException("$task 所有网络线路均失败：${lastError?.message ?: "无可用网络"}", lastError)
    }

    fun autoEnabled(activity: Activity): Boolean {
        val shared = activity.getSharedPreferences(PREF, 0)
        if (shared.contains(KEY_AUTO)) return shared.getBoolean(KEY_AUTO, true)
        // Keep the user's previous auto-update preference when migrating from
        // either branch-specific updater; don't unexpectedly re-enable it.
        for (previous in listOf("cardeck_shared_updater", "buick_updater", "buick_main_updater")) {
            val legacy = activity.getSharedPreferences(previous, 0)
            if (legacy.contains(KEY_AUTO)) return legacy.getBoolean(KEY_AUTO, true)
        }
        return true
    }

    fun setAutoEnabled(activity: Activity, enabled: Boolean) {
        activity.getSharedPreferences(PREF, 0).edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    fun check(activity: Activity, silent: Boolean = false) {
        thread(name = "buick-update-check") {
            try {
                val release = tryRoutes(activity, "检查 GitHub 更新") { route ->
                    val conn = openHttp(RELEASES, route, 15_000, 20_000)
                    try {
                        conn.setRequestProperty("Accept", "application/vnd.github+json")
                        require(conn.responseCode == 200) { "GitHub HTTP ${conn.responseCode}" }
                        JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    } finally {
                        conn.disconnect()
                    }
                }
                require(release.optString("tag_name") == RELEASE_TAG && !release.optBoolean("draft")) {
                    "更新标签不匹配，拒绝安装"
                }
                val assets = release.getJSONArray("assets")
                var apkUrl: String? = null
                var digestUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name == APK_NAME) apkUrl = asset.optString("browser_download_url")
                    if (name == "$APK_NAME.sha256") digestUrl = asset.optString("browser_download_url")
                }
                val code = release.optString("body").lineSequence()
                    .firstOrNull { it.startsWith("versionCode=") }
                    ?.substringAfter("=")?.trim()?.toLongOrNull()
                    ?: throw IllegalStateException("Release 缺少 versionCode")
                val versionName = release.optString("body").lineSequence()
                    .firstOrNull { it.startsWith("versionName=") }
                    ?.substringAfter("=")?.trim()
                    ?.takeIf { it.matches(Regex("[0-9]{14}")) }
                    ?: code.toString()
                val branch = release.optString("body").lineSequence()
                    .firstOrNull { it.startsWith("branch=") }
                    ?.substringAfter("=")?.trim().orEmpty()
                val changeNotes = UpdateNotes.extract(release.optString("body"))
                require(branch == SOURCE_BRANCH) {
                    "更新来源分支不匹配：$branch，预期 $SOURCE_BRANCH"
                }
                val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val installed = if (Build.VERSION.SDK_INT >= 33) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
                val installedVersionName = packageInfo.versionName ?: installed.toString()
                val url = apkUrl ?: throw IllegalStateException("Release 未提供 APK")
                val shaUrl = digestUrl ?: throw IllegalStateException("Release 未提供 SHA256")
                main.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    if (code <= installed) {
                        if (!silent) {
                            info(
                                activity,
                                "已是最新版本（$installedVersionName）\n" +
                                    "发布分支：$branch\n\n更新说明：\n$changeNotes",
                            )
                        }
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle("发现 Buick-E5 更新")
                            .setMessage(
                                "当前版本：$installedVersionName\n" +
                                    "最新版本：$versionName\n" +
                                    "来源分支：$branch\n\n" +
                                    "更新说明：\n$changeNotes",
                            )
                            .setNegativeButton("取消", null)
                            .setNeutralButton("安装已下载包") { _, _ -> install(activity, downloadedApk(activity)) }
                            .setPositiveButton("下载") { _, _ -> download(activity, url, shaUrl) }
                            .show()
                    }
                }
            } catch (e: Exception) {
                if (!silent) main.post { if (!activity.isFinishing) info(activity, "检查更新失败：${e.message}") }
            }
        }
    }

    private fun download(activity: Activity, url: String, shaUrl: String) {
        val dialog = android.app.ProgressDialog(activity).apply {
            setTitle("下载 Buick-E5 更新")
            setMessage("正在下载…")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }
        thread(name = "buick-update-download") {
            val target = downloadedApk(activity)
            try {
                target.parentFile?.mkdirs()
                tryRoutes(activity, "下载并校验 APK") { route ->
                    // A route failure restarts this attempt from byte zero; never
                    // mistake an incomplete APK from another route for success.
                    target.delete()
                    main.post {
                        dialog.progress = 0
                        dialog.setMessage("正在通过${route.label}下载…")
                    }
                    val shaConn = openHttp(shaUrl, route, 15_000, 20_000)
                    val expected = try {
                        require(shaConn.responseCode == 200) { "SHA256 HTTP ${shaConn.responseCode}" }
                        shaConn.inputStream.bufferedReader().use {
                            it.readText().trim().split(Regex("\\s+"))[0].lowercase()
                        }
                    } finally {
                        shaConn.disconnect()
                    }
                    require(expected.matches(Regex("[0-9a-f]{64}"))) { "SHA256 格式无效" }
                    val conn = openHttp(url, route, 20_000, 45_000)
                    val actual = try {
                        require(conn.responseCode == 200) { "APK HTTP ${conn.responseCode}" }
                        val total = conn.contentLengthLong
                        val digest = MessageDigest.getInstance("SHA-256")
                        conn.inputStream.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var count = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    digest.update(buffer, 0, n)
                                    count += n
                                    if (total > 0) {
                                        val progress = (count * 100 / total).toInt()
                                        main.post { dialog.progress = progress }
                                    }
                                }
                            }
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    } finally {
                        conn.disconnect()
                    }
                    require(actual == expected) { "SHA256 校验失败，拒绝安装" }
                }
                main.post {
                    dialog.dismiss()
                    if (activity.isFinishing || activity.isDestroyed) {
                        // Keep verified APK in cache; next manual update check can resume installation.
                        return@post
                    }
                    install(activity, target)
                }
            } catch (e: Exception) {
                target.delete()
                main.post {
                    dialog.dismiss()
                    if (!activity.isFinishing) info(activity, "更新下载失败：${e.message}")
                }
            }
        }
    }

    /** Verified APKs remain in cache so permission/settings interruptions are recoverable. */
    private fun install(activity: Activity, target: File) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!target.isFile || target.length() == 0L) {
            info(activity, "安装包不存在，请重新下载。")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity).setTitle("允许安装更新")
                .setMessage("安装包已下载并通过 SHA-256 校验。请允许 CarDeck 安装未知应用，返回后重新点击检查更新，即可继续安装，无需重新下载。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("前往授权") { _, _ ->
                    try {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")))
                    } catch (e: Exception) {
                        info(activity, "无法打开安装权限设置：${e.message}")
                    }
                }.show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activity,
                "${activity.packageName}.updatefiles", target)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "选择安装程序"))
        } catch (e: Exception) {
            AlertDialog.Builder(activity).setTitle("无法打开安装窗口")
                .setMessage("安装包已保留，无需重新下载。\n原因：${e.message}")
                .setNegativeButton("稍后", null)
                .setPositiveButton("重试安装") { _, _ -> install(activity, target) }
                .show()
        }
    }

    private fun info(activity: Activity, message: String) {
        AlertDialog.Builder(activity).setTitle("CarDeck 在线更新")
            .setMessage(message).setPositiveButton("确定", null).show()
    }
}
