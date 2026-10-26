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

    enum class Channel(val key: String) {
        FORMAL("formal"),
        EXPERIMENT("experiment");

        companion object {
            fun parse(value: String?): Channel = entries.firstOrNull { it.key == value } ?: FORMAL
        }
    }

    fun selected(context: Context): Channel =
        Channel.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun select(context: Context, channel: Channel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, channel.key).apply()
    }
}
