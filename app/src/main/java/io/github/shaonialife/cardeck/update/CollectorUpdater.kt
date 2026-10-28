package io.github.shaonialife.cardeck.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import io.github.shaonialife.cardeck.service.RescueTraceService
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Checking requires visible user activity. Never executes inside RescueTraceService. */
internal object CollectorUpdater {
    private const val PREF = "collector_update"
    private const val AUTO = "auto_check"
    private const val LAST = "last_check_ms"
    private const val INTERVAL_MS = 30 * 60 * 1000L
    private const val API = "https://api.github.com/repos/chengshl/cardeck/releases?per_page=100"
    private val ui = Handler(Looper.getMainLooper())

    fun autoEnabled(a: Activity): Boolean =
        a.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(AUTO, true)

    fun setAutoEnabled(a: Activity, enabled: Boolean) =
        a.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(AUTO, enabled).apply()

    fun autoCheck(a: Activity) {
        if (!autoEnabled(a)) return
        val prefs = a.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(LAST, 0L)
        if (now >= last && now - last < INTERVAL_MS) return
        prefs.edit().putLong(LAST, now).apply()
        check(a, silent = true)
    }

    fun check(a: Activity, silent: Boolean = false) {
        thread(name = "collector-update-check") {
            try {
                val latest = CollectorReleasePolicy.latest(JSONArray(request(API).decodeToString()))
                val installed = a.packageManager.getPackageInfo(a.packageName, 0).longVersionCode
                ui.post {
                    if (a.isFinishing || a.isDestroyed) return@post
                    when {
                        latest == null -> if (!silent) alert(a, "未找到专职取证正式签名预发布版本")
                        latest.versionCode <= installed -> if (!silent)
                            alert(a, "当前已是最新取证版，或本机版本更高。不会安装旧包。")
                        else -> AlertDialog.Builder(a)
                            .setTitle("取证助手发现更新")
                            .setMessage("当前：${a.packageManager.getPackageInfo(a.packageName, 0).versionName}\n" +
                                "新版本：${latest.versionName}\n" +
                                "来源：${CollectorReleasePolicy.SOURCE}\n" +
                                "构建：${latest.builtAt}\n\n" +
                                (latest.notes.ifBlank { "本地取证增强" }) +
                                "\n\n只在你同意后下载并校验；安装会短暂终止取证，请先导出旧证据。")
                            .setPositiveButton("下载并校验") { _, _ -> download(a, latest) }
                            .setNegativeButton("暂不更新", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                if (!silent) ui.post { if (!a.isFinishing && !a.isDestroyed)
                    alert(a, "检查更新失败：${e.message}") }
            }
        }
    }

    private fun alert(a: Activity, message: String) {
        AlertDialog.Builder(a).setTitle("独立取证更新").setMessage(message)
            .setPositiveButton("知道了", null).show()
    }

    private fun request(url: String, maxBytes: Int = 512 * 1024): ByteArray {
        require(url.startsWith("https://"))
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 45000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CarDeck-Rescue-Standalone")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            require(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    require(output.size() + n <= maxBytes) { "更新信息过大" }
                    output.write(buf, 0, n)
                }
                output.toByteArray()
            }
        } finally { conn.disconnect() }
    }

    private fun target(a: Activity) = File(a.cacheDir, "updates/collector/${CollectorReleasePolicy.APK}")

    private fun download(a: Activity, latest: CollectorReleasePolicy.Candidate) {
        val progress = AlertDialog.Builder(a).setTitle("正在校验取证更新")
            .setMessage("下载时取证服务会继续运行。").setCancelable(false).create()
        progress.show()
        thread(name = "collector-update-download") {
            val out = target(a)
            try {
                out.parentFile?.mkdirs()
                out.delete()
                val sha = request(latest.shaUrl, 1024).decodeToString()
                    .trim().substringBefore(" ").substringBefore("\t").lowercase()
                require(sha.matches(Regex("[0-9a-f]{64}"))) { "SHA-256 清单不合法" }
                val conn = (URL(latest.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "CarDeck-Rescue-Standalone")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                try {
                    require(conn.responseCode == 200) { "下载 HTTP ${conn.responseCode}" }
                    var count = 0L
                    conn.inputStream.use { input -> out.outputStream().use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            count += n
                            require(count <= 64L * 1024 * 1024) { "APK超过64MiB安全上限" }
                            digest.update(buffer, 0, n)
                            stream.write(buffer, 0, n)
                        }
                    }}
                } finally { conn.disconnect() }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual == sha) { "SHA-256校验失败" }
                val pkg = a.packageManager
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                val candidate = pkg.getPackageArchiveInfo(out.absolutePath, flags)
                    ?: error("下载内容不是有效 APK")
                require(candidate.packageName == a.packageName) { "APK包名不匹配" }
                require(candidate.longVersionCode == latest.versionCode) { "APK版本号与Release不同" }
                val current = pkg.getPackageInfo(a.packageName, flags)
                val expectedSigners = current.signingInfo?.apkContentsSigners
                    ?.map { it.toCharsString() }?.toSet().orEmpty()
                val actualSigners = candidate.signingInfo?.apkContentsSigners
                    ?.map { it.toCharsString() }?.toSet().orEmpty()
                require(expectedSigners.isNotEmpty() && expectedSigners == actualSigners) {
                    "APK签名与当前安装版不同；拒绝覆盖"
                }
                ui.post {
                    progress.dismiss()
                    if (!a.isFinishing && !a.isDestroyed) promptInstall(a, out)
                }
            } catch (error: Exception) {
                out.delete()
                ui.post {
                    progress.dismiss()
                    if (!a.isFinishing && !a.isDestroyed) alert(a, "下载或验证失败：${error.message}")
                }
            }
        }
    }

    private fun promptInstall(a: Activity, out: File) {
        AlertDialog.Builder(a)
            .setTitle("更新包校验完成")
            .setMessage("SHA-256、包名、版本和签名已核验。\n\n" +
                "安装会终止当前取证服务，请先在本页手动导出旧日志，再停止采集。\n" +
                (if (RescueTraceService.running) "当前采集仍在运行，请先保存证据。" else "当前取证服务未运行。"))
            .setPositiveButton("我已导出并停止，继续") { _, _ ->
                if (RescueTraceService.running) {
                    alert(a, "请先导出并停止采集，再从下方按钮安装缓存包。")
                } else install(a, out)
            }
            .setNegativeButton("稍后安装", null)
            .show()
    }

    fun installCached(a: Activity) {
        val out = target(a)
        if (!out.isFile || out.length() == 0L) {
            alert(a, "没有已验证的缓存更新包，请重新检查并下载。")
            return
        }
        if (RescueTraceService.running) {
            alert(a, "采集仍在运行；先导出日志、停止采集，再安装缓存版。")
            return
        }
        install(a, out)
    }

    private fun install(a: Activity, out: File) {
        if (RescueTraceService.running || !out.isFile) return
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a).setTitle("需要允许安装更新")
                .setMessage("在系统设置里允许取证助手安装未知应用，然后返回点「安装已验证更新」。")
                .setPositiveButton("打开系统设置") { _, _ ->
                    a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${a.packageName}")))
                }.setNegativeButton("取消", null).show()
            return
        }
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.updatefiles", out)
        a.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
