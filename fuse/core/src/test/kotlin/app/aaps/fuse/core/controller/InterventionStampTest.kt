package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InterventionStampTest {

    private val EPO = "epoche-A"
    private fun st(seq: Long, epo: String = EPO) = InterventionStamp(epo, seq)
    private fun pub(smb: Double?, tbr: Boolean?) = InterventionStamp.Published(smb, tbr)
    private fun next(s: InterventionStamp, smb: Double?, tbr: Boolean?) =
        InterventionStamp.next(s, pub(smb, tbr))

    // ---- Die Sequenz innerhalb der Epoche -------------------------------

    /** DER RUHIGE ZYKLUS: nichts publiziert, also kein Eingriff. */
    @Test
    fun `ohne publizierte Menge bleibt der Stempel stehen`() {
        assertEquals(st(7L), next(st(7L), 0.0, false))
    }

    @Test
    fun `jede publizierte Menge zaehlt genau einmal`() {
        assertEquals(st(8L), next(st(7L), 0.05, false), "SMB")
        assertEquals(st(8L), next(st(7L), 0.0, true), "TBR tatsaechlich geaendert")
        assertEquals(st(8L), next(st(7L), 0.05, true), "beides - trotzdem EIN Schritt")
    }

    /** DIE EPOCHE BLEIBT. Nur die Persistenzschicht darf sie wechseln - ein
     *  Zaehlschritt ist kein Bruch im Lauf. */
    @Test
    fun `next wechselt niemals die Epoche`() {
        assertEquals(EPO, next(st(7L), 1.0, true).epochId)
        assertEquals(EPO, next(st(7L), 0.0, false).epochId)
        assertEquals("andere", next(st(7L, "andere"), 1.0, true).epochId)
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
        assertEquals(st(8L), next(st(7L), null, false), "SMB unbekannt")
        assertEquals(st(8L), next(st(7L), 0.0, null), "TBR unbekannt")
        assertEquals(st(8L), next(st(7L), Double.NaN, false), "NaN")
        assertEquals(st(8L), next(st(7L), Double.POSITIVE_INFINITY, false), "Inf")
        assertEquals(st(8L), next(st(7L), null, null), "beides unbekannt")
    }

    /**
     * DIE EINZIGE ZUSICHERUNG DIESES BAUSTEINS - ueber ALLE Eingaben.
     *
     * Toni 18.08.: "Innerhalb einer gueltigen Epoche steigt sequence monoton."
     * Erzwungen ist das nicht durch eine Absichtserklaerung, sondern dadurch,
     * dass es keinen Rueckgabepfad gibt, der kleiner wird. Hier durchgespielt.
     */
    @Test
    fun `die Sequenz wird unter keiner Eingabe kleiner`() {
        val mengen = listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                            -1.0, -0.0, 0.0, 1e-12, 0.05, 25.0)
        val raten = listOf(null, true, false)
        val staende = listOf(0L, 1L, 42L, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        for (q in staende) for (m in mengen) for (r in raten) {
            val n = next(st(q), m, r)
            assertTrue(n.sequence >= q, "next($q, $m, $r) = $n")
            // Ueberlaufsicher: `q + 1` schlaegt bei Long.MAX_VALUE selbst ins
            // Negative um und meldete eine Verletzung, die es nicht gibt.
            assertTrue(n.sequence - q <= 1L, "hoechstens ein Schritt: next($q, $m, $r) = $n")
            assertEquals(EPO, n.epochId, "die Epoche bleibt: next($q, $m, $r)")
        }
    }

    @Test
    fun `am Long-Anschlag bleibt die Sequenz stehen statt umzuschlagen`() {
        assertEquals(st(Long.MAX_VALUE), next(st(Long.MAX_VALUE), 1.0, true))
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
        assertEquals(st(7L), next(st(7L), -1.0, false))
    }

    // ---- Der Vergleich: beide Felder oder nichts ------------------------

    @Test
    fun `gleiche Epoche und gleiche Sequenz sind gleich`() {
        assertTrue(InterventionStamp.same(st(5L), st(5L)))
    }

    /**
     * DER KERN DER GANZEN BAUFORM (Toni 18.08.).
     *
     * Nach Reparatur, Quarantaene oder Rollback beginnt die Sequenz neu und
     * durchlaeuft ihre alten Werte ein zweites Mal. Wuerde nur sie verglichen,
     * saehe ein Eingriff irgendwann aus wie keiner - und aus einer Strecke mit
     * Insulin entstuende lambda-Evidenz. Die Epoche verhindert das ohne jede
     * Rekonstruktion aus Spuren.
     */
    @Test
    fun `gleiche Sequenz aus anderer Epoche ist NICHT gleich`() {
        assertFalse(InterventionStamp.same(st(5L, "epoche-A"), st(5L, "epoche-B")))
        assertFalse(InterventionStamp.same(st(0L, "vor-reparatur"), st(0L, "nach-reparatur")))
    }

    @Test
    fun `gleiche Epoche mit anderer Sequenz ist nicht gleich`() {
        assertFalse(InterventionStamp.same(st(5L), st(6L)))
    }

    /**
     * ZWEI UNBEKANNTE HERKUENFTE SIND KEIN BELEG.
     *
     * Ein leerer Epochenname waere eine Wildcard, die jede fremde Epoche
     * traefe - genau der Freibrief, den der Ledger schon einmal beim leeren
     * Pumpen-Serial hatte (Live-Befund 09.08.). Deshalb ist ein ungueltiger
     * Stempel mit KEINEM gleich, auch nicht mit sich selbst.
     */
    @Test
    fun `ein ungueltiger Stempel ist mit keinem gleich`() {
        val leer = InterventionStamp("", 5L)
        val leer2 = InterventionStamp("", 5L)
        assertFalse(leer.valid)
        assertFalse(InterventionStamp.same(leer, leer2), "zwei leere Epochen sind kein Beleg")
        assertFalse(InterventionStamp.same(leer, st(5L)))
        assertFalse(InterventionStamp.same(st(5L), leer))
        assertFalse(InterventionStamp.same(InterventionStamp("  ", 5L), InterventionStamp("  ", 5L)), "nur Leerraum")
        assertFalse(InterventionStamp.same(InterventionStamp(EPO, -1L), InterventionStamp(EPO, -1L)), "negative Sequenz")
    }

    @Test
    fun `null ist mit nichts gleich`() {
        assertFalse(InterventionStamp.same(null, st(5L)))
        assertFalse(InterventionStamp.same(st(5L), null))
        assertFalse(InterventionStamp.same(null, null))
    }
}
