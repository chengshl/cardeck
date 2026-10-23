package io.github.shaonialife.cardeck.launcher

import android.content.Context
import io.github.shaonialife.cardeck.util.DebugFileLog
import io.github.shaonialife.cardeck.gateway.CriticalEventTrail
import io.github.shaonialife.cardeck.gateway.BuickDiagnosticStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * T49: No shell/network calls. A committed local phase survives a system_server
 * restart even if app log listeners could not flush their last event.
 * Mark only non-sensitive phase names, with a real timestamp.
 */
internal object DisplayCrashTrail {
    private const val PREF = BuickDiagnosticStorage.DISPLAY_TRAIL
    private const val LAST = "last_phase"

    fun mark(context: Context, phase: String) {
        CriticalEventTrail.mark(context, phase)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).format(Date())
        val record = "$time phase=$phase"
        val committed = runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(LAST, record).commit()
        }.getOrDefault(false)
        DebugFileLog.append(context, "DISPLAY-TRAIL T49: $record disk=$committed")
    }

    fun reportPrevious(context: Context) {
        val previous = runCatching {
            val current = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (current.contains(LAST)) current.getString(LAST, null)
            else BuickDiagnosticStorage.legacyPrefs(context.packageName, "display-crash-trail")
                ?.let { context.getSharedPreferences(it, Context.MODE_PRIVATE).getString(LAST, null) }
        }.getOrNull() ?: return
        DebugFileLog.append(context, "DISPLAY-TRAIL T49: 前一进程最终阶段=$previous（是否异常须结合DropBox原始时间）")
    }
}
