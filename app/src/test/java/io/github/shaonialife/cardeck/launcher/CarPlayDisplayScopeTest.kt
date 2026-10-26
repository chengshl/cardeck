package io.github.shaonialife.cardeck.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayDisplayScopeTest {
    private val e5 = "io.github.buick.e5.cardeck"
    private val test = "io.github.buick.test.cardeck"

    @Test fun scopesAllFourScreenNamesToTheCurrentApkPackage() {
        for (kind in listOf("CarPlayDock", "CarPlayApp", "CarPlayAppHi", "CarPlayMap")) {
            val own = CarPlayDisplayScope.name(kind, e5)
            assertEquals("$kind@$e5", own)
            assertTrue(CarPlayDisplayScope.owns(own, e5))
            assertFalse(CarPlayDisplayScope.owns(own, test))
            assertFalse(CarPlayDisplayScope.owns(kind, e5))
        }
    }

    @Test fun detectsOnlyOwnDisplayIdInMixedShizukuAndFallbackDumpsys() {
        val dump = """
            mDisplayId=27, displayId=27, uniqueId='virtual:com.android.shell,2000,CarPlayDock@$test,13'
            displayId=28, uniqueId='virtual:com.android.shell,2000,CarPlayApp@$e5,14'
            displayId=29, uniqueId='virtual:io.github.buick.e5.cardeck,10432,CarPlayMap@$e5,2'
            displayId=30, uniqueId='virtual:com.android.shell,2000,CarPlayAppHi@$test,15'
            displayId=31, uniqueId='virtual:com.android.shell,2000,CarPlayDock,36'
            displayId=32, uniqueId='virtual:com.android.shell,2000,CarPlayApp@$e5.attacker,16'
        """.trimIndent()
        assertEquals(listOf(28 to "CarPlayApp", 29 to "CarPlayMap"),
            CarPlayDisplayScope.parseOwned(dump, e5))
        assertEquals(listOf(27 to "CarPlayDock", 30 to "CarPlayAppHi"),
            CarPlayDisplayScope.parseOwned(dump, test))
    }

    @Test fun neverTreatsUnattributedLegacyScreensAsOwn() {
        val dump = """
            displayId=41, uniqueId='virtual:com.android.shell,2000,CarPlayDock,34'
            displayId=42, uniqueId='virtual:com.android.shell,2000,CarPlayApp,35'
            displayId=43, uniqueId='virtual:com.android.shell,2000,CarPlayAppHi,36'
            displayId=44, uniqueId='virtual:com.android.shell,2000,CarPlayMap,37'
        """.trimIndent()
        assertTrue(CarPlayDisplayScope.parseOwned(dump, e5).isEmpty())
        assertTrue(CarPlayDisplayScope.parseOwned(dump, test).isEmpty())
    }

    @Test fun rejectsUnknownNamesAndInvalidPackages() {
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayDisplayScope.name("CarPlayFake", e5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayDisplayScope.name("CarPlayDock", "not a package")
        }
    }
}
