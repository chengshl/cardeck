package io.github.shaonialife.cardeck.gateway

import android.content.Context
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A one-shot, user-requested READ-ONLY reboot evidence snapshot.
 * Uses existing Shizuku; no root, service monitoring, permissions changes,
 * silent collection, boot hooks or modification of Android system state.
 *
 * Boot reason describes the CURRENT boot and may be "unknown". pstore can be
 * absent or inaccessible. Neither absence nor a vendor string proves a cause.
 */
internal object RebootEvidenceCapture {
    private const val MAX_SOURCE_CHARS = 20000

    fun collect(context: Context, redactor: LogRedactor): List<String> {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).format(Date())
        val evidence = ArrayList<String>()
        evidence += "[重启取证] 采集时间=$now；下列bootreason针对当前启动，不代表已确定根因"
        evidence += "[重启取证] 历史事件请查看DropBox原始事件时间；无记录不代表系统没有异常"
        if (ShizukuGate.state(context) != ShizukuGate.State.READY) {
            evidence += "[重启取证] Shizuku不可用，仅提供App操作轨迹与已经保留的DropBox摘要"
            return evidence
        }

        val commands = listOf(
            "ro.boot.bootreason" to "getprop ro.boot.bootreason",
            "sys.boot.reason" to "getprop sys.boot.reason",
            "persist.sys.boot.reason" to "getprop persist.sys.boot.reason",
            "ro.bootmode" to "getprop ro.bootmode",
            "uptime" to "cat /proc/uptime",
            "pstore.files" to "ls -l /sys/fs/pstore 2>&1 | head -c 4000",
            "pstore.preview" to "for f in /sys/fs/pstore/console-ramoops* /sys/fs/pstore/dmesg-ramoops*; do if [ -r \"\$f\" ]; then echo ====pstore====; tail -c 12000 \"\$f\"; fi; done",
            "last_kmsg" to "dumpsys dropbox --print SYSTEM_LAST_KMSG 2>/dev/null | tail -c 12000",
            "system_restart" to "dumpsys dropbox --print SYSTEM_RESTART 2>/dev/null | tail -c 12000",
            "system_watchdog" to "dumpsys dropbox --print system_server_watchdog 2>/dev/null | tail -c 12000",
        )

        for ((label, cmd) in commands) {
            if (ShizukuGate.state(context) != ShizukuGate.State.READY) {
                evidence += "[重启取证] Shizuku采集中断；其余条目未读取"
                break
            }
            // No shell interpolation of user input; commands are fixed literals.
            val result = runCatching { Shell.exec(cmd, timeoutMs = 4500L) }.getOrNull()
            if (result == null || !result.ok) {
                evidence += "[重启取证] $label 无法读取或设备不支持"
                continue
            }
            val raw = result.output.takeLast(MAX_SOURCE_CHARS).trim()
            if (raw.isBlank() || raw == "[]" || raw.contains("No entries found", ignoreCase = true)) {
                evidence += "[重启取证] $label 无可用记录（未据此断言无重启）"
                continue
            }
            evidence += "[重启取证] $label 有记录:"
            raw.lineSequence().take(120).forEach { line ->
                redactor.forensicChunks(line).forEach { chunk ->
                    if (evidence.size < 750) evidence += "[重启/$label] $chunk".take(256)
                }
            }
        }
        evidence += "[重启取证] 本模式不包含完整bugreport；若原因仍未知，建议通过ADB本地保存后人工脱敏分析"
        return evidence.take(760)
    }
}
