package io.github.shaonialife.cardeck.update

import android.content.Context

/**
 * 用户明确选择的软件更新渠道。由每个APK自己的SharedPreferences持有，
 * 三个独立包名之间绝不共享；首次使用默认为正式分支。
 * “自动更新”仍指自动检查，有新版本时必须经用户确认下载/安装。
 */
internal object UpdatePreference {
    private const val PREFS = "car_deck_update_channel"
    private const val KEY = "selected_channel"
    private const val KEY_PROJECT = "selected_experiment_source"

    enum class Channel(val key: String) {
        FORMAL("formal"),
        EXPERIMENT("experiment");

        companion object {
            fun parse(value: String?): Channel = entries.firstOrNull { it.key == value } ?: FORMAL
        }
    }

    fun selected(context: Context): Channel =
        Channel.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    /** 已选具体实验功能线；未选择时不自动跳到其他实验。 */
    fun selectedProject(context: Context): String? =
        validProjectSource(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROJECT, null),
        )

    /** 仅允许合法experiment短路径，源分支归属另由Release解析器验证。 */
    internal fun validProjectSource(value: String?): String? =
        value?.takeIf { it.matches(Regex("experiment/[a-z0-9_-]{3,100}")) }

    fun selectProject(context: Context, sourceBranch: String?) {
        require(sourceBranch == null || validProjectSource(sourceBranch) == sourceBranch) {
            "非法实验项目"
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (sourceBranch == null) remove(KEY_PROJECT) else putString(KEY_PROJECT, sourceBranch)
        }.apply()
    }

    fun select(context: Context, channel: Channel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, channel.key).apply()
    }
}
