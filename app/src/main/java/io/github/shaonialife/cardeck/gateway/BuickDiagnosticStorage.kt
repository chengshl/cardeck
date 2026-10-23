package io.github.shaonialife.cardeck.gateway

import java.util.Locale

/**
 * Stable Buick diagnostic preference names. Android applicationId already
 * isolates data across installed APKs. Legacy names exist for read migration
 * only; new records never include a build variant in the preference name.
 */
internal object BuickDiagnosticStorage {
    const val EVENTS = "buick-critical-events"
    const val DISPLAY_TRAIL = "buick-display-crash-trail"

    fun legacyPrefs(packageName: String, purpose: String): String? {
        if (!purpose.matches(Regex("[a-z0-9-]{1,40}"))) return null
        val parts = packageName.lowercase(Locale.ROOT).split('.')
        val index = parts.indexOf("buick")
        val variant = if (index >= 0) parts.getOrNull(index + 1) else null
        if (variant == null || !variant.matches(Regex("[a-z0-9]{1,16}"))) return null
        return "buick-$variant-$purpose"
    }
}
