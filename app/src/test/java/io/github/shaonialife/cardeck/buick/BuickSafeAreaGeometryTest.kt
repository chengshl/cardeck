package io.github.shaonialife.cardeck.buick

import org.junit.Assert.*
import org.junit.Test

class BuickSafeAreaGeometryTest {
    private val primary = mapOf(
        "widthPixels" to 1972L, "heightPixels" to 520L, "initialViewArea" to 0L,
        "viewAreas" to listOf(mapOf("originXPixels" to 0L, "originYPixels" to 0L,
            "safeArea" to mapOf("originXPixels" to 278L, "originYPixels" to 44L,
                "widthPixels" to 1408L, "heightPixels" to 476L))),
    )

    @Test fun e5PrimaryUsesActualReportedCoordinates() {
        val s = BuickSafeAreaGeometry.parse(primary)!!
        assertEquals(BuickInsets(278, 44, 286, 0), s.insets)
        val v = BuickSafeAreaGeometry.resolve(s, 1972, 520, 1972, 520, "auto", BuickInsets.ZERO)!!
        assertEquals(BuickViewport(278, 44, 1408, 476), v)
        assertTrue(v.contains(278, 44))
        assertFalse(v.contains(277, 44))
        assertEquals(Pair(0, 0), v.unmap(278, 44, 1972, 520))
    }

    @Test fun scaleAndModesDoNotChangeStreamDimensions() {
        val s = BuickSafeAreaGeometry.parse(primary)!!
        assertEquals(BuickViewport(139, 22, 704, 238),
            BuickSafeAreaGeometry.resolve(s, 1972, 520, 986, 260, "auto", BuickInsets.ZERO))
        assertEquals(BuickViewport(139, 22, 1690, 498),
            BuickSafeAreaGeometry.resolve(s, 1972, 520, 1972, 520, "relaxed", BuickInsets.ZERO))
        assertNull(BuickSafeAreaGeometry.resolve(s, 1972, 520, 1972, 520, "off", BuickInsets.ZERO))
        assertEquals(BuickViewport(20, 5, 1952, 515),
            BuickSafeAreaGeometry.resolve(null, 1972, 520, 1972, 520, "custom", BuickInsets(20,5,0,0)))
    }

    @Test fun rejectInvalidReceiverRectsAndUnsafeManualInsets() {
        assertNull(BuickSafeAreaGeometry.parse(primary + ("viewAreas" to emptyList<Any>())))
        assertNull(BuickSafeAreaGeometry.parse(primary + ("viewAreas" to
            listOf(mapOf("safeArea" to mapOf("originXPixels" to 1900, "originYPixels" to 0,
                "widthPixels" to 200, "heightPixels" to 400))))))
        assertNull(BuickSafeAreaGeometry.resolve(null, 1972, 520, 1972, 520,
            "auto", BuickInsets.ZERO))
        assertNull(BuickSafeAreaGeometry.resolve(null, 1972, 520, 1972, 520,
            "custom", BuickInsets(1000,0,1000,0)))
    }

    @Test fun secondaryDisplayHasIndependentSafeRect() {
        val secondary = mapOf(
            "widthPixels" to 2194L, "heightPixels" to 700L,
            "viewAreas" to listOf(mapOf("safeArea" to mapOf(
                "originXPixels" to 680L, "originYPixels" to 82L,
                "widthPixels" to 1162L, "heightPixels" to 486L))),
        )
        assertEquals(BuickInsets(680, 82, 352, 132), BuickSafeAreaGeometry.parse(secondary)?.insets)
    }
}
