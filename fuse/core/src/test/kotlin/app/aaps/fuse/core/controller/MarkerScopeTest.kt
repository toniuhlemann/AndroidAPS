package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkerScopeTest {

    private val m = 60_000L
    private val t0 = 1_786_000_000_000L

    @Test
    fun `sonderrechte gelten ab druck bis 45 min`() {
        assertTrue(MarkerScope.boostActive(t0, t0, 0L))
        assertTrue(MarkerScope.boostActive(t0, t0 + 44 * m, 0L))
        assertTrue(MarkerScope.boostActive(t0, t0 + 45 * m, 0L))
        assertFalse(MarkerScope.boostActive(t0, t0 + 45 * m + 1, 0L))
        // Der Fruehstuecks-Fall: Sturz ~75 min nach Druck lag im alten
        // 90-min-Fenster - jetzt nicht mehr.
        assertFalse(MarkerScope.boostActive(t0, t0 + 75 * m, 0L))
    }

    @Test
    fun `nachhaltige wende beendet die sonderrechte sofort`() {
        val turn = t0 + 20 * m
        assertTrue(MarkerScope.boostActive(t0, t0 + 19 * m, 0L))
        assertFalse(MarkerScope.boostActive(t0, turn, turn))
        assertFalse(MarkerScope.boostActive(t0, t0 + 21 * m, turn))
    }

    @Test
    fun `kein marker oder uhrsprung heisst keine sonderrechte`() {
        assertFalse(MarkerScope.boostActive(0L, t0, 0L))
        assertFalse(MarkerScope.boostActive(t0 + m, t0, 0L)) // Druck "in der Zukunft"
    }

    @Test
    fun `prior-hub am horizont - entzirkularisierung`() {
        // 0,7 mg/dl/min, tau 60, H 30: 0,7*60*(1-e^-0,5) ~ 16,5 mg/dl.
        assertEquals(16.5, MarkerScope.priorLiftAtHorizonMgdl(0.7, 60.0, 30), 0.1)
        assertEquals(0.0, MarkerScope.priorLiftAtHorizonMgdl(0.0, 60.0, 30), 0.0)
        assertEquals(0.0, MarkerScope.priorLiftAtHorizonMgdl(0.7, 60.0, 0), 0.0)
        // Monotonie: laengerer Horizont, groesserer Hub, gedeckelt bei prior*tau.
        val h30 = MarkerScope.priorLiftAtHorizonMgdl(0.7, 60.0, 30)
        val h120 = MarkerScope.priorLiftAtHorizonMgdl(0.7, 60.0, 120)
        assertTrue(h120 > h30 && h120 < 0.7 * 60.0)
    }
}
