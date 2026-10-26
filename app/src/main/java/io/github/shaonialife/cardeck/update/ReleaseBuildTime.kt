package io.github.shaonialife.cardeck.update

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 安装包时间：优先使用CI在APK构建后写入Release的builtAt，不用提交时间代替。 */
internal object ReleaseBuildTime {
    private val beijing = TimeZone.getTimeZone("Asia/Shanghai")
    private val utc = TimeZone.getTimeZone("UTC")

    fun metadata(body: String, key: String): String? =
        body.lineSequence().firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }

    fun label(body: String, versionName: String): String {
        val builtAt = metadata(body, "builtAt")
        if (builtAt != null) {
            val parsed = runCatching {
                val source = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                source.timeZone = utc
                source.isLenient = false
                source.parse(builtAt)
            }.getOrNull()
            if (parsed != null) return "构建完成：${format(parsed)}（北京时间）"
        }
        // 老构建的14位版本号由构建工作流生成，代表版本时间而不一定是APK完成时刻。
        if (versionName.matches(Regex("[0-9]{14}"))) {
            val parsed = runCatching {
                val source = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                source.timeZone = beijing
                source.isLenient = false
                source.parse(versionName)
            }.getOrNull()
            if (parsed != null) return "版本时间：${format(parsed)}（北京时间；未提供构建完成时间）"
        }
        return "构建时间：尚未提供"
    }

    private fun format(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = beijing }.format(date)
}
