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
        /**
         * T42: Never rely on the thumb position alone. The status label,
         * outline, fill and actual switch tint all follow the same checked
         * state, including when the whole setting card is tapped.
         */
        fun addStateCard(
            title: String,
            initiallyEnabled: Boolean,
            onChanged: (Boolean) -> Unit,
        ): Switch {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(px(16), px(14), px(12), px(14))
                isClickable = true
                isFocusable = true
            }
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val titleView = TextView(this).apply {
                text = title
                textSize = 15.5f
                setTextColor(fg)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val stateView = TextView(this).apply {
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, px(8), 0, 0)
            }
            column.addView(titleView)
            column.addView(stateView)
            card.addView(
                column,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = px(8)
                },
            )
            val control = Switch(this).apply {
                text = ""
                showText = false
                isChecked = initiallyEnabled
                contentDescription = title
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accent, getColor(R.color.m3_outline)),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(getColor(R.color.m3_pri_container), getColor(R.color.m3_surface_highest)),
                )
                minimumWidth = px(54)
            }
            card.addView(
                control,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            fun refreshState(on: Boolean) {
                val state = if (on) "已开启" else "已关闭"
                stateView.text = if (on) "●  $state" else "○  $state"
                stateView.setTextColor(if (on) getColor(R.color.m3_ok) else secondary)
                card.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = px(18).toFloat()
                    setColor(getColor(if (on) R.color.m3_pri_container else R.color.m3_surface_high))
                    setStroke(px(if (on) 2 else 1), if (on) accent else getColor(R.color.m3_outline))
                }
                card.contentDescription = "$title，$state，点击切换"
                control.contentDescription = "$title，$state"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    control.stateDescription = state
                }
            }
            refreshState(initiallyEnabled)
            control.setOnCheckedChangeListener { _, on ->
                refreshState(on)
                onChanged(on)
            }
            card.setOnClickListener { control.toggle() }
            root.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = px(6)
                    bottomMargin = px(8)
                },
            )
            return control
        }

        root.addView(label("显示与安全区", 24f))
        root.addView(label("曲面屏安全区域", 19f))
        root.addView(label("安全区域设置在下次连接车机时生效；真实桌面和触摸始终启用。"))
        addStateCard(
            title = "CarPlay 曲面屏安全区域",
            initiallyEnabled = BuickVehiclePrefs.enabled(this),
        ) { on ->
            BuickVehiclePrefs.setEnabled(this, on)
        }
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
        root.addView(label("应用显示", 19f))
        val appManagement = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = px(18).toFloat()
                setColor(surfaceHigh)
                setStroke(px(1), getColor(R.color.m3_outline_var))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val vehicle = editingKey ?: return@setOnClickListener
                startActivity(android.content.Intent(
                    this@BuickSettingsActivity, BuickAppDisplayActivity::class.java,
                ).putExtra(BuickAppDisplayActivity.EXTRA_VEHICLE_KEY, vehicle))
            }
        }
        val appIcon = android.widget.ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            imageTintList = ColorStateList.valueOf(accent)
            contentDescription = null
        }
        appManagement.addView(appIcon, LinearLayout.LayoutParams(px(28), px(28)))
        val appCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), 0, px(8), 0)
        }
        appCopy.addView(label("应用显示管理", 16f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 0)
        })
        appCopy.addView(label("应用列表 · 搜索 · 独立安全区/全铺满", 12f).apply {
            setPadding(0, px(3), 0, 0)
        })
        appManagement.addView(appCopy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        appManagement.addView(label("›", 26f).apply {
            setTextColor(accent)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 0)
        })
        root.addView(appManagement, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = px(6)
            bottomMargin = px(16)
        })
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
                android.widget.Toast.makeText(this@BuickSettingsActivity, "已保存，下次连接该车后生效", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(save)
        root.addView(label("主页安全区域在此保存；单独打开应用的默认方案和独立配置请进入「应用显示管理」，选择后自动保存。", 12f))
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
