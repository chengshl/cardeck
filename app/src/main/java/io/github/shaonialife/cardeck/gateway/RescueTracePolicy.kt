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

    // A benign "Starting to observe" is emitted for every DeviceConfig namespace and
    // can generate hundreds of context copies if the tag alone is considered fatal.
    // Keep those messages in the normal lane; reserve fsync storage for actual rescue actions.
    private val rescueAction = Regex(
        """RescueParty\s*:\s*(?:(?:Attempting|Finished|Failed)\s+rescue\s+level\b|""" +
            """(?:Rebooting|Restarting)\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val criticalEvent = Regex(
        """WARM_REBOOT|PackageWatchdog.*(failed|rescue|reset)|""" +
            """denied\s*\{\s*add\s*\}|SettingsProvider.*(Exception|FATAL)|""" +
            """FATAL EXCEPTION|Fatal signal|system_server.*(ANR|watchdog|crash)|""" +
            """failed to complete startup""",
        RegexOption.IGNORE_CASE,
    )

    /** Critical startup/reset evidence gets a separate fsync-preserved storage lane. */
    fun critical(line: String): Boolean =
        criticalEvent.containsMatchIn(line) || rescueAction.containsMatchIn(line)

    fun keep(line: String): Boolean =
        line.isNotBlank() && line.length <= 8192 &&
            !line.startsWith("---------") && relevant.containsMatchIn(line)
}
