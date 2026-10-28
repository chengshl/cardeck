package io.github.shaonialife.cardeck.update

/** 仅用于展示版本名称；Android 更新顺序始终由 versionCode 决定。 */
internal object ReleaseVersionName {
    private val legacy = Regex("""[0-9]{14}""")
    private val e5 = Regex("""E5-[0-9]{9}""")
    private val test = Regex("""E5-TEST-[0-9]{9}""")

    fun display(body: String, branch: String, fallbackCode: Long): String {
        val name = ReleaseBuildTime.metadata(body, "versionName")
            ?: return fallbackCode.toString()
        val allowed = legacy.matches(name) || when (branch) {
            "Buick-E5" -> e5.matches(name)
            "Buick-Test" -> test.matches(name)
            else -> false
        }
        return if (allowed) name else fallbackCode.toString()
    }
}
