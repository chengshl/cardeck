package io.github.shaonialife.cardeck.buick

import android.content.Context

/**
 * T74 is an opt-in, Buick-Test-only diagnostic experiment. Never silently
 * truncate a healthy audio stream at 18 seconds.
 */
internal object BuickAudioGuardPrefs {
    private const val FILE = "buick_audio_t74_guard"
    private const val TIMED_FALLBACK = "timed_fallback_enabled"

    fun timedFallbackEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(TIMED_FALLBACK, false)

    fun setTimedFallbackEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(TIMED_FALLBACK, enabled).apply()
    }
}
