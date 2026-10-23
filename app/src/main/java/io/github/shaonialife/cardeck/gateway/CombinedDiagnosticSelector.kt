package io.github.shaonialife.cardeck.gateway

/**
 * One manually collected, redacted preview for both system warnings and reboot
 * forensics. CrashEvidenceSelector has already prioritized and bounded the
 * critical evidence; normal warnings must never evict its first stack frames.
 */
internal object CombinedDiagnosticSelector {
    private const val MAX_LINES = 256
    private const val RESERVED_WARNINGS = 48

    fun merge(
        crashEvidence: List<String>,
        currentWarnings: List<String>,
        shizukuReady: Boolean,
    ): List<String> {
        // Both collectors already redact their own output. Do not re-process
        // it here or risk losing meaningful clock timestamps.
        val critical = crashEvidence.asSequence()
            .filter { it.isNotBlank() }.map { it.take(256) }.take(MAX_LINES).toList()
        if (!shizukuReady) return critical

        val warnings = currentWarnings.asSequence()
            .filter { it.isNotBlank() }
            .map { ("[当前系统] " + it).take(256) }
            .toList()
        if (warnings.isEmpty()) return critical

        // Keep all short crash previews; reserve a modest, fixed budget for the
        // *latest* warnings only when enough warning lines actually exist.
        val criticalBudget = MAX_LINES - minOf(RESERVED_WARNINGS, warnings.size)
        val chosenCritical = critical.take(criticalBudget)
        val chosenWarnings = warnings.takeLast(MAX_LINES - chosenCritical.size)
        return chosenCritical + chosenWarnings
    }
}
