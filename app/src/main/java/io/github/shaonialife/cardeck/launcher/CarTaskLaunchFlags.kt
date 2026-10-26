package io.github.shaonialife.cardeck.launcher

/**
 * A car-only Android task must never create an extra entry in the phone's
 * Recent Apps list. The flag applies to the *root launch of a new task*, not
 * to existing phone tasks or to any reused target task.
 *
 * NEW_TASK = 0x10000000, MULTIPLE_TASK = 0x08000000,
 * EXCLUDE_FROM_RECENTS = 0x00800000 (android.content.Intent API).
 */
internal object CarTaskLaunchFlags {
    const val NEW_CAR_TASK: Int = 0x18800000
    const val REUSE_TASK: Int = 0x10000000

    fun shellFlags(newCarTask: Boolean): String =
        "0x" + (if (newCarTask) NEW_CAR_TASK else REUSE_TASK).toString(16)
}
