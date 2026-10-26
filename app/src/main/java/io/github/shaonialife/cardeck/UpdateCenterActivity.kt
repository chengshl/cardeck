package io.github.shaonialife.cardeck

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import io.github.shaonialife.cardeck.update.BuickUpdater
import io.github.shaonialife.cardeck.update.ExperimentalUpdates
import io.github.shaonialife.cardeck.update.InstalledBuildIdentity
import io.github.shaonialife.cardeck.update.UpdatePreference

/** 当前APK独立软件更新页：更新渠道、自动检查、构建信息及实验历史统一入口。 */
class UpdateCenterActivity : AppCompatActivity() {
    private val bg = Color.rgb(16, 20, 24)
    private val surface = Color.rgb(29, 36, 43)
    private val selectedSurface = Color.rgb(32, 54, 63)
    private val outline = Color.rgb(61, 74, 81)
    private val accent = Color.rgb(134, 216, 235)
    private val fg = Color.rgb(237, 244, 247)
    private val muted = Color.rgb(162, 176, 184)

    private lateinit var formal: LinearLayout
    private lateinit var experiment: LinearLayout
    private lateinit var formalMark: TextView
    private lateinit var experimentMark: TextView
    private lateinit var selectedLabel: TextView
    private lateinit var installedOriginLabel: TextView
    private lateinit var currentChannelLabel: TextView
    private lateinit var browse: TextView

    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()
    private fun shape(fill: Int, stroke: Int = fill): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }
    private fun txt(content: String, size: Float, color: Int = fg, bold: Boolean = false) =
        TextView(this).apply {
            text = content
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1f)
            isSingleLine = false
        }
    private fun LinearLayout.line(v: View, top: Int = 0) {
        addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
    private fun branch(): String = when (packageName) {
        "io.github.buick.e5.cardeck" -> "Buick-E5"
        "io.github.buick.main.cardeck" -> "Buick-main"
        "io.github.buick.test.cardeck" -> "Buick-Test"
        else -> packageName
    }
    private fun card(title: String, summary: String, hint: String): Pair<LinearLayout, TextView> {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(16), dp(17), dp(16))
            minimumHeight = dp(116)
            isClickable = true
            isFocusable = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(txt(title, 17f, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
        val mark = txt("", 12.5f, accent, true)
        header.addView(mark)
        wrapper.line(header)
        wrapper.line(txt(summary, 13f, muted), 6)
        wrapper.line(txt(hint, 12f, muted), 7)
        return wrapper to mark
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = Color.BLACK
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(page)
        }
        setContentView(scroll)

        page.line(txt("‹  返回别克设置", 14f, accent, true).apply {
            setPadding(0, dp(8), 0, dp(12))
            setOnClickListener { finish() }
        })
        page.line(txt("软件更新", 27f, fg, true), 7)
        page.line(txt("当前应用：${branch()}\n当前安装包来源与更新检查通道分别展示，互不混淆。", 12.5f, muted), 9)

        page.line(txt("更新分支", 17f, fg, true), 25)
        page.line(txt("点击选择并保存。自动检查将以已选分支为准。", 12f, muted), 5)

        val a = card("正式分支", "当前 ${branch()} 的独立正式更新通道",
            "适合日常使用 · 检查此应用的正式安装包")
        formal = a.first
        formalMark = a.second
        page.line(formal, 12)

        val b = card("试验分支", "当前 ${branch()} 的独立试验构建",
            "用于测试新功能 · 可能不稳定 · 支持查看历史版本")
        experiment = b.first
        experimentMark = b.second
        page.line(experiment, 11)
        formal.setOnClickListener { choose(UpdatePreference.Channel.FORMAL) }
        experiment.setOnClickListener { choose(UpdatePreference.Channel.EXPERIMENT) }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(16), dp(17), dp(14))
            background = shape(surface, outline)
        }
        page.line(info, 21)
        val apk = packageManager.getPackageInfo(packageName, 0)
        info.line(txt("当前安装包", 12f, muted, true))
        installedOriginLabel = txt(
            InstalledBuildIdentity.description(getString(R.string.cardeck_installed_build_origin)),
            14.5f, accent, true
        )
        info.line(installedOriginLabel, 7)
        selectedLabel = txt("已安装版本：${apk.versionName ?: "未知"}", 14f, fg, true)
        info.line(selectedLabel, 9)
        currentChannelLabel = txt("", 12.5f, muted)
        info.line(currentChannelLabel, 7)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val rowText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rowText.line(txt("自动检查更新", 15f, fg, true))
        rowText.line(txt("仅检查当前选中分支，安装仍需确认", 12f, muted), 5)
        val autoState = txt(if (BuickUpdater.autoEnabled(this)) "状态：已开启" else "状态：已关闭", 12.5f, muted, true)
        rowText.line(autoState, 6)
        row.addView(rowText, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = SwitchCompat(this).apply {
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, muted),
            )
            isChecked = BuickUpdater.autoEnabled(this@UpdateCenterActivity)
            contentDescription = "自动检查当前选中更新分支"
            setOnCheckedChangeListener { _, checked ->
                BuickUpdater.setAutoEnabled(this@UpdateCenterActivity, checked)
                autoState.text = if (checked) "状态：已开启" else "状态：已关闭"
            }
        }
        row.addView(toggle)
        info.line(row, 15)

        page.line(txt("检查所选分支更新", 16f, bg, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(52)
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = shape(accent)
            setOnClickListener { BuickUpdater.checkSelected(this@UpdateCenterActivity) }
        }, 17)
        browse = txt("查看试验分支历史安装包", 14f, accent, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(50)
            setPadding(dp(10), dp(11), dp(10), dp(11))
            background = shape(surface, outline)
            setOnClickListener { ExperimentalUpdates.browse(this@UpdateCenterActivity) }
        }
        page.line(browse, 9)
        page.line(txt(
            "版本详情显示来源、更新内容与安装包构建完成时间（北京时间）。" +
                "旧包无真实构建时间时会明确标注版本时间。" +
                "\n试验版与正式版共用当前包名；版本号较高时无法直接安装更旧的正式包。",
            12f, muted), 18)
        refresh()
    }

    private fun choose(value: UpdatePreference.Channel) {
        UpdatePreference.select(this, value)
        refresh()
    }
    private fun refresh() {
        val isExperiment = UpdatePreference.selected(this) == UpdatePreference.Channel.EXPERIMENT
        formal.background = shape(if (!isExperiment) selectedSurface else surface,
            if (!isExperiment) accent else outline)
        experiment.background = shape(if (isExperiment) selectedSurface else surface,
            if (isExperiment) accent else outline)
        formalMark.text = if (!isExperiment) "✓ 已选择" else "选择"
        experimentMark.text = if (isExperiment) "✓ 已选择" else "选择"
        currentChannelLabel.text = if (isExperiment) "当前检查通道：试验分支（与已安装来源不同）" else "当前检查通道：正式分支"
        browse.visibility = if (isExperiment) View.VISIBLE else View.GONE
    }
}
