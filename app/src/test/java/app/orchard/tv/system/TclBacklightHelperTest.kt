package app.orchard.tv.system

import org.junit.Assert.*
import org.junit.Test

class TclBacklightHelperTest {
    @Test fun stepsFromRealNonGridValues() {
        assertEquals(50, TclBacklightHelper.nextLevel(48, 1))
        assertEquals(45, TclBacklightHelper.nextLevel(48, -1))
    }

    @Test fun allLitLevelsStayLitAndMonotonic() {
        for (level in 1..100) {
            val up = TclBacklightHelper.nextLevel(level, 1)
            val down = TclBacklightHelper.nextLevel(level, -1)
            assertTrue(up in level..100)
            assertTrue(down in 1..level)
            assertTrue(up == 100 || up % 5 == 0)
            assertTrue(down == 1 || down % 5 == 0)
        }
    }

    @Test fun invalidReadingsAndDirectionsAreRejected() {
        for ((level, direction) in listOf(0 to 1, 101 to -1, -1 to 1, 48 to 0, 48 to Int.MAX_VALUE)) {
            try {
                TclBacklightHelper.nextLevel(level, direction)
                fail("Invalid panel reading/direction was accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
