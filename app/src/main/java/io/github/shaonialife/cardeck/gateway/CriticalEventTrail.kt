package io.github.shaonialife.cardeck.gateway

import android.content.Context
import android.os.SystemClock
import io.github.shaonialife.cardeck.util.DebugFileLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * T52 critical breadcrumbs. No network, shell, timer, continuous sampling or raw data.
 * SharedPreferences.commit() is intentional: stage survives killed app / system_server.
 * Only infrequent named transitions should call mark().
 */
internal object CriticalEventTrail {
    private const val PREFS = BuickDiagnosticStorage.EVENTS
    private const val KEY = "last_50"
    private val session = UUID.randomUUID().toString().substring(0, 8)
    private val lock = Any()

    private fun readStoredEvents(context: Context): List<CriticalEvent> {
        val current = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = if (current.contains(KEY)) {
            current.getString(KEY, "").orEmpty()
        } else {
            // Preserve history saved under the old APK-specific file name.
            // mark() writes the common name after reading this old data.
            BuickDiagnosticStorage.legacyPrefs(context.packageName, "t52-critical-events")
                ?.let { legacy ->
                    context.applicationContext.getSharedPreferences(legacy, Context.MODE_PRIVATE)
                        .getString(KEY, "")
                }.orEmpty()
        }
        return CriticalTrailRing.decode(raw)
    }

    fun mark(context: Context, stage: String) {
        if (!CriticalTrailRing.validPhase(stage)) return
        val entry = CriticalEvent(System.currentTimeMillis(), SystemClock.elapsedRealtime(), session, stage)
        val ok = synchronized(lock) {
            runCatching {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val old = readStoredEvents(context)
                val next = CriticalTrailRing.append(old, entry)
                prefs.edit().putString(KEY, CriticalTrailRing.encode(next)).commit()
            }.getOrDefault(false)
        }
        DebugFileLog.append(context, "CRITICAL-TRAIL T52: stage=" + stage +
            " process=" + session + " committed=" + ok)
    }

    /** Safe offline read; works without Shizuku or Gateway. */
    fun recentLines(context: Context): List<String> {
        val events = synchronized(lock) {
            runCatching {
                readStoredEvents(context)
            }.getOrDefault(emptyList())
        }
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US)
        return events.mapIndexed { idx, event ->
            val before = events.getOrNull(idx - 1)
            val boundary = when {
                before == null -> "history_begin"
                CriticalTrailRing.possibleDeviceReboot(before, event) -> "possible_device_reboot"
                CriticalTrailRing.newAppProcess(before, event) -> "new_app_process"
                else -> "same_app_process"
            }
            "[事件轨迹] " + format.format(Date(event.wallMs)) +
                " elapsed_ms=" + event.elapsedMs + " process=" + event.session +
                " stage=" + event.phase + " boundary=" + boundary
        }
    }

    /** Call before recording current APP_PROCESS_START, so older events stay identifiable. */
    fun reportPrevious(context: Context) {
        recentLines(context).takeLast(6).forEach { line ->
            DebugFileLog.append(context, "CRITICAL-TRAIL T52: 上次记录 " + line)
        }
    }
}
