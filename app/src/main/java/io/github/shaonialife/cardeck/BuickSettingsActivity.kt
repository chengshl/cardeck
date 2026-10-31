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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.shaonialife.cardeck.buick.BuickConnectionModePrefs
import io.github.shaonialife.cardeck.buick.BuickE5HandshakePrefs
import io.github.shaonialife.cardeck.buick.BuickInsets
import io.github.shaonialife.cardeck.buick.BuickVehiclePrefs
import io.github.shaonialife.cardeck.airplay.AudioReverseControlPort
import io.github.shaonialife.cardeck.airplay.B233UdpBindPreflight

/** Settings are per locally identified receiver; never persist raw BT or /info device identifiers. */
class BuickSettingsActivity : AppCompatActivity() {
    private val profiles by lazy { BuickVehiclePrefs.all(this) }
    private var editingKey: String? = null
    private lateinit var details: TextView
    private lateinit var modes: RadioGroup
    private lateinit var marginInputs: List<EditText>
    @Volatile private var audioPreflightCancelled = false

    override fun onDestroy() {
        audioPreflightCancelled = true
        super.onDestroy()
    }

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
        // Buick-Test T59: presentation only. Keep all existing preference keys and values.
        fun rounded(fill: Int, stroke: Int? = null, radiusDp: Int = 18): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = px(radiusDp).toFloat()
                setColor(fill)
                if (stroke != null) setStroke(px(1), stroke)
            }

        fun heading(title: String, subtitle: String) {
            root.addView(label(title, 19f).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, px(14), 0, px(2))
            })
            root.addView(label(subtitle, 12f).apply {
                setPadding(0, 0, 0, px(10))
            })
        }

        root.addView(label("别克设置", 27f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, px(6), 0, px(4))
        })
        root.addView(label("别克 E5 服务、连接方式与曲面屏显示设置", 13f).apply {
            setPadding(0, 0, 0, px(16))
        })
        heading("服务连接方式", "仅影响别克 E5/B233；更改后停止服务并重新启动才生效")
        val modeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(15), px(16), px(16))
            background = rounded(surfaceHigh, getColor(R.color.m3_outline), 16)
        }
        modeCard.addView(label("iAP2 连接方向", 16f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, px(5))
        })
        val modeStatus = label("", 14f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, px(2), 0, px(10))
        }
        modeCard.addView(modeStatus)
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(4), px(4), px(4), px(4))
            background = rounded(getColor(R.color.m3_surface), getColor(R.color.m3_outline), 12)
        }
        fun modeChoice(): TextView = TextView(this).apply {
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            minHeight = px(50)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isClickable = true
            isFocusable = true
        }
        val passiveChoice = modeChoice()
        val activeChoice = modeChoice()
        modeRow.addView(passiveChoice, LinearLayout.LayoutParams(0, px(50), 1f).apply { rightMargin = px(4) })
        modeRow.addView(activeChoice, LinearLayout.LayoutParams(0, px(50), 1f))
        modeCard.addView(modeRow)
        modeCard.addView(label(
            "被动（默认）：服务启动后等待车机连接，不主动发起 iAP2。" +
                "主动：服务启动后由手机尝试连接车机；两次尝试之间至少间隔15秒，并避免与车机主动连接竞争。" +
                "切换时不会中断正在运行的服务或删除配对记录。",
            12f,
        ).apply { setPadding(0, px(10), 0, 0) })
        fun refreshMode(active: Boolean) {
            modeStatus.text = if (active) "当前已保存：主动连接" else "当前已保存：被动连接（推荐）"
            modeStatus.setTextColor(getColor(if (active) R.color.m3_amber else R.color.m3_ok))
            for ((button, optionActive) in listOf(passiveChoice to false, activeChoice to true)) {
                val checked = active == optionActive
                button.text = if (optionActive) {
                    if (checked) "✓ 主动连接" else "主动连接"
                } else {
                    if (checked) "✓ 被动连接" else "被动连接"
                }
                button.setTextColor(if (checked) getColor(R.color.m3_on_primary) else fg)
                button.background = rounded(if (checked) accent else surfaceHigh, null, 10)
                button.contentDescription = button.text.toString() + if (checked) "，已选择" else "，点击切换"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    button.stateDescription = if (checked) "已选中" else "未选中"
                }
            }
        }
        passiveChoice.setOnClickListener {
            BuickConnectionModePrefs.setActive(this@BuickSettingsActivity, false)
            refreshMode(false)
        }
        activeChoice.setOnClickListener {
            BuickConnectionModePrefs.setActive(this@BuickSettingsActivity, true)
            refreshMode(true)
        }
        refreshMode(BuickConnectionModePrefs.active(this))
        root.addView(modeCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = px(16) })

        heading("握手兼容实验", "仅用于其他别克车机的兼容性验证")
        val handshakeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(15), px(16), px(16))
            background = rounded(surfaceHigh, getColor(R.color.m3_outline), 16)
        }
        handshakeCard.addView(label("强制使用 E5/B233 握手流程", 16f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, px(5))
        })
        handshakeCard.addView(label(
            "开启后，下次连接跳过车型身份/消息清单的协议选择，直接执行 E5 的 32B MFi、0x4300/0x4301 流程。关闭后恢复默认识别；不会自动清除配对。",
            12f,
        ).apply { setPadding(0, 0, 0, px(10)) })
        val handshakeStatus = label("", 14f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, px(10))
        }
        handshakeCard.addView(handshakeStatus)
        val handshakeChoices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(4), px(4), px(4), px(4))
            background = rounded(getColor(R.color.m3_surface), getColor(R.color.m3_outline), 12)
        }
        fun handshakeOption(): TextView = TextView(this).apply {
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            minHeight = px(50)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isClickable = true
            isFocusable = true
        }
        val handshakeOff = handshakeOption()
        val handshakeOn = handshakeOption()
        handshakeChoices.addView(handshakeOff, LinearLayout.LayoutParams(0, px(50), 1f).apply { rightMargin = px(4) })
        handshakeChoices.addView(handshakeOn, LinearLayout.LayoutParams(0, px(50), 1f))
        handshakeCard.addView(handshakeChoices)
        fun refreshHandshake(enabled: Boolean) {
            handshakeStatus.text = if (enabled) "当前：强制 E5 握手（实验中）" else "当前：默认协议（推荐）"
            handshakeStatus.setTextColor(if (enabled) getColor(R.color.m3_amber) else getColor(R.color.m3_ok))
            for ((choice, on) in listOf(handshakeOff to false, handshakeOn to true)) {
                val checked = enabled == on
                choice.text = when {
                    checked && on -> "✓ 强制 E5"
                    checked -> "✓ 默认关闭"
                    on -> "强制 E5"
                    else -> "默认关闭"
                }
                choice.setTextColor(if (checked) getColor(R.color.m3_on_primary) else fg)
                choice.background = rounded(
                    if (checked) accent else surfaceHigh, null, 10,
                )
                choice.contentDescription = choice.text.toString() + (if (checked) "，已选择" else "，点击切换")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    choice.stateDescription = if (checked) "已选中" else "未选中"
                }
            }
        }
        handshakeOff.setOnClickListener {
            BuickE5HandshakePrefs.setEnabled(this@BuickSettingsActivity, false)
            refreshHandshake(false)
        }
        handshakeOn.setOnClickListener {
            BuickE5HandshakePrefs.setEnabled(this@BuickSettingsActivity, true)
            refreshHandshake(true)
        }
        refreshHandshake(BuickE5HandshakePrefs.enabled(this))
        root.addView(handshakeCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = px(12) })
        root.addView(label(
            "只对已选目标车机，或自动发现中名称包含 Buick 的设备强制生效；建议先在「连接准备」指定目标车机。更改后需重新连接。",
            12f,
        ).apply { setPadding(0, 0, 0, px(12)) })

        // Audio P0 / T97 manual, audio-free network preflight.
        // This surface exists only in the signed Buick-Test package; it cannot
        // modify the VPN, request networks, send UDP, or start CarPlay audio.
        if (packageName == "io.github.buick.test.cardeck") {
            heading("B233 音频网络预检（实验）", "仅停车后手动执行；需已经连接 B233 纯视频会话")
            val diagnosticCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(16), px(15), px(16), px(16))
                background = rounded(surfaceHigh, getColor(R.color.m3_outline), 16)
            }
            val diagnosticStatus = label(
                "未检测。此项只测试手机能否在当前车机 Wi-Fi 上绑定接收型 UDP 端口；" +
                    "不发送音频或任何 UDP 数据，也不代表车机已接受音频资源。",
                12f,
            )
            val diagnosticButton = Button(this).apply {
                text = "手动检测 B233 Wi-Fi / 反向 RTCP（25秒）"
                setTextColor(getColor(R.color.m3_on_primary))
                backgroundTintList = ColorStateList.valueOf(accent)
                setOnClickListener {
                    val selected = SessionController.b233NetworkForAudioFreePreflight()
                    if (selected == null) {
                        diagnosticStatus.text =
                            "未执行：仅允许已连接 B233 的纯视频会话（从未请求或尝试音频）。" +
                                "不要为预检开启 CarPlay 音频，也不要修改 VPN。"
                        return@setOnClickListener
                    }
                    androidx.appcompat.app.AlertDialog.Builder(this@BuickSettingsActivity)
                        .setTitle("停车状态下运行只读网络预检？")
                        .setMessage(
                            "请确认车辆已停稳。测试保留接收型 UDP 监听约 25 秒，" +
                                "不会发送网络数据、不修改 VPN 或连接设置、不启动音频。" +
                                "检测成功仅证明当时能绑定监听端口，不保证解决 20 秒断连。",
                        )
                        .setNegativeButton("取消", null)
                        .setPositiveButton("开始检测") { _, _ ->
                            // Recheck after the dialog: an audio session may have
                            // begun or the car Wi-Fi Network may have changed.
                            if (SessionController.b233NetworkForAudioFreePreflight() != selected) {
                                diagnosticStatus.text = "未执行：纯视频会话或 Wi-Fi Network 已变化。"
                            } else {
                                isEnabled = false
                                diagnosticStatus.text = "检测中：最长 25 秒；期间不要切换网络或启动音频。"
                                Thread({
                                    val result = B233UdpBindPreflight(
                                        bindNetwork = { udp -> selected.bindSocket(udp) },
                                        // No raw log transport from this manual UI surface.
                                        log = {},
                                        networkStillValid = {
                                            !audioPreflightCancelled &&
                                                SessionController.b233NetworkForAudioFreePreflight() == selected
                                        },
                                        onBindError = AudioReverseControlPort::bindFailureCategory,
                                    ).run(25_000L)
                                    runOnUiThread {
                                        if (!audioPreflightCancelled && !isFinishing && !isDestroyed) {
                                            isEnabled = true
                                            diagnosticStatus.text = when (result.status) {
                                                "pass" ->
                                                    "绑定成功：接收型 UDP 监听持续 ${result.heldMs / 1000} 秒。" +
                                                        " 未发送任何 UDP；不代表车机收到心跳或音频资源已获授权。"
                                                "bind_failed" ->
                                                    "绑定失败：${result.errno}；未开放/宣告音频控制端口。" +
                                                        " 不要将此记为车机拒绝 media SETUP。"
                                                "network_missing", "network_changed" ->
                                                    "未完成：纯视频会话或车机 Wi-Fi 已变化。"
                                                "listener_exited" ->
                                                    "未通过：UDP 接收监听提前退出。"
                                                "interrupted" ->
                                                    "预检中止；未判定车机音频能力。"
                                                else -> "预检未通过：${result.status}。"
                                            }
                                        }
                                    }
                                }, "b233-manual-udp-preflight").apply {
                                    isDaemon = true
                                    start()
                                }
                            }
                        }
                        .show()
                }
            }
            diagnosticCard.addView(diagnosticStatus)
            diagnosticCard.addView(diagnosticButton)
            root.addView(diagnosticCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = px(16) })
        }

        heading("曲面屏安全区域", "设置下次连接生效；桌面与触摸功能始终保持开启")

        // T60: explicitly labelled two-state control. A tinted switch thumb was
        // indistinct on Sony's dark screen; never rely on position/color alone.
        val toggleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
            isClickable = true
            isFocusable = true
        }
        toggleCard.addView(label("CarPlay 曲面屏安全区域", 16f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, px(5))
        })
        val stateText = label("", 14f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, px(2), 0, px(12))
        }
        toggleCard.addView(stateText)

        val choiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(px(4), px(4), px(4), px(4))
            background = rounded(getColor(R.color.m3_surface), getColor(R.color.m3_outline), 14)
        }
        fun choice(): TextView = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            minHeight = px(54)
            isClickable = true
            isFocusable = true
        }
        val offChoice = choice()
        val onChoice = choice()
        choiceRow.addView(offChoice, LinearLayout.LayoutParams(0, px(54), 1f).apply {
            rightMargin = px(4)
        })
        choiceRow.addView(onChoice, LinearLayout.LayoutParams(0, px(54), 1f))
        toggleCard.addView(choiceRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        fun updateToggle(on: Boolean) {
            stateText.text = if (on) "当前状态：已开启" else "当前状态：已关闭"
            stateText.setTextColor(if (on) getColor(R.color.m3_on_pri_container) else fg)
            toggleCard.background = rounded(
                if (on) getColor(R.color.m3_pri_container) else surfaceHigh,
                if (on) accent else getColor(R.color.m3_outline),
            )
            for ((choice, value) in listOf(offChoice to false, onChoice to true)) {
                val selected = value == on
                choice.text = when {
                    selected && value -> "✓ 已开启"
                    selected -> "✓ 已关闭"
                    value -> "开启"
                    else -> "关闭"
                }
                choice.setTextColor(if (selected) getColor(R.color.m3_on_primary) else fg)
                choice.background = rounded(
                    if (selected) accent else getColor(R.color.m3_surface_high),
                    null, 11,
                )
                choice.isSelected = selected
                choice.contentDescription = (if (value) "开启" else "关闭") +
                    " CarPlay 曲面屏安全区域，" + (if (selected) "当前已选" else "点击选择")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    choice.stateDescription = if (selected) "已选中" else "未选中"
                }
            }
            toggleCard.contentDescription = "CarPlay 曲面屏安全区域，" +
                (if (on) "已开启" else "已关闭") + "，点击可切换"
        }
        fun selectToggle(on: Boolean) {
            // Preserve T59's immediate persistent setting and next-connection effect.
            BuickVehiclePrefs.setEnabled(this@BuickSettingsActivity, on)
            updateToggle(on)
        }
        offChoice.setOnClickListener { selectToggle(false) }
        onChoice.setOnClickListener { selectToggle(true) }
        toggleCard.setOnClickListener {
            selectToggle(!BuickVehiclePrefs.enabled(this@BuickSettingsActivity))
        }
        updateToggle(BuickVehiclePrefs.enabled(this))
        root.addView(toggleCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = px(12) })

        heading("车辆独立配置", "每辆车单独保存，不会覆盖其他车机的显示方案")
        if (profiles.isEmpty()) {
            root.addView(label("还没有已识别的车机。成功连接一次后，CarDeck 将保存匿名车辆画像、屏幕尺寸及车机提供的安全区；可再选择该车的方案。"))
        }
        val spinner = Spinner(this).apply {
            minimumHeight = px(52)
            background = rounded(surfaceHigh, getColor(R.color.m3_outline_var), 14)
        }
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
        details = label("", 13f).apply {
            setPadding(px(14), px(12), px(14), px(12))
            background = rounded(surfaceHigh, getColor(R.color.m3_outline_var), 14)
        }
        root.addView(details, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = px(10); bottomMargin = px(12) })
        heading("主屏安全区方案", "选择显示方式并保存；已保存的值会在下次连接时应用")
        val options = listOf(
            Triple("auto", "自动适配", "严格使用车机提供的 Safe Area"),
            Triple("relaxed", "舒适模式", "使用车机安全边距的 50%"),
            Triple("custom", "手动设置", "分别指定四边边距，单位为原始像素"),
            Triple("off", "不使用安全区", "此车辆使用原始全屏画面"),
        )
        modes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for ((i, option) in options.withIndex()) {
            modes.addView(RadioButton(this).apply {
                id = 51000 + i
                tag = option.first
                text = option.second + "\n" + option.third
                textSize = 14f
                setTextColor(fg)
                setLineSpacing(px(3).toFloat(), 1f)
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = px(65)
                setPadding(px(14), px(9), px(12), px(9))
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accent, secondary),
                )
            }, RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = px(8) })
        }
        root.addView(modes)

        val manualSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(14))
            background = rounded(surfaceHigh, getColor(R.color.m3_outline_var), 16)
        }
        manualSection.addView(label("手动边距 · px", 15f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, px(7))
        })
        manualSection.addView(label("仅在选择「手动设置」时使用，下次连接生效", 12f).apply {
            setPadding(0, 0, 0, px(10))
        })
        val marginFields = mutableMapOf<String, EditText>()
        fun addMarginRow(first: String, second: String) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((index, side) in listOf(first, second).withIndex()) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(10), px(8), px(10), px(7))
                    background = rounded(getColor(R.color.m3_surface_highest), null, 12)
                }
                cell.addView(label(side + "边距", 12f).apply {
                    setPadding(0, 0, 0, px(2))
                })
                val input = EditText(this).apply {
                    hint = "0"
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setSingleLine(true)
                    textSize = 16f
                    setTextColor(fg)
                    setHintTextColor(secondary)
                    backgroundTintList = ColorStateList.valueOf(accent)
                    setSelectAllOnFocus(true)
                    filters = arrayOf(android.text.InputFilter.LengthFilter(4))
                    contentDescription = side + "边距（像素）"
                }
                marginFields[side] = input
                cell.addView(input, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, px(42),
                ))
                row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index == 0) rightMargin = px(8)
                })
            }
            manualSection.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = px(8) })
        }
        addMarginRow("左", "右")
        addMarginRow("上", "下")
        marginInputs = listOf("左", "上", "右", "下").map { marginFields.getValue(it) }
        root.addView(manualSection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = px(12) })

        fun refreshModes() {
            val chosen = modes.checkedRadioButtonId
            for (i in 0 until modes.childCount) {
                val option = modes.getChildAt(i) as RadioButton
                val selected = option.id == chosen
                option.background = rounded(
                    if (selected) getColor(R.color.m3_pri_container) else surfaceHigh,
                    if (selected) accent else getColor(R.color.m3_outline_var),
                    14,
                )
                option.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            manualSection.visibility = if (chosen == 51002) View.VISIBLE else View.GONE
        }
        modes.setOnCheckedChangeListener { _, _ -> refreshModes() }
        refreshModes()

        heading("应用显示", "单独控制地图、视频或其他应用的安全区与全铺满")
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
        if (profiles.isEmpty()) {
            appManagement.isEnabled = false
            appManagement.alpha = 0.5f
        }
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
        root.addView(save, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, px(52),
        ).apply { topMargin = px(4); bottomMargin = px(10) })
        root.addView(label("主屏方案在此保存；应用显示管理中的单独配置会自动保存。", 12f).apply {
            setPadding(0, 0, 0, px(10))
        })
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
            for (i in 0 until modes.childCount) modes.getChildAt(i).isEnabled = false
            marginInputs.forEach { it.isEnabled = false }
        }
    }

    private fun showProfile(profile: BuickVehiclePrefs.Profile) {
        editingKey = profile.key
        val safe = profile.reported
        val hint = if (safe == null) "车机尚未提供可解析的 Safe Area；可选手动边距。" else
            "车机安全区：左${safe.insets.left}、上${safe.insets.top}、右${safe.insets.right}、下${safe.insets.bottom} px"
        details.text = profile.label + "\n" + hint
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
