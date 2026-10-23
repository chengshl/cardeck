package io.github.shaonialife.cardeck

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.shaonialife.cardeck.buick.BuickVehiclePrefs

/**
 * T39: focused, searchable per-app display settings instead of two dropdowns
 * and an extra save button on the Buick vehicle page.
 *
 * Uses the existing hashed per-car/per-app preferences. Does not change
 * the video compositor, virtual displays, or CarPlay connection.
 */
class BuickAppDisplayActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_VEHICLE_KEY = "vehicle_key"
    }

    private data class AppItem(val pkg: String, val title: String, val icon: Drawable?)

    private val fg by lazy { getColor(R.color.m3_on_surface) }
    private val muted by lazy { getColor(R.color.m3_on_var) }
    private val accent by lazy { getColor(R.color.m3_primary) }
    private val base by lazy { getColor(R.color.m3_surface) }
    private val surface by lazy { getColor(R.color.m3_surface_low) }
    private val elevated by lazy { getColor(R.color.m3_surface_high) }
    private val stroke by lazy { getColor(R.color.m3_outline_var) }
    private val vehicleKey: String? by lazy { intent.getStringExtra(EXTRA_VEHICLE_KEY) }

    private val apps = mutableListOf<AppItem>()
    private lateinit var list: RecyclerView
    private lateinit var appAdapter: AppAdapter
    private lateinit var counter: TextView
    private lateinit var fullCard: LinearLayout
    private lateinit var safeCard: LinearLayout
    private lateinit var allFilter: TextView
    private lateinit var customFilter: TextView
    private var searchText = ""
    private var onlyCustom = false

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(fill: Int, radius: Int = 16, outline: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(fill)
            if (outline != null) setStroke(dp(1), outline)
        }

    private fun text(value: String, size: Float, color: Int = fg, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = true
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profile = vehicleKey?.let { BuickVehiclePrefs.get(this, it) }
        if (profile == null) {
            finish()
            return
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(base)
        }
        setContentView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        root.addView(header)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(fg)
            gravity = Gravity.CENTER
            contentDescription = "返回别克设置"
            background = shape(surface, 14)
            setOnClickListener { finish() }
        }
        topBar.addView(back, LinearLayout.LayoutParams(dp(42), dp(42)))
        val title = text("应用显示管理", 22f, fg, true)
        topBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(14)
        })
        header.addView(topBar)

        val car = text("当前车辆  ·  ${profile.label}", 12f, muted)
        car.setPadding(dp(2), dp(12), dp(2), dp(8))
        header.addView(car)

        if (!BuickVehiclePrefs.enabled(this)) {
            val disabledNote = text("安全区总开关目前关闭；这里的选择会保存，开启后生效。", 12f, getColor(R.color.m3_amber))
            disabledNote.setPadding(dp(12), dp(10), dp(12), dp(10))
            disabledNote.background = shape(elevated, 12)
            header.addView(disabledNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }

        header.addView(text("默认显示方式", 16f, fg, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(12)
        })
        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fullCard = defaultCard("全铺满", "沉浸显示 · 适合视频", android.R.drawable.ic_menu_view, "full")
        safeCard = defaultCard("安全区域", "完整内容 · 适合地图", android.R.drawable.ic_menu_crop, "safe")
        choices.addView(fullCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        choices.addView(safeCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        header.addView(choices)
        header.addView(text("只改变单独打开的 App / 全屏地图，不影响桌面和 Dock。", 12f, muted).apply {
            setPadding(0, dp(10), 0, dp(8))
        })
        refreshDefaultCards()

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(10))
        }
        section.addView(text("为 App 单独指定", 16f, fg, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        counter = text("", 12f, muted)
        section.addView(counter)
        header.addView(section)

        val searchBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(10), 0)
            background = shape(elevated, 14)
        }
        val searchIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            imageTintList = ColorStateList.valueOf(muted)
            contentDescription = null
        }
        searchBox.addView(searchIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
        val search = EditText(this).apply {
            hint = "搜索应用名称"
            setSingleLine(true)
            textSize = 14f
            setTextColor(fg)
            setHintTextColor(muted)
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(dp(10), dp(10), dp(8), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchText = s?.toString().orEmpty()
                    if (::appAdapter.isInitialized) refreshList()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        searchBox.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(searchBox)

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(6))
        }
        allFilter = filter("全部应用") {
            onlyCustom = false
            refreshList()
        }
        customFilter = filter("已自定义") {
            onlyCustom = true
            refreshList()
        }
        filters.addView(allFilter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(8) })
        filters.addView(customFilter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
        header.addView(filters)

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BuickAppDisplayActivity)
            setPadding(dp(16), dp(4), dp(16), dp(24))
            clipToPadding = false
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        apps.addAll(loadApps())
        appAdapter = AppAdapter()
        list.adapter = appAdapter
        refreshList()
    }

    private fun defaultCard(title: String, description: String, iconRes: Int, mode: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(15), dp(10), dp(15))
            minimumHeight = dp(104)
            isClickable = true
            isFocusable = true
            contentDescription = "$title：$description"
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(accent)
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(23), dp(23)))
        card.addView(text(title, 15f, fg, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        card.addView(text(description, 11f, muted), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        })
        card.setOnClickListener {
            val key = vehicleKey ?: return@setOnClickListener
            BuickVehiclePrefs.setStandaloneMode(this, key, mode)
            refreshDefaultCards()
            refreshList()
        }
        return card
    }

    private fun refreshDefaultCards() {
        val mode = vehicleKey?.let { BuickVehiclePrefs.get(this, it)?.standaloneMode } ?: "full"
        fullCard.background = shape(if (mode == "full") getColor(R.color.m3_pri_container) else surface, 16,
            if (mode == "full") accent else stroke)
        safeCard.background = shape(if (mode == "safe") getColor(R.color.m3_pri_container) else surface, 16,
            if (mode == "safe") accent else stroke)
        fullCard.isSelected = mode == "full"
        safeCard.isSelected = mode == "safe"
    }

    private fun filter(label: String, click: () -> Unit): TextView = text(label, 12f, fg, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
    }

    private fun refreshList() {
        if (!::appAdapter.isInitialized) return
        allFilter.background = shape(if (!onlyCustom) getColor(R.color.m3_pri_container) else surface, 16,
            if (!onlyCustom) accent else null)
        customFilter.background = shape(if (onlyCustom) getColor(R.color.m3_pri_container) else surface, 16,
            if (onlyCustom) accent else null)
        val key = vehicleKey ?: return
        val q = searchText.trim()
        val matches = apps.filter {
            (q.isBlank() || it.title.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true)) &&
                (!onlyCustom || BuickVehiclePrefs.appOverride(this, key, it.pkg) != null)
        }.sortedWith(compareByDescending<AppItem> {
            BuickVehiclePrefs.appOverride(this, key, it.pkg) != null
        }.thenBy { it.title.lowercase() })
        appAdapter.update(matches)
        counter.text = "${matches.size} 个应用"
    }

    private fun loadApps(): List<AppItem> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                AppItem(
                    pkg,
                    info.loadLabel(packageManager)?.toString()?.ifBlank { pkg } ?: pkg,
                    runCatching { info.loadIcon(packageManager) }.getOrNull(),
                )
            }.distinctBy { it.pkg }
            .sortedBy { it.title.lowercase() }
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.Holder>() {
        private val visible = mutableListOf<AppItem>()

        inner class Holder(view: View, val icon: ImageView, val title: TextView,
            val description: TextView, val badge: TextView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@BuickAppDisplayActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(12), dp(13))
                background = shape(surface, 16, stroke)
                isClickable = true
                isFocusable = true
            }
            val icon = ImageView(this@BuickAppDisplayActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = null
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
            val nameCol = LinearLayout(this@BuickAppDisplayActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            val title = text("", 14f, fg, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val sub = text("", 12f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            nameCol.addView(title)
            nameCol.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
            })
            row.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val badge = text("", 11f, fg, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(7), dp(10), dp(7))
            }
            row.addView(badge)
            return Holder(row, icon, title, sub, badge)
        }

        override fun getItemCount() = visible.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = visible[position]
            val key = vehicleKey ?: return
            val override = BuickVehiclePrefs.appOverride(this@BuickAppDisplayActivity, key, app.pkg)
            val inherited = BuickVehiclePrefs.get(this@BuickAppDisplayActivity, key)?.standaloneMode ?: "full"
            val effective = override ?: inherited
            holder.icon.setImageDrawable(app.icon ?: getDrawable(android.R.drawable.sym_def_app_icon))
            holder.title.text = app.title
            holder.description.text = if (override == null) "跟随车辆默认" else "此应用单独配置"
            holder.badge.text = if (effective == "safe") "安全区域" else "全铺满"
            holder.badge.background = shape(
                if (override != null) getColor(R.color.m3_pri_container) else elevated,
                12, if (override != null) accent else null,
            )
            holder.itemView.setOnClickListener { showAppModeDialog(app) }
            holder.itemView.contentDescription = "${app.title}，${holder.description.text}，${holder.badge.text}，点击修改"
            val params = holder.itemView.layoutParams as? RecyclerView.LayoutParams
                ?: RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(8)
            holder.itemView.layoutParams = params
        }

        fun update(next: List<AppItem>) {
            visible.clear()
            visible.addAll(next)
            notifyDataSetChanged()
        }
    }

    private fun showAppModeDialog(app: AppItem) {
        val key = vehicleKey ?: return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(elevated)
            setPadding(dp(20), dp(22), dp(20), dp(16))
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply { setImageDrawable(app.icon ?: getDrawable(android.R.drawable.sym_def_app_icon)) }
        heading.addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titles.addView(text(app.title, 18f, fg, true))
        titles.addView(text("选择单独应用的显示方式", 12f, muted))
        heading.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(heading)
        content.addView(text("选择后自动保存；重新打开应用生效", 12f, muted).apply {
            setPadding(0, dp(14), 0, dp(12))
        })

        val current = BuickVehiclePrefs.appOverride(this, key, app.pkg)
        val modes = listOf(
            Triple(null, "跟随车辆默认", "使用这辆车的默认显示方式"),
            Triple("safe", "安全区域", "完整显示，保持比例，四周留白"),
            Triple("full", "全铺满", "沉浸铺满，边角可能裁切"),
        )
        val dialog = AlertDialog.Builder(this).setView(content).create()
        for ((mode, label, explanation) in modes) {
            val selected = current == mode
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = shape(if (selected) getColor(R.color.m3_pri_container) else surface,
                    14, if (selected) accent else stroke)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    BuickVehiclePrefs.setAppOverride(this@BuickAppDisplayActivity, key, app.pkg, mode)
                    dialog.dismiss()
                    refreshList()
                    android.widget.Toast.makeText(
                        this@BuickAppDisplayActivity, "已保存 · 重新打开 ${app.title} 后生效",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(text(label, 15f, fg, true))
            body.addView(text(explanation, 12f, muted).apply {
                setPadding(0, dp(4), 0, 0)
            })
            row.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) row.addView(text("✓", 20f, accent, true))
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(shape(elevated, 22))
            dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(280)),
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }
}
