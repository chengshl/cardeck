package io.github.shaonialife.cardeck.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.shaonialife.cardeck.ForensicOnlyActivity
import io.github.shaonialife.cardeck.gateway.LogRedactor
import io.github.shaonialife.cardeck.gateway.RescueTracePolicy
import io.github.shaonialife.cardeck.gateway.RescueTraceStore
import io.github.shaonialife.cardeck.gateway.RescueTraceContext
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import moe.shizuku.server.IShizukuService
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * L3 opt-in foreground capture. Local-only and no auto-start after reboot.
 * An Android reboot terminates this reader but keeps app-private rotated files.
 * Runs until the user stops it, Shizuku disconnects, or Android terminates the process.
 * There is deliberately no application-defined timeout: failures may occur hours later.
 * This specialized local diagnostic monitor is an opt-in specialUse foreground service.
 */
class RescueTraceService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var killReader: (() -> Unit)? = null
    private var started = false
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running || cancelled) return
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
                val gap = RescueCaptureHealth.heartbeat(this@RescueTraceService)
                RescueTraceStore.forApp(this@RescueTraceService).append(
                    "[采集心跳] $stamp 监控进程存活；不表示系统无异常" +
                        if (gap >= 15L) "；调度间隔约${gap}分钟（可能休眠）" else "",
                )
            }
            handler.postDelayed(this, 10 * 60 * 1000L)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            description = "已手动停止；文件仍保存在手机"
            RescueCaptureHealth.stopped(this, description)
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "CarDeck 重启前日志留存", NotificationManager.IMPORTANCE_LOW,
        ))
        val open = PendingIntent.getActivity(
            this, 6, Intent(this, ForensicOnlyActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 7, Intent(this, RescueTraceService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("取证助手 · 本地保存系统异常")
            .setContentText("服务运行中 · 普通2MiB＋关键512KiB，本地保存")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "停止采集", stop)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (!started) {
            started = true
            running = true
            description = "采集中 · 不限时、仅本机、脱敏、不上传"
            RescueCaptureHealth.started(this)
            executor.execute { record() }
            handler.postDelayed(heartbeat, 10 * 60 * 1000L)
        }
        return START_NOT_STICKY
    }

    private fun record() {
        val store = RescueTraceStore.forApp(this)
        val redactor = LogRedactor()
        val context = RescueTraceContext()
        try {
            check(ShizukuGate.state(this) == ShizukuGate.State.READY) { "Shizuku未就绪" }
            val binder = rikka.shizuku.Shizuku.getBinder() ?: error("Shizuku已断开")
            val process = IShizukuService.Stub.asInterface(binder).newProcess(
                arrayOf(
                    "logcat", "-b", "main", "-b", "system", "-b", "crash",
                    "-v", "threadtime", "-v", "year", "-T", "1",
                    "*:W", "RescueParty:I", "PackageWatchdog:I",
                    "SettingsProvider:I", "ServiceManager:I",
                ),
                null, null,
            )
            killReader = { runCatching { process.destroy() } }
            // Stop can win the race against Shizuku's process creation.
            if (cancelled) {
                runCatching { process.destroy() }
                return
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
            val pkg = packageManager.getPackageInfo(packageName, 0)
            val origin = getString(R.string.cardeck_installed_build_origin)
            store.append("[采集会话] $stamp 包名=$packageName 版本=${pkg.versionName} 来源=$origin；无限时；重启后手动恢复")
            store.append("[取证规则] 普通2MiB循环＋关键512KiB；SELinux上下文保留；关键事件前4后12行")
            // Drain stderr in a separate daemon; never let the pipe block logcat.
            Thread({
                runCatching {
                    process.errorStream.use { fd ->
                        FileInputStream(fd.fileDescriptor).use { stream ->
                            val buf = ByteArray(2048)
                            while (!cancelled && stream.read(buf) >= 0) Unit
                        }
                    }
                }
            }, "rescue-logcat-stderr").apply { isDaemon = true }.start()
            process.inputStream.use { fd ->
                FileInputStream(fd.fileDescriptor).bufferedReader().use { reader ->
                    var accepted = 0
                    for (raw in reader.lineSequence()) {
                        if (cancelled) break
                        if (raw.isBlank() || raw.length > 8192 || raw.startsWith("---------")) continue
                        // All queued context is redacted in memory BEFORE storage.
                        val safe = redactor.forensicChunks(raw).take(42)
                        val entries = context.accept(raw, safe)
                        for ((entry, vital) in entries) store.append(entry, vital)
                        if (entries.isNotEmpty()) RescueCaptureHealth.matchedLog(this)
                        accepted += entries.size
                        if (accepted > 0 && accepted % 50 == 0) {
                            description = "已保留取证分段 $accepted；本机约 ${store.retainedBytes() / 1024} KB"
                        }
                    }
                }
            }
            if (!cancelled) {
                description = "Shizuku/Logcat已结束；已留存文件可导出"
                RescueCaptureHealth.stopped(this, description)
            }
        } catch (error: Exception) {
            description = "采集停止：" + (error.message?.take(80) ?: "无法读取Shizuku日志")
            RescueCaptureHealth.stopped(this, description)
        } finally {
            killReader?.invoke()
            killReader = null
            if (!cancelled) handler.post { stopSelf() }
        }
    }

    override fun onDestroy() {
        cancelled = true
        handler.removeCallbacks(heartbeat)
        killReader?.invoke()
        executor.shutdownNow()
        running = false
        if (RescueCaptureHealth.snapshot(this).lastStopReason.isBlank()) {
            RescueCaptureHealth.stopped(this, "采集服务已退出；请确认Shizuku状态")
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cardeck_rescue_trace"
        private const val NOTIFICATION_ID = 36
        private const val STOP = "cardeck.rescue.STOP"
        @Volatile var running = false
            private set
        @Volatile var description: String = "未运行 · 已保存文件仍可导出"
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RescueTraceService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, RescueTraceService::class.java))
            description = "已停止 · 已保留日志可导出"
            RescueCaptureHealth.stopped(context, description)
        }
    }
}
