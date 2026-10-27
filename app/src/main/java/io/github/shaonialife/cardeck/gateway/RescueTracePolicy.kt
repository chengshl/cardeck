package io.github.shaonialife.cardeck.gateway

/** Narrow, low-volume filter for a user-enabled, locally retained crash trail. */
internal object RescueTracePolicy {
    private val relevant = Regex(
        "RescueParty|PackageWatchdog|SettingsProvider|ServiceManager|service_manager|" +
            "avc:\\s*denied|SELinux denied|SYSTEM_RESTART|system_app_crash|system_server_crash|" +
            "system_app_anr|system_server_watchdog|failed to complete startup|" +
            "FATAL EXCEPTION|Fatal signal|Process .* has died|systemhelper",
        RegexOption.IGNORE_CASE,
    )

    /** Critical startup/reset evidence gets a separate fsync-preserved storage lane. */
    fun critical(line: String): Boolean =
        Regex(
            """RescueParty|WARM_REBOOT|PackageWatchdog.*(failed|rescue|reset)|""" +
                """denied\s*\{\s*add\s*\}|SettingsProvider.*(Exception|FATAL)|""" +
                """FATAL EXCEPTION|Fatal signal|system_server.*(ANR|watchdog|crash)|""" +
                """failed to complete startup""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(line)

    fun keep(line: String): Boolean =
        line.isNotBlank() && line.length <= 8192 &&
            !line.startsWith("---------") && relevant.containsMatchIn(line)
}
