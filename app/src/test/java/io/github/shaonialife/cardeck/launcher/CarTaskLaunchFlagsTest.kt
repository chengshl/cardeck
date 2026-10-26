package io.github.shaonialife.cardeck.launcher

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarTaskLaunchFlagsTest {
    @Test
    fun newCarTaskIsHiddenInRecentsAndCanUseIndependentDisplayTask() {
        val flags = CarTaskLaunchFlags.NEW_CAR_TASK
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            flags,
        )
        assertTrue(flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
        assertEquals("0x18800000", CarTaskLaunchFlags.shellFlags(newCarTask = true))
    }

    @Test
    fun reusedTaskNeverMutatesOriginalPhoneTaskRecentsPolicy() {
        val flags = CarTaskLaunchFlags.REUSE_TASK
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, flags)
        assertFalse(flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
        assertFalse(flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
        assertEquals("0x10000000", CarTaskLaunchFlags.shellFlags(newCarTask = false))
    }
}
