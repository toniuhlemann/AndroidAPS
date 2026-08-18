package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InterventionRevisionTest {

    private fun pub(smb: Double?, tbr: Boolean?) = InterventionRevision.Published(smb, tbr)

    /** DER RUHIGE ZYKLUS: nichts publiziert, also kein Eingriff. */
    @Test
    fun `ohne publizierte Menge bleibt die Revision stehen`() {
        assertEquals(7L, InterventionRevision.next(7L, pub(0.0, false)))
    }

    @Test
    fun `jede publizierte Menge zaehlt genau einmal`() {
        assertEquals(8L, InterventionRevision.next(7L, pub(0.05, false)), "SMB")
        assertEquals(8L, InterventionRevision.next(7L, pub(0.0, true)), "TBR geaendert")
        assertEquals(8L, InterventionRevision.next(7L, pub(0.05, true)), "beides - trotzdem EIN Schritt")
    }

    /**
     * IM ZWEIFEL WIRD GEZAEHLT.
     *
     * Eine faelschlich angenommene Intervention kostet Nachweis, eine
     * uebersehene erfindet welchen. Deshalb ist jede unklare Lage ein
     * Eingriff - auch NaN, das sonst durch jeden Groessenvergleich faellt.
     */
    @Test
    fun `unbekannte oder unbrauchbare Angaben gelten als Eingriff`() {
        assertEquals(8L, InterventionRevision.next(7L, pub(null, false)), "SMB unbekannt")
        assertEquals(8L, InterventionRevision.next(7L, pub(0.0, null)), "TBR unbekannt")
        assertEquals(8L, InterventionRevision.next(7L, pub(Double.NaN, false)), "NaN")
        assertEquals(8L, InterventionRevision.next(7L, pub(Double.POSITIVE_INFINITY, false)), "Inf")
        assertEquals(8L, InterventionRevision.next(7L, pub(null, null)), "beides unbekannt")
    }

    /**
     * DIE EINZIGE ZUSICHERUNG DIESES BAUSTEINS - ueber ALLE Eingaben.
     *
     * Toni 18.08.: "Die Revision sollte monoton bleiben und bei spaeterem
     * Reject nicht zurueckgedreht werden." Erzwungen ist das nicht durch eine
     * Absichtserklaerung, sondern dadurch, dass es keinen Rueckgabepfad gibt,
     * der kleiner wird. Hier durchgespielt.
     */
    @Test
    fun `die Revision wird unter keiner Eingabe kleiner`() {
        val mengen = listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                            -1.0, -0.0, 0.0, 1e-12, 0.05, 25.0)
        val raten = listOf(null, true, false)
        val staende = listOf(0L, 1L, 42L, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        for (s in staende) for (m in mengen) for (r in raten) {
            val n = InterventionRevision.next(s, pub(m, r))
            assertTrue(n >= s, "next($s, $m, $r) = $n")
            // Ueberlaufsicher formuliert: `s + 1` schlaegt bei Long.MAX_VALUE
            // selbst ins Negative um - die Schranke wuerde dann eine
            // Verletzung melden, die es nicht gibt.
            assertTrue(n - s <= 1L, "hoechstens ein Schritt: next($s, $m, $r) = $n")
        }
    }

    /** Am Anschlag wird nicht ins Negative umgeschlagen. */
    @Test
    fun `am Long-Anschlag bleibt die Revision stehen statt umzuschlagen`() {
        assertEquals(Long.MAX_VALUE, InterventionRevision.next(Long.MAX_VALUE, pub(1.0, true)))
    }

    /**
     * EINE NEGATIVE MENGE IST KEINE ABGABE - aber auch kein Normalfall.
     *
     * Sie kann nur aus einem Fehler stammen; gezaehlt wird sie nicht, weil
     * kein Insulin geflossen ist. Der Test haelt die Entscheidung fest,
     * damit sie nicht unbemerkt kippt.
     */
    @Test
    fun `eine negative Menge zaehlt nicht als Abgabe`() {
        assertEquals(7L, InterventionRevision.next(7L, pub(-1.0, false)))
    }
}
