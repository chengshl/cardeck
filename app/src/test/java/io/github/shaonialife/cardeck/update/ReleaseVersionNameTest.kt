package io.github.shaonialife.cardeck.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseVersionNameTest {
    @Test fun formalAcceptsNewAndLegacyNames() {
        assertEquals("E5-261029001",
            ReleaseVersionName.display("versionName=E5-261029001", "Buick-E5", 89134000))
        assertEquals("20261028232557",
            ReleaseVersionName.display("versionName=20261028232557", "Buick-E5", 89133957))
    }

    @Test fun testAcceptsSharedSequenceAndRejectsWrongChannel() {
        assertEquals("E5-TEST-261029002",
            ReleaseVersionName.display("versionName=E5-TEST-261029002", "Buick-Test", 89134002))
        assertEquals("89134002",
            ReleaseVersionName.display("versionName=E5-261029001", "Buick-Test", 89134002))
    }

    @Test fun malformedNamesFallBackToVersionCode() {
        for (bad in listOf("E5-261029001-danger", "E5-26102901", "../E5-261029001")) {
            assertEquals("89134001",
                ReleaseVersionName.display("versionName=$bad", "Buick-E5", 89134001))
        }
        assertEquals("89134001", ReleaseVersionName.display("", "Buick-E5", 89134001))
    }
}
