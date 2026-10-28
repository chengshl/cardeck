package io.github.shaonialife.cardeck.update

import android.content.Context

/**
 * 用户明确选择的软件更新渠道。由每个APK自己的SharedPreferences持有，
 * 三个独立包名之间绝不共享；首次使用时实验安装包默认本实验，正式安装包默认正式。
 * 已保存的明确渠道选择不因安装升级而被覆盖。
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

    /**
     * A signed experiment APK follows its own release track on first use.
     * A user's explicit Formal or Experiment selection ALWAYS wins.
     */
    fun selected(context: Context): Channel =
        Channel.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    /** 只跟踪用户选定的具体实验分支，避免其他实验的更高版本抢占。 */
    fun selectedProject(context: Context): String? =
        validProjectSource(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROJECT, null))

    internal fun validProjectSource(value: String?): String? =
        value?.takeIf { it.matches(Regex("experiment/[a-z0-9_-]{3,100}")) }

    fun selectProject(context: Context, sourceBranch: String?) {
        require(sourceBranch == null || validProjectSource(sourceBranch) == sourceBranch) { "非法实验项目" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (sourceBranch == null) remove(KEY_PROJECT) else putString(KEY_PROJECT, sourceBranch)
        }.apply()
    }

    fun select(context: Context, channel: Channel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, channel.key).apply()
    }
}
