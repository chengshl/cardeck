package io.github.shaonialife.cardeck.launcher

/**
 * CarDeck多安装包的虚拟屏归属规则。
 *
 * Shizuku建屏统一由shell UID持有，dumpsys的owner uid无法区分Buick-E5、
 * Buick-main和Buick-Test，旧屏又全部叫CarPlayDock/App/AppHi/Map。
 * 自本规则起在displayName写入应用包名；未知归属的旧屏绝不自动回收。
 * 此标记用于安全筛选而非身份认证，releaseDisplay仍需服务实际持有该屏。
 */
internal object CarPlayDisplayScope {
    private val kinds = setOf("CarPlayDock", "CarPlayApp", "CarPlayAppHi", "CarPlayMap")
    private val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val displayEntry = Regex("""displayId=(\d+),\s*uniqueId='([^']*)'""")

    fun name(kind: String, packageName: String): String {
        require(kind in kinds) { "未知CarPlay显示类型" }
        require(packagePattern.matches(packageName)) { "非法包名" }
        return "$kind@$packageName"
    }

    fun owns(name: String, packageName: String): Boolean =
        kinds.any { kind -> name == "$kind@$packageName" }

    /** 只从完整uniqueId的名称字段匹配，不匹配其他分支、旧的无包名名称。 */
    fun parseOwned(dump: String, packageName: String): List<Pair<Int, String>> {
        if (!packagePattern.matches(packageName)) return emptyList()
        return displayEntry.findAll(dump).mapNotNull { match ->
            val displayId = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val uniqueId = match.groupValues[2]
            val kind = kinds.firstOrNull { k ->
                uniqueId.contains(",$k@$packageName,") ||
                    uniqueId.endsWith(",$k@$packageName")
            } ?: return@mapNotNull null
            displayId to kind
        }.distinctBy { it.first }.toList()
    }
}
