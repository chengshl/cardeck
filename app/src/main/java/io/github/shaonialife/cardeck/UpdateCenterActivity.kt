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
import io.github.shaonialife.cardeck.update.ExperimentProjectCatalog
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
    private lateinit var browse: TextView
    private lateinit var projectPanel: LinearLayout
    private lateinit var projectName: TextView
    private lateinit var projectStatus: TextView
    private var availableProjects: List<ExperimentProjectCatalog.Project>? = null
    private var projectLookupError: String? = null

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
        page.line(txt("当前应用：${branch()}\n每个安装包独立保存更新分支，不跨别克包名安装。", 12.5f, muted), 9)
        page.line(txt("提示：这里选的是检查更新的渠道，并不代表当前安装包是正式版或试验版。手动安装实验APK后，请选试验分支及对应项目。", 12f, muted), 5)

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

        // 项目目录与选中功能线分开保存，空目录绝不自动订阅另一条实验线。
        projectPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = shape(surface, outline)
        }
        projectPanel.line(txt("关注的实验项目", 15.5f, fg, true))
        projectName = txt("尚未选择项目", 14f, accent, true)
        projectPanel.line(projectName, 8)
        projectStatus = txt("正在读取实验目录…", 12f, muted)
        projectPanel.line(projectStatus, 6)
        val chooseProject = txt("选择 / 更换实验项目", 14f, bg, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            background = shape(accent)
            setPadding(dp(9), dp(11), dp(9), dp(11))
            isFocusable = true
            isClickable = true
            setOnClickListener {
                ExperimentalUpdates.chooseProject(this@UpdateCenterActivity) {
                    refresh()
                    reloadProjects()
                }
            }
        }
        projectPanel.line(chooseProject, 12)
        page.line(projectPanel, 12)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(16), dp(17), dp(14))
            background = shape(surface, outline)
        }
        page.line(info, 21)
        selectedLabel = txt("", 14.5f, fg, true)
        info.line(selectedLabel)
        val apk = packageManager.getPackageInfo(packageName, 0)
        info.line(txt("已安装：${apk.versionName ?: "未知"}", 12.5f, muted), 7)

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
                "\n方案B：低版本无法覆盖时，从所选实验代码重新构建高版本签名包，无需卸载；安装仍需用户确认。",
            12f, muted), 18)
        refresh()
        reloadProjects()
    }

    private fun choose(value: UpdatePreference.Channel) {
        UpdatePreference.select(this, value)
        refresh()
        reloadProjects()
    }
    private fun reloadProjects() {
        if (UpdatePreference.selected(this) != UpdatePreference.Channel.EXPERIMENT) return
        ExperimentalUpdates.loadProjects(this) { projects, error ->
            availableProjects = projects
            projectLookupError = error
            refresh()
        }
    }

    private fun refresh() {
        val isExperiment = UpdatePreference.selected(this) == UpdatePreference.Channel.EXPERIMENT
        formal.background = shape(if (!isExperiment) selectedSurface else surface,
            if (!isExperiment) accent else outline)
        experiment.background = shape(if (isExperiment) selectedSurface else surface,
            if (isExperiment) accent else outline)
        formalMark.text = if (!isExperiment) "✓ 已选择" else "选择"
        experimentMark.text = if (isExperiment) "✓ 已选择" else "选择"
        selectedLabel.text = if (isExperiment) "当前已选择：试验分支" else "当前已选择：正式分支"
        projectPanel.visibility = if (isExperiment) View.VISIBLE else View.GONE
        val source = UpdatePreference.selectedProject(this)
        projectName.text = if (source == null) "尚未选择实验项目" else
            ExperimentProjectCatalog.name(source) + "\n" + source
        val projects = availableProjects
        projectStatus.text = when {
            !isExperiment -> ""
            projectLookupError != null -> "实验目录读取失败：$projectLookupError"
            projects == null -> "正在读取当前安装包的实验发布…"
            projects.isEmpty() -> "暂无符合当前包名要求的已发布实验项目。"
            source == null -> "有 ${projects.size} 个已发布实验项目。请选择一个，自动检查不会跨项目。"
            else -> {
                val found = projects.firstOrNull { it.sourceBranch == source }
                if (found == null) "所选实验暂时没有可安装APK，不会切到其他项目。"
                else {
                    val info = packageManager.getPackageInfo(packageName, 0)
                    val installed = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                        @Suppress("DEPRECATION")
                        info.versionCode.toLong()
                    }
                    "已发布：${found.newest.versionName}\n" +
                        ExperimentProjectCatalog.installState(installed, found.newest)
                }
            }
        }
        browse.visibility = if (isExperiment && source != null) View.VISIBLE else View.GONE
    }
}
