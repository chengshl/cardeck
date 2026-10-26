package io.github.shaonialife.cardeck.update

/**
 * 安装包的编译来源，不等于用户在更新页面选择的检查通道。
 * 不能从用户选择推断旧APK实际来自哪个构建源。
 */
internal object InstalledBuildIdentity {
    enum class Track { FORMAL, EXPERIMENT, UNKNOWN }

    fun track(origin: String): Track = when {
        origin == "Buick-E5" -> Track.FORMAL
        origin.startsWith("experiment/buick-e5-") -> Track.EXPERIMENT
        else -> Track.UNKNOWN
    }

    fun description(origin: String): String = when (track(origin)) {
        Track.FORMAL -> "正式分支 · Buick-E5"
        Track.EXPERIMENT -> "试验分支 · ${origin.substringAfterLast('/')}"
        Track.UNKNOWN -> "构建来源未标记（旧版或本地构建）"
    }
}
