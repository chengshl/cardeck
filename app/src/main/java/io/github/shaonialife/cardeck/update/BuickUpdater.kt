package io.github.shaonialife.cardeck.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Buick-E5 prerelease channel; never consumes upstream/main APKs. */
object BuickUpdater {
    private const val RELEASES = "https://api.github.com/repos/chengshl/cardeck/releases/tags/buick-e5-latest"
    private const val PREF = "buick_updater"
    private const val KEY_AUTO = "auto_check"
    private val main = Handler(Looper.getMainLooper())

    fun autoEnabled(activity: Activity): Boolean =
        activity.getSharedPreferences(PREF, 0).getBoolean(KEY_AUTO, true)

    fun setAutoEnabled(activity: Activity, enabled: Boolean) {
        activity.getSharedPreferences(PREF, 0).edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    fun check(activity: Activity, silent: Boolean = false) {
        thread(name = "buick-update-check") {
            try {
                val conn = (URL(RELEASES).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "CarDeck-Buick-E5")
                }
                val release = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                conn.disconnect()
                val tag = release.optString("tag_name")
                val assets = release.getJSONArray("assets")
                var apkUrl: String? = null
                var digestUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name == "CarDeck-Buick-E5.apk") apkUrl = asset.optString("browser_download_url")
                    if (name == "CarDeck-Buick-E5.apk.sha256") digestUrl = asset.optString("browser_download_url")
                }
                val code = release.optString("body").lineSequence()
                    .firstOrNull { it.startsWith("versionCode=") }
                    ?.substringAfter("=")?.trim()?.toLongOrNull()
                    ?: throw IllegalStateException("Release 缺少 versionCode")
                val installed = if (Build.VERSION.SDK_INT >= 33) {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode.toLong()
                }
                val url = apkUrl ?: throw IllegalStateException("Release 未提供 APK")
                val shaUrl = digestUrl ?: throw IllegalStateException("Release 未提供 SHA256")
                main.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    if (code <= installed) {
                        if (!silent) info(activity, "已是最新版本（$installed）")
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle("发现 Buick E5 更新")
                            .setMessage("当前版本：$installed\\n新版本：$code\\n频道：$tag\\n是否下载并安装？")
                            .setNegativeButton("取消", null)
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
            setTitle("下载 Buick E5 更新")
            setMessage("正在下载…")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }
        thread(name = "buick-update-download") {
            val target = File(activity.cacheDir, "updates/CarDeck-Buick-E5.apk")
            try {
                target.parentFile?.mkdirs()
                val shaConn = (URL(shaUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000; readTimeout = 12000
                }
                val expected = shaConn.inputStream.bufferedReader().use { it.readText().trim().split(" ")[0].lowercase() }
                shaConn.disconnect()
                require(expected.matches(Regex("[0-9a-f]{64}"))) { "SHA256 格式无效" }
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 30000
                }
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
                conn.disconnect()
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual == expected) { "SHA256 校验失败，已拒绝安装" }
                main.post {
                    dialog.dismiss()
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    if (!activity.packageManager.canRequestPackageInstalls()) {
                        info(activity, "请先允许 CarDeck 安装未知应用，然后重新点击检查更新。")
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")))
                    } else {
                        val uri = FileProvider.getUriForFile(activity,
                            "${activity.packageName}.updatefiles", target)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(intent)
                    }
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

    private fun info(activity: Activity, message: String) {
        AlertDialog.Builder(activity).setTitle("CarDeck 在线更新")
            .setMessage(message).setPositiveButton("确定", null).show()
    }
}
