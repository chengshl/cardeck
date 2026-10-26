package io.github.shaonialife.cardeck.update

/** 普通覆盖安装要求版本号递增；较旧 APK 仅允许保存到下载目录。 */
internal object ExperimentInstallPolicy {
    enum class Mode { UPGRADE, SAVE_ONLY }
    fun mode(installed: Long, candidate: Long): Mode =
        if (candidate > installed) Mode.UPGRADE else Mode.SAVE_ONLY
}
