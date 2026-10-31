package io.github.shaonialife.cardeck

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.shaonialife.cardeck.airplay.B233UdpBindPreflight
import io.github.shaonialife.cardeck.airplay.AudioReverseControlPort
import java.util.concurrent.atomic.AtomicBoolean
import io.github.shaonialife.cardeck.update.BuickUpdater

/** Buick-only menu. Keep diagnostics and updates out of upstream SettingsMenus. */
class BuickMenuActivity : AppCompatActivity() {
    companion object { private val preflightRunning = AtomicBoolean(false) }
    @Volatile private var preflightCancelled = false

    override fun onDestroy() {
        preflightCancelled = true
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_buick_menu)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<RecyclerView>(R.id.menuList).apply {
            layoutManager = LinearLayoutManager(this@BuickMenuActivity)
            adapter = BuickMenuAdapter()
        }
        if (BuickUpdater.autoEnabled(this)) BuickUpdater.checkSelected(this, silent = true)
    }

    private inner class BuickMenuAdapter : RecyclerView.Adapter<MenuHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuHolder =
            MenuHolder(layoutInflater.inflate(R.layout.item_phone_settings_menu, parent, false))

        override fun getItemCount() = if (packageName == "io.github.buick.test.cardeck") 4 else 3

        override fun onBindViewHolder(holder: MenuHolder, position: Int) {
            holder.itemView.setOnLongClickListener(null)
            when (position) {
                0 -> {
                    holder.icon.setImageResource(android.R.drawable.ic_menu_manage)
                    holder.title.text = "显示与安全区"
                    holder.desc.text = "曲面屏适配 · 车辆方案 · 应用显示"
                    holder.itemView.setOnClickListener {
                        startActivity(Intent(this@BuickMenuActivity, BuickSettingsActivity::class.java))
                    }
                }
                1 -> {
                    holder.icon.setImageResource(android.R.drawable.ic_menu_send)
                    holder.title.text = "日志管理"
                    holder.desc.text = "本地保存 · 可选在线上传 · 崩溃取证"
                    holder.itemView.setOnClickListener {
                        startActivity(Intent(this@BuickMenuActivity, EntryConfigActivity::class.java))
                    }
                }
                2 -> {
                    holder.icon.setImageResource(android.R.drawable.stat_sys_download)
                    holder.title.text = "软件更新"
                    holder.desc.text = "正式 / 试验分支 · 自动检查 · 构建时间"
                    holder.itemView.setOnClickListener {
                        startActivity(Intent(this@BuickMenuActivity, UpdateCenterActivity::class.java))
                    }
                }
                3 -> {
                    holder.icon.setImageResource(android.R.drawable.ic_menu_info_details)
                    holder.title.text = "T89 网络预检（仅音频关闭）"
                    holder.desc.text = "同 App UID / 车机 Wi-Fi / 当前 VPN · 25秒 UDP 监听"
                    holder.itemView.setOnClickListener { runT89BindPreflight() }
                }
            }
        }
    }

    /**
     * Manual B233 audio-free preflight. Uses SessionController's existing
     * vehicle Network, which the production AudioSender also uses. Never
     * requests a new Network, changes VPN, starts audio, or sends UDP.
     */
    private fun runT89BindPreflight() {
        val network = SessionController.b233NetworkForAudioFreePreflight()
        if (network == null) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("T89 前置条件未满足")
                .setMessage(
                    "先在 Test App 中关闭本轮 CarPlay 音频，连接别克 B233 视频会话后再打开本页。" +
                        " 必须由当前 App 持有车机 Wi-Fi Network；不会使用默认 VPN 或另一网络代替。"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        if (preflightRunning.get()) {
            android.widget.Toast.makeText(this, "25秒预检正在执行", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("T89 · 25秒同 UID 网络预检")
            .setMessage(
                "请确认车辆已停稳。只启动手机端接收型 UDP 监听；不发送任何 UDP 包、不启动音频、不修改 VPN 或 Wi-Fi。" +
                    "需保持现有 B233 视频会话和网络连接。完成后会写入脱敏日志。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("开始") { _, _ ->
                if (preflightCancelled || SessionController.b233NetworkForAudioFreePreflight() != network) {
                    android.widget.Toast.makeText(this, "未执行：纯视频会话或车机 Wi-Fi 已变化", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!preflightRunning.compareAndSet(false, true)) return@setPositiveButton
                SessionController.appendLog("AUDIO T89 PRECHECK: started durationMs=25000 mode=receive_only")
                Thread({
                    try {
                        val outcome = B233UdpBindPreflight(
                            bindNetwork = { udp -> network.bindSocket(udp) },
                            networkStillValid = {
                                !preflightCancelled &&
                                    SessionController.b233NetworkForAudioFreePreflight() == network
                            },
                            onBindError = AudioReverseControlPort::bindFailureCategory,
                            log = SessionController::appendLog,
                        ).run()
                        val summary =
                            "result=${outcome.status} errno=${outcome.errno} " +
                                "heldMs=${outcome.heldMs} bound=${outcome.wasListening} " +
                                "aliveAtEnd=${outcome.listenerAliveAtEnd}"
                        SessionController.appendLog("AUDIO T89 PRECHECK: $summary")
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                androidx.appcompat.app.AlertDialog.Builder(this)
                                    .setTitle(if (outcome.passed && outcome.heldMs >= 25_000L)
                                        "T89 绑定预检通过" else "T89 绑定预检未通过")
                                    .setMessage(
                                        "$summary\n\n结果仅验证本 App 的网络绑定与本地监听，" +
                                            "不代表 T89 音频已通过实车验证。"
                                    )
                                    .setPositiveButton("完成", null)
                                    .show()
                            }
                        }
                    } catch (error: Exception) {
                        SessionController.appendLog(
                            "AUDIO T89 PRECHECK: failed error=${error.javaClass.simpleName}"
                        )
                    } finally {
                        preflightRunning.set(false)
                    }
                }, "t89-wifi-udp-preflight").apply { isDaemon = true; start() }
            }
            .show()
    }

    private class MenuHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.menuIcon)
        val title: TextView = view.findViewById(R.id.menuTitle)
        val desc: TextView = view.findViewById(R.id.menuDesc)
    }
}
