package io.github.shaonialife.cardeck.gateway

/** 对已脱敏的取证预览做说明，不推断崩溃根因或把历史记录冒充新事件。 */
internal object DiagnosticPreviewSummary {
    fun headline(eventId: String?, uploadedBefore: Boolean): String = when {
        uploadedBefore -> "历史证据 · 此前提交过相同崩溃记录"
        eventId != null -> "发现带原始时间的系统崩溃记录"
        else -> "尚未识别带发生时间的系统崩溃事件"
    }

    /** A compact one-screen synopsis; detailed legacy body remains unchanged. */
    fun compact(
        lines: List<String>,
        eventId: String?,
        uploadedBefore: Boolean,
        shizukuReady: Boolean,
        fullCount: Int? = null,
        diagnosticType: String? = null,
    ): String {
        val count = fullCount ?: lines.size
        val eventTime = eventId?.substringAfter('@')?.takeIf { it.isNotBlank() }
        val parts = listOf(
            "App " + lines.count { it.startsWith("[App历史]") || it.startsWith("[上轮App") },
            "阶段 " + lines.count { it.startsWith("[阶段事件]") },
            "系统 " + lines.count { it.startsWith("[当前系统]") || it.startsWith("[取证") },
            "崩溃 " + lines.count { it.startsWith("[DropBox:") },
        ).joinToString(" · ")
        return buildString {
            append((diagnosticType ?: "快速诊断") + "：已准备 " + count + " 行")
            if (fullCount != null && fullCount > lines.size) append("（仅预览 " + lines.size + " 行）")
            append("\n样本构成：" + parts)
            append("\n系统权限：" + if (shizukuReady) "Shizuku已就绪" else "Shizuku未就绪，只有部分本地记录")
            if (eventTime != null) append("\n崩溃证据原始时间：" + eventTime)
            if (uploadedBefore) append("\n相同证据曾上传；不是重新发生崩溃的证明")
            append("\n\n保存只在手机；上传需确认并等待全部批次回执")
            if (fullCount != null) append("\nLogcat缓冲区可能已轮转，不能保证完整覆盖事故前历史")
        }
    }

    fun body(
        lines: List<String>,
        eventId: String?,
        uploadedBefore: Boolean,
        shizukuReady: Boolean,
    ): String {
        val appCount = lines.count { it.startsWith("[上轮App") }
        val stageCount = lines.count { it.startsWith("[阶段事件]") }
        val systemCount = lines.count { it.startsWith("[当前系统]") }
        val dropboxCount = lines.count { it.startsWith("[DropBox:") }
        val dated = eventId?.substringAfter('@', "")?.takeIf { it.isNotBlank() }
        val source = eventId?.substringBefore('@')?.takeIf { it.isNotBlank() }
        return buildString {
            append("本次精选预览：${lines.size} 行（上限256行，并非完整历史日志量）\n")
            append("前次App ${appCount} 行 · 阶段记录 ${stageCount} 行\n")
            append("DropBox ${dropboxCount} 行 · 当前系统警告 ${systemCount} 行\n\n")
            if (dated != null) {
                append("系统崩溃记录的原始发生时间：$dated\n")
                append("记录来源：$source\n")
                append("这个时间不是本次点击预览或上传的时间。\n\n")
            } else {
                append("未识别出带原始发生时间的系统崩溃事件。\n")
                append("不代表没有发生系统故障。\n\n")
            }
            if (uploadedBefore) {
                append("本机已保存此前确认提交同一崩溃证据的指纹。\n")
                append("提交请求已排队不代表所有批次都已被服务器确认。\n")
                append("这是重复证据，不能作为今天再次崩溃的证明。\n\n")
            } else if (eventId != null) {
                append("本机未记录这条崩溃证据此前成功提交的指纹。\n")
                append("这不等于它是刚刚发生的新崩溃。\n\n")
            }
            if (shizukuReady) {
                append("Shizuku 已就绪，可按系统权限读取警告和历史DropBox。\n")
            } else {
                append("Shizuku 未就绪：仅可查看此前App日志和关键阶段记录。\n")
                append("这不是系统崩溃记录缺失的证明。\n")
            }
            append("\n可切换「精选日志」检查每一行脱敏记录。\n")
            append("如需更完整的App运行历史，请回到日志管理选择「保存完整本地诊断包」（仅本地保存，最多2MB App历史）。")
        }
    }
}
