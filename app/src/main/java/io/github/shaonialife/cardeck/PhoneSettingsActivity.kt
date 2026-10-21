package io.github.shaonialife.cardeck

import android.content.Context
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
import io.github.shaonialife.cardeck.launcher.settings.SettingsMenus
import io.github.shaonialife.cardeck.launcher.settings.SettingsToastHost

/**
 * 手机侧「车机设置」二级菜单页（M3 暗色）：
 * 列出五个设置入口，点击进入内容 Fragment——与车机 SettingsActivity 双栏共用同一套面板。
 */
class PhoneSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_settings)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.menuList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = MenuAdapter()
        if (BuickUpdater.autoEnabled(this)) BuickUpdater.check(this, silent = true)
    }

    private inner class MenuAdapter : RecyclerView.Adapter<MenuHolder>() {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): MenuHolder {
            val view = layoutInflater.inflate(R.layout.item_phone_settings_menu, parent, false)
            return MenuHolder(view)
        }

        override fun getItemCount() = SettingsMenus.ALL.size + 2

        override fun onBindViewHolder(
            holder: MenuHolder,
            position: Int,
        ) {
            holder.itemView.setOnLongClickListener(null)
            if (position == SettingsMenus.ALL.size) {
                holder.icon.setImageResource(android.R.drawable.ic_menu_manage)
                holder.title.text = "入口配置"
                holder.desc.text = "设置服务地址和入口凭证"
                holder.itemView.setOnClickListener {
                    startActivity(Intent(this@PhoneSettingsActivity, EntryConfigActivity::class.java))
                }
                return
            }
            if (position == SettingsMenus.ALL.size + 1) {
                holder.icon.setImageResource(android.R.drawable.stat_sys_download)
                holder.title.text = "检查更新（Buick-Test）"
                holder.desc.text = "仅检查测试分支更新；长按切换自动检查"
                holder.itemView.setOnClickListener { BuickUpdater.check(this@PhoneSettingsActivity) }
                holder.itemView.setOnLongClickListener {
                    val enabled = !BuickUpdater.autoEnabled(this@PhoneSettingsActivity)
                    BuickUpdater.setAutoEnabled(this@PhoneSettingsActivity, enabled)
                    android.widget.Toast.makeText(this@PhoneSettingsActivity,
                        if (enabled) "自动检查更新已开启" else "自动检查更新已关闭",
                        android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
                return
            }
            val item = SettingsMenus.ALL[position]
            holder.icon.setImageResource(item.iconRes)
            holder.title.text = item.title
            holder.desc.text = item.desc
            holder.itemView.setOnClickListener {
                SettingsContentActivity.start(this@PhoneSettingsActivity, item)
            }
        }
    }

    private class MenuHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.menuIcon)
        val title: TextView = view.findViewById(R.id.menuTitle)
        val desc: TextView = view.findViewById(R.id.menuDesc)
    }
}

/**
 * 手机侧设置内容宿主：承载与车机双栏共用的内容 Fragment。
 */
class SettingsContentActivity :
    AppCompatActivity(),
    SettingsToastHost {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val menuId = intent.getIntExtra(EXTRA_MENU, SettingsMenus.DESKTOP)
        setContentView(R.layout.activity_settings_content)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.contentTitle).text = SettingsMenus.titleOf(menuId)

        if (supportFragmentManager.findFragmentById(R.id.settingsContainer) == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, SettingsMenus.createFragment(menuId))
                .commit()
        }
    }

    override fun showToast(text: String) {
        android.widget.Toast
            .makeText(this, text, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    companion object {
        private const val EXTRA_MENU = "menu"

        fun start(
            context: Context,
            item: SettingsMenus.Item,
        ) {
            context.startActivity(
                Intent(context, SettingsContentActivity::class.java)
                    .putExtra(EXTRA_MENU, item.id),
            )
        }
    }
}
