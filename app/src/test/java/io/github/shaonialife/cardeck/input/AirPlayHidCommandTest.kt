package io.github.shaonialife.cardeck.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirPlayHidCommandTest {
    @Test fun legacyTopLevelHidReportStillWorks() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val hid = AirPlayHidCommand.parse(mapOf("type" to "hidSendReport", "uuid" to "1", "hidReport" to data))
        assertEquals("1", hid.uuid)
        assertArrayEquals(data, hid.report)
        assertEquals("root", hid.layout)
    }

    @Test fun nestedParamsHidReportIsNotLost() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val hid = AirPlayHidCommand.parse(mapOf("type" to "hidSendReport", "params" to mapOf("uuid" to 0, "hidReport" to data)))
        assertEquals("0", hid.uuid)
        assertArrayEquals(data, hid.report)
        assertEquals("params", hid.layout)
    }

    @Test fun missingReportDoesNotInventTouch() {
        val hid = AirPlayHidCommand.parse(mapOf("type" to "hidSendReport", "params" to mapOf("uuid" to "1")))
        assertEquals("1", hid.uuid)
        assertNull(hid.report)
        assertEquals("missing", hid.layout)
    }

    @Test fun invalidParamsDoNotCrash() {
        val hid = AirPlayHidCommand.parse(mapOf("type" to "hidSendReport", "params" to "invalid"))
        assertNull(hid.uuid)
        assertNull(hid.report)
    }
}
