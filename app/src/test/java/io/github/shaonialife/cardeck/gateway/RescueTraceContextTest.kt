package io.github.shaonialife.cardeck.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueTraceContextTest {
    @Test fun fatalContextIsDurableIncludingStack() {
        val context = RescueTraceContext()
        assertTrue(context.accept("unrelated line", listOf("[片段1/1] before")).isEmpty())

        val opened = context.accept(
            "2026-10-28 11:20:31 E AndroidRuntime: FATAL EXCEPTION: main",
            listOf("[片段1/1] FATAL EXCEPTION: main"),
        )
        assertEquals(2, opened.size)
        assertTrue(opened.all { it.second })
        assertTrue(opened.any { it.first.contains("before") })

        repeat(12) { index ->
            val stack = context.accept(
                "2026-10-28 E AndroidRuntime:  at call$index",
                listOf("[片段1/1] at call$index"),
            )
            assertEquals(1, stack.size)
            assertTrue(stack.first().second)
        }
        val later = context.accept(
            "2026-10-28 W ServiceManager: SELinux denied",
            listOf("[片段1/1] SELinux denied"),
        )
        assertEquals(1, later.size)
        assertFalse(later.first().second)
    }

    @Test fun ordinaryWarningsDoNotFloodCriticalRetention() {
        val context = RescueTraceContext()
        val entries = context.accept(
            "2026-10-28 W ServiceManager: SELinux denied",
            listOf("[片段1/1] SELinux denied"),
        )
        assertEquals(1, entries.size)
        assertFalse(entries.single().second)
    }
}
