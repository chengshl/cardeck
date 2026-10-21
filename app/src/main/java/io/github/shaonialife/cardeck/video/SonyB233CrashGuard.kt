package io.github.shaonialife.cardeck.video

/**
 * T28 fail-safe routing after Xperia 1 V / Android 15 repeatedly rebooted
 * with system_server android.ui WindowContextListenerImpl NPE on display-add.
 *
 * Keep the guard restricted to this verified phone and Buick B233.
 * Legacy renderers are untouched for all other phone/receiver combinations.
 */
internal object SonyB233CrashGuard {
    fun useDisplaylessDiagnostic(
        manufacturer: String,
        model: String,
        sdk: Int,
        receiverIsB233: Boolean,
    ): Boolean =
        receiverIsB233 &&
            sdk >= 35 &&
            manufacturer.contains("sony", ignoreCase = true) &&
            model.replace('_', '-').startsWith("XQ-DQ72", ignoreCase = true)
}
