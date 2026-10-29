package io.github.shaonialife.cardeck.update

/** 把可信发布候选按具体实验源码分组；跨功能线绝不按版本号抢占更新。 */
internal object ExperimentProjectCatalog {
    internal data class Project(
        val sourceBranch: String,
        val newest: ExperimentalUpdates.Candidate,
    )

    fun group(candidates: List<ExperimentalUpdates.Candidate>): List<Project> =
        candidates.groupBy { it.sourceBranch }
            .mapNotNull { (source, items) ->
                items.maxByOrNull { it.versionCode }?.let { Project(source, it) }
            }.sortedBy { it.sourceBranch }

    fun latest(
        candidates: List<ExperimentalUpdates.Candidate>,
        selectedSource: String?,
    ): ExperimentalUpdates.Candidate? {
        if (selectedSource == null) return null
        return candidates.asSequence().filter { it.sourceBranch == selectedSource }
            .maxByOrNull { it.versionCode }
    }

    /** 仅映射可信项目名称的显示文案，不改 sourceBranch、订阅或安装策略。 */
    fun name(sourceBranch: String): String {
        val slug = sourceBranch.substringAfterLast('/')
        return when (sourceBranch) {
            "experiment/buick-e5-bluetooth-a2dp-preserve" -> "蓝牙音源保护（A2DP/HFP）"
            "experiment/buick-e5-detailed-diagnostics" -> "详细故障诊断与日志"
            "experiment/buick-e5-iap2-stop-safety" -> "iAP2 安全退出"
            "experiment/buick-e5-session-teardown" -> "CarPlay 会话退出与重连"
            "experiment/buick-e5-updater-b-minimal" -> "更新器方案 B · 最小融合"
            "experiment/buick-e5-t78-updater-b-convergence" -> "更新器融合 · T78 联调"
            "experiment/buick-e5-test-convergence" -> "Test → E5 功能融合"
            else -> when {
                slug.contains("t75") -> "T75 · 音频 RTCP"
                slug.contains("t74") -> "T74 · 音频实验"
                slug.contains("t68") -> "T68 · 显示适配"
                slug.contains("task-isolation") -> "任务隔离实验"
                slug.contains("touch") -> "触摸恢复实验"
                slug.contains("updater-b") -> "更新器方案 B · 功能验证"
                else -> "其他实验 · " + slug.replace('-', ' ')
            }
        }
    }

    fun installState(installed: Long, candidate: ExperimentalUpdates.Candidate): String =
        when {
            candidate.versionCode > installed -> "可覆盖安装 · 需手动确认"
            candidate.versionCode == installed -> "版本号相同 · 需要重新构建更高版本"
            else -> "实验版号低于本机 · 需要重新构建更高版本"
        }
}
