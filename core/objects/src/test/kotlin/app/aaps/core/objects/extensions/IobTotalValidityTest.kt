package app.aaps.core.objects.extensions

import app.aaps.core.interfaces.aps.IobTotal
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * UNBEKANNT IST NICHT NULL (Codex Combined Closure b6dbb490, P0).
 *
 * `calculateIobFromBolusToTime` gab bei fehlendem Profil ein vollstaendig
 * genulltes, ENDLICHES IobTotal zurueck - und der Aufrufer legte es im
 * iobTable-Cache ab. Ein Verbraucher kann eine erfundene Null nicht von einer
 * echten unterscheiden; jede Pruefung auf dem WERT scheitert daran
 * prinzipiell. Deshalb traegt die Groesse selbst, ob sie eine Aussage ist.
 */
class IobTotalValidityTest {

    @Test
    fun `voreingestellt ist eine Rechnung gueltig - bestehende Pfade bleiben unveraendert`() {
        assertTrue(IobTotal(1_700_000_000_000L).valid)
    }

    @Test
    fun `Ungueltigkeit ist ansteckend`() {
        val gueltig = IobTotal(0L).also { it.iob = 1.0 }
        val unbekannt = IobTotal(0L).also { it.valid = false }
        assertFalse(IobTotal.combine(unbekannt, gueltig).valid) { "Bolusanteil unbekannt" }
        assertFalse(IobTotal.combine(gueltig, unbekannt).valid) { "Basalanteil unbekannt" }
        assertTrue(IobTotal.combine(gueltig, gueltig).valid)
    }

    @Test
    fun `eine erfundene Null ist vom Wert her NICHT erkennbar`() {
        // Der Kern der Sache: die beiden Objekte sind numerisch identisch.
        // Waere die Gueltigkeit nicht mitgefuehrt, koennte kein Verbraucher
        // sie unterscheiden - genau deshalb reicht keine Plausibilitaets-
        // pruefung auf dem Wert.
        val echteNull = IobTotal(0L)
        val erfundeneNull = IobTotal(0L).also { it.valid = false }
        assertTrue(echteNull.iob == erfundeneNull.iob && echteNull.activity == erfundeneNull.activity)
        assertTrue(echteNull.valid && !erfundeneNull.valid)
    }
}
