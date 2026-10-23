package io.github.shaonialife.cardeck.buick

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/**
 * Local-only profiles. Raw device IDs, pairing credentials, SSIDs and Bluetooth
 * addresses are never stored in this preference file or shown in the settings.
 */
internal object BuickVehiclePrefs {
    private const val NAME = "buick_viewport_profiles_v1"
    private const val ENABLED = "enabled"
    private const val SALT = "install_salt"
    private const val KEYS = "profile_keys"
    private const val CURRENT = "last_profile"

    data class Profile(
        val key: String,
        val label: String,
        val displayWidth: Int,
        val displayHeight: Int,
        val reported: BuickSafeArea?,
        val mode: String,
        val manual: BuickInsets,
        /** Default for a standalone app or fullscreen map, independent of the home/Dock mode. */
        val standaloneMode: String,
        val lastSeen: Long,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, value).apply()
    }

    /** A per-installation salted fingerprint distinguishes two cars with the same model. */
    fun keyFor(context: Context, receiverId: String?): String? {
        val id = receiverId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
        val p = prefs(context)
        val salt = p.getString(SALT, null) ?: UUID.randomUUID().toString().also {
            p.edit().putString(SALT, it).apply()
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("CarDeck/Buick/view/v1/" + salt + "/" + id).toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun observe(
        context: Context,
        receiverId: String?,
        model: String?,
        displayWidth: Int,
        displayHeight: Int,
        safe: BuickSafeArea?,
    ): Profile? {
        val key = keyFor(context, receiverId) ?: return null
        if (displayWidth !in 320..12000 || displayHeight !in 160..5000) return null
        val p = prefs(context)
        val keys = p.getStringSet(KEYS, emptySet()).orEmpty().toMutableSet()
        keys.add(key)
        val label = model.orEmpty().replace(Regex("[^A-Za-z0-9 _-]"), "").take(28)
            .ifBlank { "Buick VCS" } + " · " + displayWidth + "×" + displayHeight
        val edit = p.edit()
            .putStringSet(KEYS, keys)
            .putString(CURRENT, key)
            .putString("${key}_label", label)
            .putInt("${key}_w", displayWidth)
            .putInt("${key}_h", displayHeight)
            .putLong("${key}_last", System.currentTimeMillis())
        if (safe != null) {
            edit.putInt("${key}_safe_left", safe.insets.left)
                .putInt("${key}_safe_top", safe.insets.top)
                .putInt("${key}_safe_right", safe.insets.right)
                .putInt("${key}_safe_bottom", safe.insets.bottom)
        } else {
            for (side in listOf("left", "top", "right", "bottom")) edit.remove("${key}_safe_$side")
        }
        edit.apply()
        return get(context, key)
    }

    fun selected(context: Context): Profile? =
        prefs(context).getString(CURRENT, null)?.let { get(context, it) }

    fun all(context: Context): List<Profile> =
        prefs(context).getStringSet(KEYS, emptySet()).orEmpty()
            .mapNotNull { get(context, it) }.sortedByDescending { it.lastSeen }

    fun get(context: Context, key: String): Profile? {
        val p = prefs(context)
        if (key !in p.getStringSet(KEYS, emptySet()).orEmpty()) return null
        val w = p.getInt("${key}_w", 0)
        val h = p.getInt("${key}_h", 0)
        val safe = if (p.contains("${key}_safe_left")) {
            BuickSafeArea(w, h, BuickInsets(
                p.getInt("${key}_safe_left", 0),
                p.getInt("${key}_safe_top", 0),
                p.getInt("${key}_safe_right", 0),
                p.getInt("${key}_safe_bottom", 0),
            ))
        } else null
        return Profile(
            key, p.getString("${key}_label", "Buick VCS").orEmpty(),
            w, h, safe, p.getString("${key}_mode", "auto") ?: "auto",
            BuickInsets(
                p.getInt("${key}_manual_left", 0), p.getInt("${key}_manual_top", 0),
                p.getInt("${key}_manual_right", 0), p.getInt("${key}_manual_bottom", 0),
            ),
            p.getString("${key}_standalone_mode", "full").let { if (it == "safe") "safe" else "full" },
            p.getLong("${key}_last", 0L),
        )
    }

    /** "full" keeps the legacy immersive behaviour; "safe" preserves all app pixels. */
    fun setStandaloneMode(context: Context, key: String, mode: String) {
        require(mode == "full" || mode == "safe")
        if (get(context, key) == null) return
        prefs(context).edit().putString("${key}_standalone_mode", mode).apply()
    }

    private fun packageKey(context: Context, key: String, packageName: String): String {
        val p = prefs(context)
        val salt = p.getString(SALT, null).orEmpty()
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(("CarDeck/Buick/app/v1/" + salt + "/" + key + "/" + packageName)
                .toByteArray(Charsets.UTF_8))
        return key + "_app_" + bytes.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Local-only app override; null means follow that vehicle's default. */
    fun appOverride(context: Context, key: String, packageName: String): String? {
        if (get(context, key) == null || packageName.isBlank()) return null
        return prefs(context).getString(packageKey(context, key, packageName), null)
            ?.takeIf { it == "full" || it == "safe" }
    }

    fun setAppOverride(context: Context, key: String, packageName: String, mode: String?) {
        require(mode == null || mode == "full" || mode == "safe")
        if (get(context, key) == null || packageName.isBlank()) return
        val edit = prefs(context).edit()
        val prefKey = packageKey(context, key, packageName)
        if (mode == null) edit.remove(prefKey) else edit.putString(prefKey, mode)
        edit.apply()
    }

    fun standaloneModeFor(context: Context, key: String?, packageName: String): String {
        if (key == null) return "full"
        return appOverride(context, key, packageName)
            ?: get(context, key)?.standaloneMode ?: "full"
    }

    fun save(context: Context, key: String, mode: String, custom: BuickInsets) {
        require(mode in setOf("auto", "relaxed", "custom", "off"))
        if (get(context, key) == null) return
        prefs(context).edit().putString("${key}_mode", mode)
            .putInt("${key}_manual_left", custom.left.coerceIn(0, 3000))
            .putInt("${key}_manual_top", custom.top.coerceIn(0, 3000))
            .putInt("${key}_manual_right", custom.right.coerceIn(0, 3000))
            .putInt("${key}_manual_bottom", custom.bottom.coerceIn(0, 3000))
            .apply()
    }
}
