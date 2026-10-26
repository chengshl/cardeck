package io.github.shaonialife.cardeck.gateway

import android.content.Context
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import io.github.shaonialife.cardeck.gateway.CriticalEventTrail
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.util.DebugFileLog
import io.github.shaonialife.cardeck.util.Bg
import moe.shizuku.server.IShizukuService
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SystemLogCapture {
    /** Fixed finite read, Bg only. Never uploads, saves, grants permission or falls back to app-only logs. */
    fun preview(context: Context, redactor: LogRedactor): List<String> {
        check(ShizukuGate.state(context) == ShizukuGate.State.READY)
        val binder = rikka.shizuku.Shizuku.getBinder() ?: error("unavailable")
        val process = IShizukuService.Stub.asInterface(binder).newProcess(
            arrayOf("logcat", "-d", "-t", "320", "-b", "main", "-b", "system", "-v", "threadtime", "*:W"), null, null)
        val timer = Executors.newSingleThreadScheduledExecutor()
        val deadline = timer.schedule({ runCatching { process.destroy() } }, 8, TimeUnit.SECONDS)
        try {
            Bg.run("system-log-stderr") { runCatching { process.errorStream.use { FileInputStream(it.fileDescriptor).use { stream -> stream.read(ByteArray(4096)) } } } }
            val lines = ArrayDeque<String>()
            process.inputStream.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                    var read = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        read += line.length
                        if (read > 65536) break
                        if (line.isNotBlank() && !line.startsWith("---------")) {
                            if (lines.size == 256) lines.removeFirst()
                            lines.addLast(redactor.line(line))
                        }
                    }
                }
            }
            check(process.waitForTimeout(1000, TimeUnit.MILLISECONDS.toString()) && process.exitValue() == 0)
            return lines.toList()
        } finally {
            deadline.cancel(false)
            runCatching { process.destroy() }
            timer.shutdownNow()
        }
    }
    /**
     * On-demand detailed fault capture: no watcher and no implicit upload.
     * Only retain relevant system lines; a bounded pipe and deadline prevent
     * logcat from blocking CarPlay or loading an unbounded log buffer.
     * This is the newest available logcat slice, not a guaranteed ten-minute
     * recording (OEM buffers may already have rotated).
     */
    fun detailedPreview(context: Context, redactor: LogRedactor): List<String> {
        check(ShizukuGate.state(context) == ShizukuGate.State.READY)
        val binder = rikka.shizuku.Shizuku.getBinder() ?: error("unavailable")
        val process = IShizukuService.Stub.asInterface(binder).newProcess(
            arrayOf("logcat", "-d", "-t", "12000", "-b", "main", "-b", "system",
                "-v", "threadtime", "*:I"), null, null,
        )
        val relevant = Regex(
            "MediaCodec|CCodec|OMX|Surface|BufferQueue|DisplayManager|VirtualDisplay|" +
                "WindowManager|ActivityTaskManager|ActivityManager|AndroidRuntime|" +
                "Shizuku|Connectivity|NetworkMonitor|Wifi|Wi-Fi|Vpn|netd|" +
                "CarDeck|carplay|H264|codec|system_server|binder|Fatal|ANR|crash|SIGABRT",
            RegexOption.IGNORE_CASE,
        )
        val timer = Executors.newSingleThreadScheduledExecutor()
        val deadline = timer.schedule({ runCatching { process.destroy() } }, 12, TimeUnit.SECONDS)
        try {
            Bg.run("detailed-system-stderr") {
                runCatching {
                    process.errorStream.use { descriptor ->
                        FileInputStream(descriptor.fileDescriptor).use { stream ->
                            stream.read(ByteArray(4096))
                        }
                    }
                }
            }
            val lines = ArrayDeque<String>()
            var inputChars = 0
            var bounded = false
            process.inputStream.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        inputChars += line.length
                        if (inputChars > 2_000_000) {
                            bounded = true
                            break
                        }
                        if (relevant.containsMatchIn(line)) {
                            val redacted = redactor.line(line)
                            if (redacted.isNotBlank() &&
                                redacted != "[已隐藏含凭证或密钥的日志行]") {
                                if (lines.size == 2700) lines.removeFirst()
                                lines.addLast(redacted)
                            }
                        }
                    }
                }
            }
            if (!bounded) {
                check(process.waitForTimeout(1000, TimeUnit.MILLISECONDS.toString()) &&
                    process.exitValue() == 0)
            }
            if (bounded) lines.addLast("[采样状态] 系统日志达到2MB读取上限，最近记录可能不完整")
            return lines.toList()
        } finally {
            deadline.cancel(false)
            runCatching { process.destroy() }
            timer.shutdownNow()
        }
    }

    /**
     * T43: user-triggered post-reboot crash evidence preview. No upload and no
     * background collection. Uses the persistent app debug file plus two Android
     * boot evidence sources unavailable to the former 120-line warning sampler.
     *
     * logcat -L is only useful when the ROM preserves the last boot's buffers.
     * DropBox entries might persist longer, but OEM policy may hide/rotate them.
     * A reboot generally stops Shizuku; recover that first for system evidence.
     */
    fun recoveryPreview(context: Context, redactor: LogRedactor): List<String> {
        val previousApp = CrashEvidenceSelector.joinPriorSessions(DebugFileLog.readPriorSessions(context))
        val criticalTrail = CriticalEventTrail.recentLines(context)
        val ready = ShizukuGate.state(context) == ShizukuGate.State.READY
        if (!ready) {
            return CrashEvidenceSelector.select(previousApp, null, emptyMap(), false, redactor, criticalTrail = criticalTrail)
        }

        // Limit every shell command's stdout in the shell itself so even very
        // large DropBox reports cannot be materialized unbounded in app memory.
        // Reads run on the caller's Bg thread, never on the UI thread.
        fun read(cmd: String): String {
            val result = runCatching { Shell.exec(cmd, timeoutMs = 6500L) }.getOrNull()
            return if (result?.ok == true) result.output.takeLast(96000) else ""
        }
        val previousBoot = read("logcat -L -d -b crash -v threadtime -v year -t 300 2>/dev/null | tail -c 96000")
        // T51: current-boot crash buffer may contain a system-process crash while
        // the old DropBox server-crash record is unrelated and much older.
        val currentBoot = read("logcat -d -b crash -v threadtime -v year -t 250 2>/dev/null | tail -c 64000")
        val tags = listOf(
            "system_server_crash", "system_app_crash", "system_app_anr",
            "system_server_watchdog", "SYSTEM_RESTART", "SYSTEM_LAST_KMSG",
            "SYSTEM_TOMBSTONE", "system_app_native_crash",
        )
        val dropbox = linkedMapOf<String, String>()
        for (tag in tags) {
            if (ShizukuGate.state(context) != ShizukuGate.State.READY) break
            dropbox[tag] = read("dumpsys dropbox --print $tag 2>/dev/null | tail -c 96000")
        }
        return CrashEvidenceSelector.select(previousApp, previousBoot, dropbox, true, redactor, currentBoot, criticalTrail)
    }

}
