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
import io.github.shaonialife.cardeck.update.BuickUpdater

/** Buick-only menu. Keep diagnostics and updates out of upstream SettingsMenus. */
class BuickMenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_buick_menu)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<RecyclerView>(R.id.menuList).apply {
            layoutManager = LinearLayoutManager(this@BuickMenuActivity)
            adapter = BuickMenuAdapter()
        }
    }

    private inner class BuickMenuAdapter : RecyclerView.Adapter<MenuHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuHolder =
            MenuHolder(layoutInflater.inflate(R.layout.item_phone_settings_menu, parent, false))

        override fun getItemCount() = 3

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
                    holder.desc.text = "正式版启动时检查 · 实验版手动检查 · 构建时间"
                    holder.itemView.setOnClickListener {
                        startActivity(Intent(this@BuickMenuActivity, UpdateCenterActivity::class.java))
                    }
                }
            }
        }
    }

    private class MenuHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.menuIcon)
        val title: TextView = view.findViewById(R.id.menuTitle)
        val desc: TextView = view.findViewById(R.id.menuDesc)
    }
}
