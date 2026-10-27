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
    internal fun defaultForOrigin(origin: String): Channel =
        if (InstalledBuildIdentity.track(origin) == InstalledBuildIdentity.Track.EXPERIMENT) {
            Channel.EXPERIMENT
        } else {
            Channel.FORMAL
        }

    fun selected(context: Context): Channel {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY)) return Channel.parse(prefs.getString(KEY, null))
        return defaultForOrigin(
            context.getString(io.github.shaonialife.cardeck.R.string.cardeck_installed_build_origin)
        )
    }

    fun select(context: Context, channel: Channel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, channel.key).apply()
    }
}
