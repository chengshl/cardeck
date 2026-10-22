package io.github.shaonialife.cardeck.gateway

import android.content.Context
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
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
            arrayOf("logcat", "-d", "-t", "120", "-b", "main", "-b", "system", "-v", "brief", "*:W"), null, null)
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
                            if (lines.size == 64) lines.removeFirst()
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
}
