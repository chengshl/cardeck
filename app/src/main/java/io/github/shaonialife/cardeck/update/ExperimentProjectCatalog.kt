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

    fun name(sourceBranch: String): String = when {
        sourceBranch.contains("t76-aac-causality-observe") -> "CarPlay 音频 · 20秒断连实验"
        sourceBranch.contains("t75") -> "T75 · 音频 RTCP"
        sourceBranch.contains("t74") -> "T74 · 音频实验"
        sourceBranch.contains("t68") -> "T68 · 显示适配"
        sourceBranch.contains("task-isolation") -> "任务隔离实验"
        sourceBranch.contains("touch") -> "触摸恢复实验"
        sourceBranch.contains("updater-b") -> "B方案 · 更新器测试"
        else -> sourceBranch.substringAfterLast('/').replace('-', ' ')
    }

    fun installState(installed: Long, candidate: ExperimentalUpdates.Candidate): String =
        when {
            candidate.versionCode > installed -> "可覆盖安装 · 需手动确认"
            candidate.versionCode == installed -> "版本号相同 · 需要重新构建更高版本"
            else -> "实验版号低于本机 · 需要重新构建更高版本"
        }
}
