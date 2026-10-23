package io.github.shaonialife.cardeck

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(20), px(20), px(32))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        fun label(text: String, sp: Float = 14f): TextView = TextView(this).apply {
            this.text = text
            textSize = sp
            setPadding(0, px(6), 0, px(8))
        }
        root.addView(label("别克设置", 24f))
        root.addView(label("曲面屏安全区域 · 仅在下次连接时应用，不修改蓝牙、音频或握手策略"))
        val switch = Switch(this).apply {
            text = "启用 CarPlay 安全区域适配"
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
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
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
                marginRow.addView(this, LinearLayout.LayoutParams(0, px(56), 1f))
            }
        }
        root.addView(marginRow)
        val save = Button(this).apply {
            text = "保存当前车辆方案"
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
                android.widget.Toast.makeText(this@BuickSettingsActivity, "已保存，下次连接该车后生效", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(save)
        root.addView(label("背景与视频流保持原始协商分辨率，只有界面内容按安全区缩放排布；边缘区域使用深色留白。触摸坐标同步校正。关闭总开关可完全恢复原版布局。", 12f))
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
        val v = profile.manual
        listOf(v.left, v.top, v.right, v.bottom).forEachIndexed { i, value ->
            marginInputs[i].setText(value.toString())
        }
    }
}
