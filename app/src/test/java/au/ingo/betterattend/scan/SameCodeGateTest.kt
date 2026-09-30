package au.ingo.betterattend.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameCodeGateTest {
    private val gate = SameCodeGate(windowMs = 2_500)

    @Test fun firstSightingIsAccepted() {
        assertTrue(gate.offer("A", now = 0))
    }

    @Test fun repeatedFramesAreIgnored() {
        assertTrue(gate.offer("A", 0))
        assertFalse(gate.offer("A", 100))
        assertFalse(gate.offer("A", 2_000))
    }

    @Test fun codeHeldInFrameStaysBlockedBecauseEachFrameRefreshesTimer() {
        assertTrue(gate.offer("A", 0))
        var t = 0L
        repeat(100) { t += 100; assertFalse(gate.offer("A", t)) } // 10 s of continuous frames
    }

    @Test fun acceptedAgainAfterBeingOutOfFrameForTheWindow() {
        assertTrue(gate.offer("A", 0))
        assertFalse(gate.offer("A", 1_000))
        assertFalse(gate.offer("A", 3_499)) // only 2.499 s absent
        assertTrue(gate.offer("A", 6_000)) // 2.501 s absent
    }

    @Test fun exactlyTheWindowCounts() {
        assertTrue(gate.offer("A", 0))
        assertTrue(gate.offer("A", 2_500))
    }

    @Test fun differentCodesAreIndependent() {
        assertTrue(gate.offer("A", 0))
        assertTrue(gate.offer("B", 10))
        assertFalse(gate.offer("A", 20))
        assertFalse(gate.offer("B", 30))
    }

    @Test fun twoCodesInFrameDoNotFlipFlop() {
        assertTrue(gate.offer("A", 0))
        assertTrue(gate.offer("B", 0))
        for (t in 100L..3_000L step 100) {
            assertFalse(gate.offer("A", t))
            assertFalse(gate.offer("B", t))
        }
    }

    @Test fun releaseAllowsImmediateRescan() {
        assertTrue(gate.offer("A", 0))
        gate.release("A")
        assertTrue(gate.offer("A", 100))
    }

    @Test fun resetClearsEverything() {
        assertTrue(gate.offer("A", 0))
        assertTrue(gate.offer("B", 0))
        gate.reset()
        assertTrue(gate.offer("A", 10))
        assertTrue(gate.offer("B", 10))
    }

    @Test fun pruningKeepsRecentEntries() {
        assertTrue(gate.offer("keep", 10_000))
        for (i in 0 until 40) gate.offer("old$i", 0L + i)
        // Pruning runs well after the old ones expired but while "keep" is still recent.
        assertFalse(gate.offer("keep", 11_000))
    }

    @Test fun usesInjectedClock() {
        var now = 0L
        val g = SameCodeGate(2_500) { now }
        assertTrue(g.offer("A"))
        now = 1_000; assertFalse(g.offer("A"))
        now = 4_000; assertTrue(g.offer("A"))
    }
}
