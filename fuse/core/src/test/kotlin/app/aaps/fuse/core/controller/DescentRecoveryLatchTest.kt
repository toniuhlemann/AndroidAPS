package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DescentRecoveryLatchTest {

    private fun step(
        state: DescentRecoveryLatch.State,
        runtime: DescentRecoveryLatch.Runtime = DescentRecoveryLatch.Runtime(),
        risk: Boolean = false,
        healthy: Boolean = true,
        low: Boolean = false,
        rate: Double? = 0.3,
        ts: Long,
    ) = DescentRecoveryLatch.advance(state, runtime, risk, healthy, low, rate, ts)

    @Test
    fun `Risiko schliesst sofort und merkt den ersten Zeitpunkt`() {
        val first = step(DescentRecoveryLatch.State(), risk = true, rate = -1.0, ts = 1_000L)
        val again = step(first.state, risk = true, rate = -2.0, ts = 61_000L)

        assertTrue(first.blocksPositive)
        assertEquals(1_000L, first.state.latchedAtTs)
        assertEquals(first.state, again.state, "ein laufendes Risiko verjuengt den Anker nicht")
    }

    @Test
    fun `drei lueckenlose klare Wendepunkte geben wieder frei`() {
        val latched = DescentRecoveryLatch.State(true, 1_000L)
        val a = step(latched, rate = 0.20, ts = 61_000L)
        val b = step(a.state, a.runtime, rate = 0.35, ts = 121_000L)
        val c = step(b.state, b.runtime, rate = 0.50, ts = 181_000L)

        assertTrue(a.blocksPositive)
        assertTrue(b.blocksPositive)
        assertFalse(c.blocksPositive)
        assertEquals(DescentRecoveryLatch.Reason.RECOVERED, c.reason)
        assertEquals(DescentRecoveryLatch.State(), c.state)
    }

    @Test
    fun `ein flacher Zwischenzyklus beginnt den Nachweis neu`() {
        val latched = DescentRecoveryLatch.State(true, 1_000L)
        val a = step(latched, rate = 0.3, ts = 61_000L)
        val flat = step(a.state, a.runtime, rate = 0.19, ts = 121_000L)
        val b = step(flat.state, flat.runtime, rate = 0.3, ts = 181_000L)

        assertEquals(0, flat.runtime.consecutiveRecoveryCycles)
        assertEquals(1, b.runtime.consecutiveRecoveryCycles)
        assertTrue(b.blocksPositive)
    }

    @Test
    fun `eine Beobachtungsluecke beginnt den Nachweis neu`() {
        val latched = DescentRecoveryLatch.State(true, 1_000L)
        val a = step(latched, rate = 0.3, ts = 61_000L)
        val afterGap = step(a.state, a.runtime, rate = 0.3, ts = 181_001L)

        assertEquals(1, afterGap.runtime.consecutiveRecoveryCycles)
        assertTrue(afterGap.blocksPositive)
    }

    @Test
    fun `Tief und ungesundes Signal koennen nie wieder freigeben`() {
        val latched = DescentRecoveryLatch.State(true, 1_000L)
        val low = step(latched, low = true, rate = 2.0, ts = 61_000L)
        val unhealthy = step(latched, healthy = false, rate = 2.0, ts = 61_000L)

        assertTrue(low.blocksPositive)
        assertEquals(DescentRecoveryLatch.Reason.WAITING_MEASURED_LOW, low.reason)
        assertTrue(unhealthy.blocksPositive)
        assertEquals(DescentRecoveryLatch.Reason.WAITING_UNHEALTHY, unhealthy.reason)
    }

    @Test
    fun `nach bestaetigtem Tief reicht beim Observer-Ausgang eine positive Rate`() {
        val latched = DescentRecoveryLatch.State(true, 1_000L)
        val low = step(latched, low = true, rate = -0.4, ts = 61_000L)

        assertTrue(low.state.sawMeasuredLow, "der restartfeste Zustand muss das echte Tief tragen")
        val clearedByObserver = step(low.state, low.runtime, low = false, rate = 0.25, ts = 121_000L)

        assertFalse(clearedByObserver.blocksPositive)
        assertEquals(DescentRecoveryLatch.Reason.RECOVERED, clearedByObserver.reason)
        assertEquals(0, clearedByObserver.runtime.consecutiveRecoveryCycles, "keine zweite Drei-Zyklen-Wartezeit")
    }

    @Test
    fun `nach Tief bleibt eine flache oder fallende Rate gesperrt`() {
        val afterLow = DescentRecoveryLatch.State(true, 1_000L, sawMeasuredLow = true)
        val flat = step(afterLow, low = false, rate = 0.19, ts = 61_000L)

        assertTrue(flat.blocksPositive)
        assertEquals(DescentRecoveryLatch.Reason.WAITING_RATE, flat.reason)
        assertTrue(flat.state.sawMeasuredLow)
    }

    @Test
    fun `Neustart behaelt den Riegel aber nicht die halbe Erholung`() {
        val latched = DescentRecoveryLatch.State(true, 1_000L)
        val beforeRestart = step(latched, rate = 0.4, ts = 61_000L)
        val restored = DescentRecoveryLatch.State.restore(
            beforeRestart.state.active,
            beforeRestart.state.latchedAtTs,
            beforeRestart.state.sawMeasuredLow,
        )!!
        val afterRestart = step(restored, rate = 0.4, ts = 121_000L)

        assertEquals(1, beforeRestart.runtime.consecutiveRecoveryCycles)
        assertEquals(1, afterRestart.runtime.consecutiveRecoveryCycles)
        assertTrue(afterRestart.blocksPositive)
    }

    @Test
    fun `ungueltige persistierte Kombinationen werden abgewiesen`() {
        assertNull(DescentRecoveryLatch.State.restore(true, 0L))
        assertNull(DescentRecoveryLatch.State.restore(false, 1L))
        assertNull(DescentRecoveryLatch.State.restore(false, 0L, sawMeasuredLow = true))
        assertEquals(DescentRecoveryLatch.State(), DescentRecoveryLatch.State.restore(false, 0L))
    }
}
