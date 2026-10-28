package io.github.shaonialife.cardeck

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.github.shaonialife.cardeck.gateway.RescueTraceStore
import io.github.shaonialife.cardeck.service.RescueCaptureHealth
import io.github.shaonialife.cardeck.service.RescueTraceService
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import io.github.shaonialife.cardeck.update.CollectorUpdater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collector-only dashboard. No CarPlay, Bluetooth, virtual display or Gateway action.
 * User explicitly starts/stops the local foreground service.
 */
class ForensicOnlyActivity : AppCompatActivity() {
    private val bg = Color.rgb(10, 16, 29)
    private val surface = Color.rgb(21, 31, 49)
    private val outline = Color.rgb(43, 59, 80)
    private val accent = Color.rgb(93, 227, 194)
    private val white = Color.rgb(241, 247, 252)
    private val muted = Color.rgb(156, 174, 197)
    private val amber = Color.rgb(255, 197, 103)
    private val danger = Color.rgb(255, 127, 135)

    private lateinit var status: TextView
    private lateinit var subtitle: TextView
    private lateinit var shizuku: TextView
    private lateinit var notification: TextView
    private lateinit var heartbeat: TextView
    private lateinit var matched: TextView
    private lateinit var capacity: TextView
    private lateinit var progress: ProgressBar
    private lateinit var feedback: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var export: Button
    private val collector get() = RescueTraceStore.forApp(this)

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCapture()
        else {
            feedback.text = "通知未授权，尚未启动。请允许通知，以便观察持续采集状态。"
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 26.dp, 20.dp, 30.dp)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
            addView(root)
        }
        setContentView(scroll)

        root.addView(label("CARDECK  /  RESCUE", 12f, accent, bold = true))
        root.addView(gap(9))
        root.addView(label("独立取证助手", 29f, white, bold = true))
        root.addView(gap(6))
        root.addView(label("只读监测系统异常 · 本地脱敏保存 · 不连接车机", 13f, muted))
        root.addView(gap(22))

        val statusCard = panel()
        root.addView(statusCard)
        statusCard.addView(label("实时状态", 12f, muted, bold = true))
        statusCard.addView(gap(10))
        status = label("未采集", 23f, white, bold = true)
        statusCard.addView(status)
        statusCard.addView(gap(5))
        subtitle = label("检查 Shizuku 与通知权限后手动启动", 13f, muted)
        statusCard.addView(subtitle)
        statusCard.addView(gap(17))
        statusCard.addView(rule())
        statusCard.addView(gap(14))
        val permissionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusCard.addView(permissionRow)
        shizuku = label("Shizuku  ···", 13f, muted)
        notification = label("通知  ···", 13f, muted)
        permissionRow.addView(shizuku, LinearLayout.LayoutParams(0, -2, 1f))
        permissionRow.addView(notification, LinearLayout.LayoutParams(0, -2, 1f))
        statusCard.addView(gap(13))
        heartbeat = label("最近心跳  —", 13f, white)
        matched = label("最近异常记录  —", 13f, white)
        statusCard.addView(heartbeat)
        statusCard.addView(gap(8))
        statusCard.addView(matched)

        root.addView(gap(16))
        root.addView(label("采集控制", 16f, white, bold = true))
        root.addView(gap(10))
        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(controlRow)
        start = actionButton("开始采集", accent, bg) { requestStart() }
        stop = actionButton("停止采集", surface, white) {
            RescueTraceService.stop(this)
            feedback.text = "已发送停止请求；已写入的取证日志仍保留在本机。"
            refresh()
            status.postDelayed({ if (!isFinishing && !isDestroyed) refresh() }, 600L)
        }
        controlRow.addView(start, LinearLayout.LayoutParams(0, 52.dp, 1f))
        controlRow.addView(gap(10, horizontal = true))
        controlRow.addView(stop, LinearLayout.LayoutParams(0, 52.dp, 1f))
        root.addView(gap(12))

        val storageCard = panel()
        root.addView(storageCard)
        storageCard.addView(label("本地证据库", 16f, white, bold = true))
        storageCard.addView(gap(8))
        capacity = label("读取中…", 13f, muted)
        storageCard.addView(capacity)
        storageCard.addView(gap(10))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 2560
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(outline)
        }
        storageCard.addView(progress, LinearLayout.LayoutParams(-1, 7.dp))
        storageCard.addView(gap(14))
        export = actionButton("导出脱敏日志  ↗", outline, white) { exportLogs() }
        storageCard.addView(export, LinearLayout.LayoutParams(-1, 50.dp))
        storageCard.addView(gap(8))
        storageCard.addView(label("保存到 下载/CarDeck · 手动导出，不会自动上传", 12f, muted))
        root.addView(gap(12))

        feedback = label("提示：系统休眠可能延迟心跳；心跳不代表全部 Logcat 事件已保存。", 12f, muted)
        feedback.setPadding(5.dp, 4.dp, 5.dp, 5.dp)
        root.addView(feedback)
        root.addView(gap(21))

        val updateCard = panel()
        root.addView(updateCard)
        updateCard.addView(label("应用更新", 16f, white, bold = true))
        updateCard.addView(gap(5))
        updateCard.addView(label("只检查本独立取证助手，不会更新 E5 / Test", 12f, muted))
        updateCard.addView(gap(8))
        val autoUpdate = CheckBox(this).apply {
            text = "打开页面时检查更新"
            textSize = 13f
            setTextColor(white)
            buttonTintList = ColorStateList.valueOf(accent)
            isChecked = CollectorUpdater.autoEnabled(this@ForensicOnlyActivity)
            setOnCheckedChangeListener { _, checked ->
                CollectorUpdater.setAutoEnabled(this@ForensicOnlyActivity, checked)
            }
        }
        updateCard.addView(autoUpdate)
        val updateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        updateCard.addView(updateRow)
        updateRow.addView(actionButton("检查更新", outline, white) {
            CollectorUpdater.check(this, silent = false)
        }, LinearLayout.LayoutParams(0, 45.dp, 1f))
        updateRow.addView(gap(10, horizontal = true))
        updateRow.addView(actionButton("安装已缓存版", outline, white) {
            CollectorUpdater.installCached(this)
        }, LinearLayout.LayoutParams(0, 45.dp, 1f))
        root.addView(gap(16))
        root.addView(label(
            "安全说明  ·  不自动启动、不限制4小时。划掉界面通常不影响前台服务；强制停止、系统杀进程或重启可能中断采集。"
                + "日志保留在应用私有目录；清除数据或卸载之前请先导出。",
            12f, muted,
        ))
    }

    private fun requestStart() {
        if (ShizukuGate.state(this) != ShizukuGate.State.READY) {
            feedback.text = "Shizuku 尚未就绪：先启动 Shizuku，并单独授权「CarDeck 取证助手」。"
            return
        }
        if (!notificationsAllowed() && Build.VERSION.SDK_INT >= 33) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startCapture()
    }

    private fun startCapture() {
        runCatching { RescueTraceService.start(this) }
            .onSuccess { feedback.text = "已请求启动；请查看常驻通知，稍后确认最近心跳。" }
            .onFailure { feedback.text = "启动失败：${it.message ?: "系统拒绝前台服务"}" }
        refresh()
        status.postDelayed({ if (!isFinishing && !isDestroyed) refresh() }, 600L)
    }

    private fun exportLogs() {
        export.isEnabled = false
        feedback.text = "正在导出已保存的脱敏记录…"
        val app = applicationContext
        Thread({
            val saved = runCatching { RescueTraceStore.export(app) }.getOrNull()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    export.isEnabled = true
                    feedback.text = if (saved == null) {
                        "没有可导出的记录，或导出失败。请检查本机存储。"
                    } else {
                        "导出成功：${saved.fileName}（${saved.bytes / 1024} KiB）\n${saved.displayPath}"
                    }
                    refresh()
                }
            }
        }, "forensic-export").start()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
        if (CollectorUpdater.autoEnabled(this)) CollectorUpdater.autoCheck(this)
    }

    private fun refresh() {
        val health = RescueCaptureHealth.snapshot(this)
        val running = RescueTraceService.running
        val now = System.currentTimeMillis()
        val ageMin = if (health.lastHeartbeatAt > 0L) {
            (now - health.lastHeartbeatAt).coerceAtLeast(0L) / 60_000L
        } else null
        status.text = when {
            running && ageMin != null && ageMin > 20L -> "服务存活 · 心跳延迟"
            running -> "采集服务运行中"
            health.lastStopReason.isNotBlank() -> "采集已停止"
            health.startedAt > 0L -> "未检测到采集服务"
            else -> "尚未开始采集"
        }
        status.setTextColor(if (running && (ageMin == null || ageMin <= 20L)) accent else amber)
        subtitle.text = when {
            running && ageMin != null && ageMin > 20L ->
                "前台服务仍在，但心跳已超过20分钟；可能是休眠，请导出核对"
            running -> "前台服务存活；不能单凭通知证明 Logcat 读取正常"
            health.lastStopReason.isNotBlank() -> health.lastStopReason
            health.startedAt > 0L -> "上次运行状态未能确认；需要时可手动重新启动"
            else -> "手动开始后，将在本机轮转保留系统异常"
        }
        val ready = ShizukuGate.state(this) == ShizukuGate.State.READY
        shizuku.text = if (ready) "● Shizuku 已授权" else "● Shizuku 未就绪"
        shizuku.setTextColor(if (ready) accent else amber)
        val notices = notificationsAllowed()
        notification.text = if (notices) "● 通知已允许" else "● 通知未授权"
        notification.setTextColor(if (notices) accent else danger)
        heartbeat.text = "最近心跳  ${formatTime(health.lastHeartbeatAt)}" +
            if (health.previousGapMinutes >= 15L) "  · 上次间隔${health.previousGapMinutes}分钟" else ""
        matched.text = "最近匹配的系统记录  ${formatTime(health.lastMatchedLogAt)}"
        val kb = collector.retainedBytes() / 1024L
        capacity.text = "普通 2 MiB ＋ 关键 512 KiB  ·  已留存 ${kb} / 2560 KiB"
        progress.progress = kb.coerceIn(0L, 2560L).toInt()
        start.isEnabled = !running
        stop.isEnabled = running
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun formatTime(value: Long): String =
        if (value <= 0L) "尚无记录"
        else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18.dp, 18.dp, 18.dp, 18.dp)
        background = shape(surface, 20.dp, outline)
    }

    private fun label(value: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setLineSpacing(1.dp.toFloat(), 1.06f)
        }

    private fun actionButton(textValue: String, fill: Int, textColor: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = textValue
            isAllCaps = false
            textSize = 14f
            setTextColor(textColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            backgroundTintList = ColorStateList.valueOf(fill)
            background = shape(fill, 13.dp, if (fill == surface) outline else fill)
            setOnClickListener { onClick() }
        }

    private fun shape(color: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(1.dp, stroke)
        }

    private fun rule(): View = View(this).apply {
        setBackgroundColor(outline)
        layoutParams = LinearLayout.LayoutParams(-1, 1.dp)
    }

    private fun gap(value: Int, horizontal: Boolean = false): View = View(this).apply {
        layoutParams = if (horizontal) LinearLayout.LayoutParams(value.dp, 1)
        else LinearLayout.LayoutParams(1, value.dp)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
