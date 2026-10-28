package io.github.shaonialife.cardeck.update

/** 仅用于展示版本名称；Android 更新顺序始终由 versionCode 决定。 */
internal object ReleaseVersionName {
    private val legacy = Regex("""[0-9]{14}""")
    private val e5 = Regex("""E5-[0-9]{9}""")
    private val test = Regex("""E5-TEST-[0-9]{9}""")

    /** 实验包的可见名称必须绑定实际 sourceBranch，不能伪装成另一实验项目。 */
    fun isExperimental(name: String, sourceBranch: String): Boolean {
        if (!sourceBranch.startsWith("experiment/")) return false
        if (legacy.matches(name)) return true
        val normalized = sourceBranch.uppercase(java.util.Locale.ROOT)
            .replace(Regex("""[^A-Z0-9]+"""), "-").trim('-')
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(sourceBranch.toByteArray(Charsets.UTF_8))
        val fingerprint = bytes.take(6).joinToString("") {
            "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xff)
        }
        val alias = "EXP-" + normalized.takeLast(14) + "-" + fingerprint
        val formerAlias = "EXP-" + normalized.takeLast(24)
        return Regex(Regex.escape(alias) + "-[0-9]{9}").matches(name) ||
            Regex(Regex.escape(formerAlias) + "-[0-9]{9}").matches(name)
    }

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
