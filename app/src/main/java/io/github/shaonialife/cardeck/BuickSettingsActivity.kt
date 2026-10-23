package io.github.shaonialife.cardeck

import android.os.Bundle
import android.content.res.ColorStateList
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.shaonialife.cardeck.buick.BuickInsets
import io.github.shaonialife.cardeck.buick.BuickVehiclePrefs

/** Settings are per locally identified receiver; never persist raw BT or /info device identifiers. */
class BuickSettingsActivity : AppCompatActivity() {
    private val profiles by lazy { BuickVehiclePrefs.all(this) }
    private var editingKey: String? = null
    private lateinit var details: TextView
    private lateinit var modes: RadioGroup
    private lateinit var marginInputs: List<EditText>
    private lateinit var standaloneModes: RadioGroup
    private val standaloneSafeChoiceId = View.generateViewId()
    private val standaloneFullChoiceId = View.generateViewId()
    private lateinit var appModes: Spinner
    private lateinit var appPicker: Spinner
    private val appChoices = mutableListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fg = getColor(R.color.m3_on_surface)
        val secondary = getColor(R.color.m3_on_var)
        val accent = getColor(R.color.m3_primary)
        val surfaceHigh = getColor(R.color.m3_surface_high)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(20), px(20), px(32))
            setBackgroundColor(getColor(R.color.m3_surface))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.m3_surface))
            isFillViewport = true
        }
        scroll.addView(root)
        setContentView(scroll)

        fun label(text: String, sp: Float = 14f): TextView = TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(if (sp < 14f) secondary else fg)
            setPadding(0, px(6), 0, px(8))
        }
        root.addView(label("别克设置", 24f))
        root.addView(label("曲面屏安全区域 · 仅在下次连接时应用，不修改蓝牙、音频或握手策略"))
        val switch = Switch(this).apply {
            text = "启用 CarPlay 安全区域适配"
            setTextColor(fg)
            thumbTintList = ColorStateList.valueOf(accent)
            trackTintList = ColorStateList.valueOf(getColor(R.color.m3_sec_container))
            isChecked = BuickVehiclePrefs.enabled(this@BuickSettingsActivity)
            setOnCheckedChangeListener { _, on -> BuickVehiclePrefs.setEnabled(this@BuickSettingsActivity, on) }
        }
        root.addView(switch)
        root.addView(label("车辆独立配置", 19f))
        if (profiles.isEmpty()) {
            root.addView(label("还没有已识别的车机。成功连接一次后，CarDeck 将保存匿名车辆画像、屏幕尺寸及车机提供的安全区；可再选择该车的方案。"))
        }
        val spinner = Spinner(this)
        if (profiles.isNotEmpty()) {
            val choices = profiles.map { it.label + " · " + it.key.take(6) }
            spinner.adapter = object : ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, choices,
            ) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(fg)
                        setBackgroundColor(surfaceHigh)
                        setPadding(px(12), px(12), px(12), px(12))
                    }

                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        setTextColor(fg)
                        setBackgroundColor(surfaceHigh)
                        setPadding(px(12), px(12), px(12), px(12))
                    }
            }
            val lastKey = BuickVehiclePrefs.selected(this)?.key
            val currentIndex = profiles.indexOfFirst { it.key == lastKey }.coerceAtLeast(0)
            spinner.setSelection(currentIndex)
            root.addView(spinner)
        }
        details = label("", 13f)
        root.addView(details)
        val options = listOf(
            "auto" to "自动 · 严格使用车机 Safe Area",
            "relaxed" to "舒适 · 使用车机安全边距的 50%",
            "custom" to "手动 · 分别设定四边边距（原始像素）",
            "off" to "此车辆不启用安全区域",
        )
        modes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for ((i, option) in options.withIndex()) {
            modes.addView(RadioButton(this).apply {
                id = 51000 + i
                tag = option.first
                text = option.second
                setTextColor(fg)
                buttonTintList = ColorStateList.valueOf(accent)
            })
        }
        root.addView(modes)
        root.addView(label("手动边距（原始 CarPlay 主屏像素）"))
        val marginRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        marginInputs = listOf("左", "上", "右", "下").map { title ->
            EditText(this).apply {
                hint = title
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                textSize = 15f
                setTextColor(fg)
                setHintTextColor(secondary)
                backgroundTintList = ColorStateList.valueOf(accent)
                marginRow.addView(this, LinearLayout.LayoutParams(0, px(56), 1f))
            }
        }
        root.addView(marginRow)
        root.addView(label("单独打开 App 的显示模式", 19f))
        root.addView(label("只影响单独打开的应用及全屏地图，不改变主页、Dock、仪表盘地图卡片。"))
        standaloneModes = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@BuickSettingsActivity).apply {
                id = standaloneSafeChoiceId
                tag = "safe"
                text = "安全区域 · 完整保留界面，等比例缩小，边缘使用壁纸"
                setTextColor(fg)
                buttonTintList = ColorStateList.valueOf(accent)
            })
            addView(RadioButton(this@BuickSettingsActivity).apply {
                id = standaloneFullChoiceId
                tag = "full"
                text = "全铺满 · 适合视频，允许边缘内容被车机裁切"
                setTextColor(fg)
                buttonTintList = ColorStateList.valueOf(accent)
            })
        }
        root.addView(standaloneModes)
        root.addView(label("个别 App 单独设置（可覆盖当前车辆默认方案）", 17f))
        val launchIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val installed = packageManager.queryIntentActivities(launchIntent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) null else pkg to info.loadLabel(packageManager).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        appChoices.addAll(installed)
        // Explicit light text on dark panels: native Spinner theme defaults are
        // not reliably readable on Sony dark UI (same issue as T33 settings).
        fun readableAdapter(items: List<String>) = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, items,
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(fg)
                    setBackgroundColor(surfaceHigh)
                    setPadding(px(10), px(10), px(10), px(10))
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(fg)
                    setBackgroundColor(surfaceHigh)
                    setPadding(px(10), px(10), px(10), px(10))
                }
        }
        appPicker = Spinner(this)
        appPicker.adapter = readableAdapter(appChoices.map { "${it.second} · ${it.first}" })
        root.addView(appPicker)
        appModes = Spinner(this)
        appModes.adapter = readableAdapter(listOf("跟随车辆默认", "安全区域", "全铺满"))
        root.addView(appModes)
        val perAppSave = Button(this).apply {
            text = "保存该 App 显示方式"
            setTextColor(getColor(R.color.m3_on_primary))
            backgroundTintList = ColorStateList.valueOf(accent)
            setOnClickListener {
                val key = editingKey ?: return@setOnClickListener
                val pkg = appChoices.getOrNull(appPicker.selectedItemPosition)?.first ?: return@setOnClickListener
                val mode = when (appModes.selectedItemPosition) {
                    1 -> "safe"
                    2 -> "full"
                    else -> null
                }
                BuickVehiclePrefs.setAppOverride(this@BuickSettingsActivity, key, pkg, mode)
                android.widget.Toast.makeText(
                    this@BuickSettingsActivity, "已保存该 App 的模式，重新打开该 App 时生效",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        root.addView(perAppSave)
        appPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                refreshAppOverride()
            }
        }
        if (appChoices.isEmpty()) perAppSave.isEnabled = false

        val save = Button(this).apply {
            text = "保存当前车辆方案"
            setTextColor(getColor(R.color.m3_on_primary))
            backgroundTintList = ColorStateList.valueOf(accent)
            setOnClickListener {
                val key = editingKey ?: return@setOnClickListener
                val mode = (modes.findViewById<RadioButton>(modes.checkedRadioButtonId)?.tag as? String) ?: "auto"
                val values = marginInputs.map { it.text.toString().toIntOrNull()?.coerceIn(0, 3000) ?: 0 }
                val profile = BuickVehiclePrefs.get(this@BuickSettingsActivity, key) ?: return@setOnClickListener
                val custom = BuickInsets(values[0], values[1], values[2], values[3])
                if (mode == "custom" && (values[0] + values[2] >= profile.displayWidth - 320 ||
                            values[1] + values[3] >= profile.displayHeight - 160)) {
                    android.widget.Toast.makeText(this@BuickSettingsActivity, "边距过大，请保留至少 320×160 显示区域", android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                BuickVehiclePrefs.save(this@BuickSettingsActivity, key, mode, custom)
                val standalone = if (standaloneModes.checkedRadioButtonId == standaloneSafeChoiceId) "safe" else "full"
                BuickVehiclePrefs.setStandaloneMode(this@BuickSettingsActivity, key, standalone)
                android.widget.Toast.makeText(this@BuickSettingsActivity, "已保存，下次连接该车后生效", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(save)
        root.addView(label("主页安全区与独立 App 显示分别配置。安全模式保持比例、同步触摸；全铺满不会拉伸视频，但屏幕角落可能裁切按钮。单独 App 设置在下次打开时生效。", 12f))
        if (profiles.isNotEmpty()) {
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    showProfile(profiles[pos])
                }
            }
            showProfile(profiles[spinner.selectedItemPosition.coerceAtLeast(0)])
        } else {
            save.isEnabled = false
            modes.isEnabled = false
        }
    }

    private fun showProfile(profile: BuickVehiclePrefs.Profile) {
        editingKey = profile.key
        val safe = profile.reported
        val hint = if (safe == null) "车机尚未提供可解析的 Safe Area；可选手动边距。" else
            "车机安全区：左${safe.insets.left}、上${safe.insets.top}、右${safe.insets.right}、下${safe.insets.bottom} px"
        details.text = "${profile.label} · ${hint}"
        for (i in 0 until modes.childCount) {
            val option = modes.getChildAt(i) as RadioButton
            if (option.tag == profile.mode) modes.check(option.id)
        }
        standaloneModes.check(if (profile.standaloneMode == "safe") standaloneSafeChoiceId else standaloneFullChoiceId)
        refreshAppOverride()
        val v = profile.manual
        listOf(v.left, v.top, v.right, v.bottom).forEachIndexed { i, value ->
            marginInputs[i].setText(value.toString())
        }
    }

    private fun refreshAppOverride() {
        if (!::appModes.isInitialized || !::appPicker.isInitialized) return
        val key = editingKey ?: return
        val pkg = appChoices.getOrNull(appPicker.selectedItemPosition)?.first ?: return
        val value = BuickVehiclePrefs.appOverride(this, key, pkg)
        appModes.setSelection(when (value) {
            "safe" -> 1
            "full" -> 2
            else -> 0
        })
    }
}
