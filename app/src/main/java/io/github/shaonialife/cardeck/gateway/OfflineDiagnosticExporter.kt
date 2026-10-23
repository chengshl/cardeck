package io.github.shaonialife.cardeck.gateway

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.shaonialife.cardeck.util.DebugFileLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Offline-only manual export. Android 12+ Downloads/CarDeck, no Gateway or network. */
internal object OfflineDiagnosticExporter {
    enum class Kind(val tag: String, val title: String) {
        APP("app", "应用历史日志"), SYSTEM("system", "系统警告与错误"), RECOVERY("crash", "重启崩溃取证"),
    }
    data class Result(val uri: Uri, val fileName: String, val displayPath: String, val bytes: Int)

    fun exportApp(context: Context, redactor: LogRedactor): Result? {
        val raw = DebugFileLog.read(context, currentSessionOnly = false)?.takeIf { it.isNotBlank() } ?: return null
        return save(context, Kind.APP, OfflineLogFormatter.appLines(raw, redactor))
    }

    fun exportPreview(context: Context, kind: Kind, redactedLines: List<String>): Result? {
        if (kind == Kind.APP || redactedLines.isEmpty() || redactedLines.size > 256 ||
            redactedLines.any { it.isBlank() || it.length > 256 }) return null
        return save(context, kind, redactedLines)
    }

    private fun save(context: Context, kind: Kind, lines: List<String>): Result? {
        if (lines.isEmpty()) return null
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(now)
        val fileName = "CarDeck-${kind.tag}-$stamp.txt"
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/CarDeck"
        val content = OfflineLogFormatter.format(
            kind.title,
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }.getOrDefault("?"),
            "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}",
            lines,
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).format(now),
        ).toByteArray(Charsets.UTF_8)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(content) } ?: error("Missing output stream")
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0)
            Result(uri, fileName, "/storage/emulated/0/$relative/$fileName", content.size)
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
