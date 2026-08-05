package app.aaps.fuse.plugin

import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.VirtualPump
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * Der Riegel ist sicherheitskritisch und winzig — genau die Kombination, bei
 * der ein Test spaeter den Unterschied macht.
 */
class FusePumpGateTest {

    private interface FakeVirtual : Pump, VirtualPump

    @Test
    fun `VirtualPump ist erlaubt`() {
        val r = FusePumpGate.evaluate(mock(FakeVirtual::class.java))
        assertTrue(r.allowed)
        assertEquals("VPUMP_ALPHA", r.reason)
    }

    @Test
    fun `eine echte Pumpe blockiert`() {
        val r = FusePumpGate.evaluate(mock(Pump::class.java))
        assertFalse(r.allowed)
        assertEquals(FusePumpGate.Verdict.BLOCKED_REAL_PUMP, r.verdict)
        assertTrue(r.reason.startsWith("BLOCKED"))
    }

    /** Keine identifizierbare Pumpe ist NICHT "vermutlich virtuell" — im
     *  Zweifel wird blockiert. */
    @Test
    fun `fehlende Pumpe blockiert ebenfalls`() {
        val r = FusePumpGate.evaluate(null)
        assertFalse(r.allowed)
        assertEquals(FusePumpGate.Verdict.BLOCKED_UNKNOWN_PUMP, r.verdict)
    }

    /** Der Grund muss immer sichtbar sein — ein blockiertes FUSE darf nicht
     *  wie ein defektes FUSE aussehen. */
    @Test
    fun `jeder Ausgang nennt einen Grund`() {
        listOf(
            FusePumpGate.evaluate(null),
            FusePumpGate.evaluate(mock(Pump::class.java)),
            FusePumpGate.evaluate(mock(FakeVirtual::class.java)),
        ).forEach { assertTrue(it.reason.isNotBlank()) }
    }
}
