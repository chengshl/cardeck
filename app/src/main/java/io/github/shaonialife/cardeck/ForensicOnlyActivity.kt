package io.github.shaonialife.cardeck

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.shaonialife.cardeck.gateway.RescueTraceStore
import io.github.shaonialife.cardeck.service.RescueTraceService
import io.github.shaonialife.cardeck.shizuku.ShizukuGate

/**
 * Collector-only launcher. Deliberately never touches SessionController,
 * CarPlayService, Bluetooth, virtual display, Gateway, or updater.
 */
class ForensicOnlyActivity : AppCompatActivity() {
    private lateinit var current: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var export: Button
    private val collector get() = RescueTraceStore.forApp(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 28.dp, 24.dp, 30.dp)
        }
        fun heading(message: String, size: Float) = TextView(this).apply {
            text = message
            textSize = size
            setPadding(0, 0, 0, 18.dp)
            holder.addView(this)
        }
        heading("CarDeck · 独立系统取证", 23f)
        heading("专职读取 Sony / Android 系统异常；不连接 CarPlay，不执行蓝牙、Wi-Fi 或投屏操作。\n"
            + "必须由你主动开始，记录脱敏后保存在本应用，最多 2 MB 滚动覆盖；不自动上传。", 14f)
        current = TextView(this).apply {
            textSize = 14f
            setPadding(0, 10.dp, 0, 12.dp)
            holder.addView(this)
        }
        fun button(title: String, act: () -> Unit): Button = Button(this).apply {
            text = title
            isAllCaps = false
            holder.addView(this)
            setOnClickListener { act() }
        }
        start = button("开始持续留存") {
            if (ShizukuGate.state(this) != ShizukuGate.State.READY) {
                current.text = "Shizuku未就绪：请先启动Shizuku并为【CarDeck 取证助手】单独授权。"
            } else {
                runCatching { RescueTraceService.start(this) }.onSuccess {
                    current.text = "已请求启动。确认通知栏出现「本地保存系统异常」常驻通知。"
                }.onFailure { error ->
                    current.text = "启动失败：${error.message ?: "系统拒绝前台服务"}"
                }
            }
        }
        stop = button("手动停止采集") {
            RescueTraceService.stop(this)
            refresh()
        }
        export = button("导出已留存日志到下载/CarDeck") {
            export.isEnabled = false
            current.text = "正在导出已保存的脱敏记录…"
            val app = applicationContext
            Thread({
                val saved = runCatching { RescueTraceStore.export(app) }.getOrNull()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        export.isEnabled = true
                        current.text = if (saved == null)
                            "没有可导出的记录，或保存失败；先开始采集。"
                        else "已导出：${saved.fileName}（${saved.bytes} bytes）\n${saved.displayPath}"
                        // Keep the exact generated path visible without auto-sharing it.
                    }
                }
            }, "forensic-export").start()
        }
        heading("注意：不设4小时自动停止，但系统仍可能终止进程；重启后Shizuku通常需要重新启动。\n"
            + "已经写入的日志重启后仍保留；卸载此App或清除数据之前务必先导出。"
            + "\n如果原来安装过Buick-Test，本版将使用同一个Test包名覆盖安装；不影响E5包。", 13f)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(holder)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!::current.isInitialized) return
        val ready = ShizukuGate.state(this)
        val sizeKb = collector.retainedBytes() / 1024
        current.text = buildString {
            appendLine("Shizuku：${ready.name}")
            appendLine("采集：${RescueTraceService.description}")
            appendLine("当前留存：${sizeKb} KB / 2048 KB")
            append("本机连续取证，不限制4小时，不自动上传")
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
