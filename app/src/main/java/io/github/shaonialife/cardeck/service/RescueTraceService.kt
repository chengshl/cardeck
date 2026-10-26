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
import io.github.shaonialife.cardeck.EntryConfigActivity
import io.github.shaonialife.cardeck.gateway.LogRedactor
import io.github.shaonialife.cardeck.gateway.RescueTracePolicy
import io.github.shaonialife.cardeck.gateway.RescueTraceStore
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
 * Stop manually, or automatically at four hours; Shizuku must be ready first.
 */
class RescueTraceService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var killReader: (() -> Unit)? = null
    private var started = false
    private val autoStop = Runnable {
        description = "已达4小时采集上限，文件已保留"
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            description = "已手动停止；文件仍保存在手机"
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "CarDeck 重启前日志留存", NotificationManager.IMPORTANCE_LOW,
        ))
        val open = PendingIntent.getActivity(
            this, 6, Intent(this, EntryConfigActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 7, Intent(this, RescueTraceService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CarDeck · 本地保留重启前日志")
            .setContentText("仅本地循环保存，最多2MB；点按可返回诊断页")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "停止采集", stop)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (!started) {
            started = true
            running = true
            description = "采集中 · 脱敏、仅保存本机、不上传"
            handler.postDelayed(autoStop, FOUR_HOURS_MS)
            executor.execute { record() }
        }
        return START_NOT_STICKY
    }

    private fun record() {
        val store = RescueTraceStore.forApp(this)
        val redactor = LogRedactor()
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
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
            store.append("[采集会话] 开始于 $stamp；只保留筛选/脱敏记录；最多4小时；重启后需重新手动启动")
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
                        if (!RescueTracePolicy.keep(raw)) continue
                        val important = raw.contains("RescueParty", true) ||
                            raw.contains("denied { add }", true) ||
                            raw.contains("failed to complete startup", true)
                        redactor.forensicChunks(raw).take(6).forEach { chunk ->
                            store.append(chunk, important)
                        }
                        accepted++
                        if (accepted % 50 == 0) description = "已保留关键事件 " +
                            accepted + " 条；本地约 " + (store.retainedBytes() / 1024) + "KB"
                    }
                }
            }
            if (!cancelled) description = "Shizuku/Logcat已结束；已留存文件可导出"
        } catch (error: Exception) {
            description = "采集停止：" + (error.message?.take(80) ?: "无法读取Shizuku日志")
        } finally {
            killReader?.invoke()
            killReader = null
            if (!cancelled) handler.post { stopSelf() }
        }
    }

    override fun onDestroy() {
        cancelled = true
        handler.removeCallbacks(autoStop)
        killReader?.invoke()
        executor.shutdownNow()
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cardeck_rescue_trace"
        private const val NOTIFICATION_ID = 36
        private const val STOP = "cardeck.rescue.STOP"
        private const val FOUR_HOURS_MS = 4L * 60 * 60 * 1000
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
        }
    }
}
