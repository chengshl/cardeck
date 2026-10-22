package io.github.shaonialife.cardeck.gateway

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Maps an actual paired Bluetooth MAC to a random UUID only on this phone.
 * Neither the MAC nor the local mapping file is sent to diagnostics or backed up.
 * Active session is process-local and cleared on disconnect; never infer a car
 * from the selected target when an actual socket has not connected.
 */
object GatewayVehicleIdentity {
    private val addressPattern = Regex("(?i)^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$")
    @Volatile private var active: String? = null

    fun current(): String? = active

    @Synchronized
    fun activate(context: Context, peerAddress: String?): String? {
        val address = peerAddress?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { addressPattern.matches(it) } ?: return null
        val file = File(context.noBackupFilesDir, "gateway-vehicle-mapping.json")
        val json = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        val existing = json.optString(address).takeIf(::validUuid)
        val id = existing ?: UUID.randomUUID().toString().also {
            json.put(address, it)
            val temp = File(context.noBackupFilesDir, "gateway-vehicle-mapping.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                if (file.exists() && !file.delete() || !temp.renameTo(file)) {
                    temp.delete()
                    return null // Never issue a non-persistent identity as a stable one.
                }
            }
        }
        active = id
        return id
    }

    @Synchronized
    fun clearIfActive(id: String?) {
        if (id != null && active == id) active = null
    }

    private fun validUuid(value: String): Boolean =
        runCatching {
            UUID.fromString(value).let { it.toString() == value && it.version() == 4 }
        }.getOrDefault(false)
}
