package io.github.shaonialife.cardeck.gateway

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-private bounded log ring. Survives a device reboot; uninstall or clear-data deletes it.
 * All lines MUST already be filtered and redacted before append(). No network access.
 */
internal class RescueTraceStore(private val directory: File) {
    companion object {
        // Shared across all Store instances (service reader, heartbeat and manual export).
        private val IO_LOCK = Any()
        private const val SLOT_BYTES = 512 * 1024L
        private const val SLOTS = 4
        private const val PREFIX = "rescue-trace-"
        // Critical records have their own bounded retention in addition to the
        // existing 2 MiB rolling general trail, so harmless noisy AVCs cannot evict them.
        private const val CRITICAL_BYTES = 512 * 1024L
        private const val CRITICAL_PREFIX = "critical-trace-"

        fun forApp(context: Context) = RescueTraceStore(File(context.filesDir, "rescue-trace"))

        /** Manual export only. Never called automatically by the monitor. */
        fun export(context: Context): OfflineDiagnosticExporter.Result? {
            val store = forApp(context)
            // Take a bounded coherent snapshot; do not block the reader during MediaStore I/O.
            val snapshot = synchronized(IO_LOCK) {
                (store.oldestToNewest() + store.criticalOldestToNewest())
                    .map { it.name to it.readBytes() }
            }
            if (snapshot.isEmpty()) return null
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = "CarDeck-PreReboot-$timestamp.txt"
            val relative = Environment.DIRECTORY_DOWNLOADS + "/CarDeck"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri: Uri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
            ) ?: return null
            return try {
                var length = 0
                resolver.openOutputStream(uri)?.use { output ->
                    snapshot.forEach { (filename, bytes) ->
                        val separator = ("\n====== " + filename + " ======\n").toByteArray()
                        output.write(separator)
                        length += separator.size
                        output.write(bytes)
                        length += bytes.size
                    }
                } ?: error("no output")
                check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null) > 0)
                OfflineDiagnosticExporter.Result(
                    uri, name, "/storage/emulated/0/$relative/$name", length,
                )
            } catch (_: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }
    }

    private fun slot(i: Int) = File(directory, PREFIX + i + ".log")
    private fun oldestToNewest() = (SLOTS - 1 downTo 0).map(::slot).filter { it.isFile && it.length() > 0L }
    private fun criticalSlot(i: Int) = File(directory, CRITICAL_PREFIX + i + ".log")
    private fun criticalOldestToNewest() = (1 downTo 0).map(::criticalSlot).filter { it.isFile && it.length() > 0L }

    fun retainedBytes(): Long = synchronized(IO_LOCK) {
        (oldestToNewest() + criticalOldestToNewest()).sumOf { it.length() }
    }

    fun append(safeLine: String, critical: Boolean = false) = synchronized(IO_LOCK) {
        require(safeLine.length <= 256) { "unbounded capture" }
        val bytes = (safeLine + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > 1024) return
        if (!directory.exists() && !directory.mkdirs()) error("failed to create evidence directory")
        var current = slot(0)
        if (current.length() + bytes.size > SLOT_BYTES) {
            slot(SLOTS - 1).delete()
            for (i in SLOTS - 2 downTo 0) {
                val old = slot(i)
                if (old.exists() && !old.renameTo(slot(i + 1))) error("evidence rotation failed")
            }
            current = slot(0)
        }
        // Closing the stream on every selected event means there is no large user-space
        // buffer to lose during a sudden RescueParty reboot.
        FileOutputStream(current, true).use {
            it.write(bytes)
            if (critical) it.fd.sync()
        }
        if (critical) {
            // Separate 2 x 256 KiB slots; rotate without deleting main trail.
            val each = CRITICAL_BYTES / 2
            var vital = criticalSlot(0)
            if (vital.length() + bytes.size > each) {
                criticalSlot(1).delete()
                if (vital.exists() && !vital.renameTo(criticalSlot(1))) {
                    error("critical evidence rotation failed")
                }
                vital = criticalSlot(0)
            }
            FileOutputStream(vital, true).use {
                it.write(bytes)
                it.fd.sync()
            }
        }
    }
}
