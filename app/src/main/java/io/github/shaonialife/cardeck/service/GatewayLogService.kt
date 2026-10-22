package io.github.shaonialife.cardeck.service

import android.app.Activity
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.shaonialife.cardeck.EntryConfigActivity
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.config.EntryConfig
import io.github.shaonialife.cardeck.config.EntryConfigStore
import io.github.shaonialife.cardeck.gateway.GatewayDeviceInfo
import io.github.shaonialife.cardeck.gateway.GatewayIdentity
import io.github.shaonialife.cardeck.gateway.GatewayVehicleIdentity
import io.github.shaonialife.cardeck.gateway.GatewayLogBuffer
import io.github.shaonialife.cardeck.gateway.GatewayLogRuntime
import io.github.shaonialife.cardeck.gateway.GatewayNetworkRoute
import io.github.shaonialife.cardeck.gateway.GatewayProbe
import io.github.shaonialife.cardeck.gateway.LogRedactor
import io.github.shaonialife.cardeck.util.DebugFileLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture

/** User-controlled app-log stream. Never starts a logcat reader or grants system-log consent. */
class GatewayLogService : Service() {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    private var started = false
    private var route: GatewayNetworkRoute? = null
    private var subscription: AutoCloseable? = null
    private var buffer: GatewayLogBuffer? = null
    private var retries = 0
    private var connection: GatewayProbe? = null
    private var retryTask: ScheduledFuture<*>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            remember(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.gateway_log_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, EntryConfigActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, GatewayLogService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.gateway_log_channel)).setContentText(getString(R.string.gateway_log_notification))
            .setContentIntent(open).setOngoing(true).addAction(0, getString(R.string.gateway_log_stop), stop).build()
        ServiceCompat.startForeground(this, 32, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        GatewayLogRuntime.running = true
        if (!started) {
            started = true
            worker.execute {
                val config = runCatching { EntryConfigStore.load(this) }.getOrNull()
                val info = runCatching { GatewayIdentity.load(this) }.getOrNull()
                if (destroyed) return@execute
                if (config == null || info == null) {
                    remember(this, false)
                    GatewayLogRuntime.publish(GatewayProbe.Snapshot(GatewayProbe.State.FAILED, GatewayProbe.Failure.CONFIGURATION))
                    main.post { stopSelf() }
                    return@execute
                }
                val queue = GatewayLogBuffer(LogRedactor(listOf(config.token)), vehicleProvider = GatewayVehicleIdentity::current)
                buffer = queue
                subscription = DebugFileLog.subscribe(queue::offer)
                queue.offer("CarDeck 软件日志实时上报已启动")
                connect(config, info, queue)
            }
        }
        return START_NOT_STICKY
    }

    private fun connect(config: EntryConfig, info: GatewayDeviceInfo, queue: GatewayLogBuffer) {
        if (destroyed) return
        try {
            val selected = GatewayNetworkRoute.create(this)
            route = selected
            lateinit var attempt: GatewayProbe
            attempt = GatewayProbe(selected.client, observer = { state ->
                if (!destroyed) {
                    GatewayLogRuntime.publish(state)
                    if (state.state == GatewayProbe.State.HEALTHY) retries = 0
                    if (state.state in setOf(GatewayProbe.State.FAILED, GatewayProbe.State.AUTH_FAILED, GatewayProbe.State.DISCONNECTED)) {
                        runCatching { worker.execute {
                            if (connection === attempt) {
                                connection = null
                                if (GatewayLogRuntime.probe === attempt) GatewayLogRuntime.probe = null
                                route?.close(); route = null
                                reconnect(config, info, queue)
                            }
                        } }
                    }
                }
            }, deviceInfo = info, appLogs = queue, vehicleProvider = GatewayVehicleIdentity::current)
            if (destroyed) { selected.close(); attempt.close(); return }
            connection = attempt
            GatewayLogRuntime.probe = attempt
            selected.watch { attempt.networkLost() }
            attempt.start(config)
        } catch (_: Exception) {
            val failed = connection
            connection = null
            if (GatewayLogRuntime.probe === failed) GatewayLogRuntime.probe = null
            failed?.close()
            route?.close(); route = null
            if (!destroyed) GatewayLogRuntime.publish(GatewayProbe.Snapshot(GatewayProbe.State.FAILED, GatewayProbe.Failure.NETWORK))
            reconnect(config, info, queue)
        }
    }

    private fun reconnect(config: EntryConfig, info: GatewayDeviceInfo, queue: GatewayLogBuffer) {
        if (destroyed) return
        retryTask?.cancel(false)
        val seconds = minOf(60L, 5L shl minOf(retries++, 4))
        retryTask = worker.schedule({ connect(config, info, queue) }, seconds, TimeUnit.SECONDS)
    }

    override fun onDestroy() {
        destroyed = true
        retryTask?.cancel(false)
        worker.execute {
            subscription?.close()
            val old = connection
            connection = null
            if (GatewayLogRuntime.probe === old) GatewayLogRuntime.probe = null
            old?.close()
            route?.close()
            buffer?.clear()
        }
        worker.shutdown()
        GatewayLogRuntime.running = false
        GatewayLogRuntime.publish(GatewayProbe.Snapshot(GatewayProbe.State.DISCONNECTED))
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "gateway_app_logs"
        private const val PREFS = "gateway-log-settings"
        private const val STOP = "cardeck.gateway.STOP_LOGS"
        private val settings = Executors.newSingleThreadExecutor()
        @Volatile private var desired: Boolean? = null

        @Synchronized
        private fun remember(context: Context, value: Boolean) {
            desired = value
            val app = context.applicationContext
            settings.execute { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", value).commit() }
        }

        fun start(context: Context) {
            remember(context, true)
            try { context.startForegroundService(Intent(context, GatewayLogService::class.java)) }
            catch (error: Exception) { remember(context, false); throw error }
        }

        fun stop(context: Context) {
            remember(context, false)
            context.stopService(Intent(context, GatewayLogService::class.java))
        }

        /** Resume only when an Activity is visible, respecting Android background-start rules. */
        fun resumeIfEnabled(activity: Activity) {
            settings.execute {
                synchronized(this) {
                    if (desired == null) desired = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
                }
                activity.runOnUiThread {
                    val resumed = (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
                    if (desired == true && resumed && !GatewayLogRuntime.running && !activity.isFinishing && !activity.isDestroyed) {
                        runCatching { activity.startForegroundService(Intent(activity, GatewayLogService::class.java)) }.onFailure {
                            GatewayLogRuntime.publish(GatewayProbe.Snapshot(GatewayProbe.State.FAILED, GatewayProbe.Failure.NETWORK))
                        }
                    }
                }
            }
        }
    }
}
