package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * C4 (Codex-Adjudication bae885f1, "C4 limitation", 22er-Matrix Punkt 13,
 * K4 Punkt 18): HAFTUNG JENSEITS DES BEWERTUNGSFENSTERS.
 *
 * Der Nahzonen-Guard prueft bis `liabilityHorizonMin` (Default 120 min), Tonis
 * Insulin wirkt ueber DIA 9 h. Die zweite Wirkhaelfte jeder Dosis bewertet nur
 * der Schwanz - und der stand auf `INCOMPLETE(1of3,noLedger,noCandidate)`:
 * gerechnet wurde ausschliesslich das sichtbare IOB.
 *
 * Diese Suite bindet die beiden fehlenden Terme:
 *  (a) die publizierte, im IOB noch unsichtbare TRANSPORTMENGE,
 *  (b) die WIRKUNG DER GERADE BESCHLOSSENEN MENGE.
 *
 * Und die Regel aus Codex Abschnitt 10: keine unvollstaendige Komponente darf
 * still wie 0 behandelt werden, wenn 0 weniger konservativ waere.
 */
class TailLiabilityCompletenessTest {

    private fun input(
        lowerBgAtH: Double = 120.0,
        existingIob: Double = 0.5,
        isfTail: Double = 50.0,
        floor: Double = 70.0,
        recovery: Double = 0.0,
        transport: TailLiability.Dose? = null,
        candidate: TailLiability.Dose? = null,
    ) = TailLiability.Input(lowerBgAtH, existingIob, isfTail, floor, recovery, transport, candidate)

    // ---- Vollstaendigkeitsvermerk ----------------------------------------

    /** Der Altstand bleibt exakt der Altstand: ohne beide Terme steht dort
     *  weiter INCOMPLETE(1of3,noLedger,noCandidate). */
    @Test
    fun `ohne Transport- und Kandidatenterm bleibt der alte Vermerk`() {
        assertEquals(TailLiability.COMPLETENESS_STAGE1, TailLiability.evaluate(input()).completeness)
    }

    @Test
    fun `mit beiden Termen ist der Bericht vollstaendig`() {
        val r = TailLiability.evaluate(
            input(transport = TailLiability.Dose(0.2, 0.12), candidate = TailLiability.Dose(0.1, 0.07))
        )
        assertEquals(TailLiability.COMPLETE_3OF3, r.completeness)
        assertTrue(r.usable)
    }

    /** Eine BESCHLOSSENE NULL ist eine Entscheidung, kein fehlender Term - der
     *  Bericht ist damit vollstaendig. */
    @Test
    fun `eine beschlossene Menge von null macht den Bericht vollstaendig`() {
        val r = TailLiability.evaluate(
            input(transport = TailLiability.Dose(0.0, 0.0), candidate = TailLiability.Dose(0.0, 0.0))
        )
        assertEquals(TailLiability.COMPLETE_3OF3, r.completeness)
        // und rechnerisch exakt der alte Bericht
        assertEquals(TailLiability.evaluate(input()).headroomU, r.headroomU, 0.0)
    }

    @Test
    fun `fehlt nur der Kandidat, steht 2 von 3 mit Namen da`() {
        val r = TailLiability.evaluate(input(transport = TailLiability.Dose(0.2, 0.12)))
        assertEquals("INCOMPLETE(2of3,noCandidate)", r.completeness)
    }

    @Test
    fun `fehlt nur der Ledger, steht 2 von 3 mit Namen da`() {
        val r = TailLiability.evaluate(input(candidate = TailLiability.Dose(0.1, 0.07)))
        assertEquals("INCOMPLETE(2of3,noLedger)", r.completeness)
    }

    // ---- Die Terme wirken ------------------------------------------------

    @Test
    fun `die Restwirkung der Transportmenge verkleinert den Spielraum`() {
        val ohne = TailLiability.evaluate(input(transport = TailLiability.Dose(0.0, 0.0)))
        val mit = TailLiability.evaluate(input(transport = TailLiability.Dose(0.2, 0.12)))
        assertEquals(0.12, ohne.headroomU - mit.headroomU, 1e-12)
        assertEquals(0.12, mit.transportLiabilityU, 1e-12)
        // Der Bericht bleibt aufgeschluesselt: IOB, Transport und Kandidat sind
        // drei Groessen, nicht eine Summe ohne Herkunft.
        assertEquals(0.5, mit.existingIobAtHU, 1e-12)
        assertEquals(0.62, mit.existingU, 1e-12)
    }

    @Test
    fun `die Restwirkung der beschlossenen Menge verkleinert den Spielraum`() {
        val ohne = TailLiability.evaluate(input(candidate = TailLiability.Dose(0.0, 0.0)))
        val mit = TailLiability.evaluate(input(candidate = TailLiability.Dose(0.1, 0.07)))
        assertEquals(0.07, ohne.headroomU - mit.headroomU, 1e-12)
        assertEquals(0.07, mit.candidateLiabilityU, 1e-12)
    }

    /** EINSEITIGKEIT: kein Term darf den Spielraum je vergroessern. */
    @Test
    fun `mehr Haftung kann den Spielraum nie vergroessern`() {
        var vorher = TailLiability.evaluate(input(transport = TailLiability.Dose(0.0, 0.0))).headroomU
        for (u in listOf(0.05, 0.1, 0.3, 1.0, 5.0)) {
            val jetzt = TailLiability.evaluate(input(transport = TailLiability.Dose(u, u * 0.6))).headroomU
            assertTrue(jetzt <= vorher + 1e-12) { "transport=$u vergroesserte den Spielraum" }
            vorher = jetzt
        }
    }

    // ---- Keine stille Null (Codex Abschnitt 10) --------------------------

    /** Ohne Einheitskern ist die Restwirkung UNBEKANNT. 0 waere die
     *  optimistische Lesart - gerechnet wird mit der VOLLEN Menge, der oberen
     *  Schranke jeder Restwirkung, und der Vermerk sagt es. */
    @Test
    fun `eine unbekannte Restwirkung zaehlt als volle Menge und wird benannt`() {
        val r = TailLiability.evaluate(input(transport = TailLiability.Dose(0.2, null)))
        assertEquals(0.2, r.transportLiabilityU, 1e-12)
        assertTrue(r.completeness.contains("transportBounded")) { r.completeness }
        // Sie zaehlt trotzdem als vorhandener Term - sie steht in der Gleichung.
        assertTrue(r.completeness.startsWith("INCOMPLETE(2of3")) { r.completeness }
    }

    @Test
    fun `eine unbekannte Kandidaten-Restwirkung zaehlt als volle Menge`() {
        val r = TailLiability.evaluate(
            input(transport = TailLiability.Dose(0.0, 0.0), candidate = TailLiability.Dose(0.1, null))
        )
        assertEquals(0.1, r.candidateLiabilityU, 1e-12)
        assertTrue(r.completeness.contains("candidateBounded")) { r.completeness }
    }

    /** Ein nicht endlicher Term darf den Guard NICHT abschalten (das waere
     *  fail-open) und nicht still zu 0 werden - er sperrt. */
    @Test
    fun `ein nicht endlicher Haftungsterm sperrt, statt den Guard stillzulegen`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val r = TailLiability.evaluate(input(transport = TailLiability.Dose(bad, null)))
            assertTrue(r.usable) { "der Guard darf sich nicht selbst abschalten" }
            assertTrue(r.headroomU < 0.0) { "bad=$bad liess Spielraum uebrig" }
            assertTrue(r.completeness.contains("transportBounded"))
        }
    }

    /** Auch unter dem physiologischen Boden zaehlen beide Terme in die Sperre -
     *  dort ist `budgetU` bedeutungslos, die HAFTUNG aber nicht. */
    @Test
    fun `unter dem physiologischen Boden sperren auch Transport und Kandidat`() {
        val r = TailLiability.evaluate(
            input(
                lowerBgAtH = -31.0, existingIob = 1.0,
                transport = TailLiability.Dose(0.2, 0.12), candidate = TailLiability.Dose(0.1, 0.07),
            )
        )
        assertTrue(r.unphysiological)
        assertFalse(r.budgetMeaningful)
        assertEquals(-(1.0 + 0.12 + 0.07), r.headroomU, 1e-12)
        assertEquals(TailLiability.COMPLETE_3OF3, r.completeness)
    }

    /** Die Bahn MIT beschlossener Menge ist eine andere Zahl als die Baseline -
     *  der Bericht sagt, welche er bekommen hat. */
    @Test
    fun `die Herkunft der Bahn nennt die beschlossene Menge`() {
        assertEquals(TailLiability.SOURCE_BASELINE, TailLiability.evaluate(input()).lowerBgAtHSource)
        assertEquals(
            TailLiability.SOURCE_WITH_CANDIDATE,
            TailLiability.evaluate(input(candidate = TailLiability.Dose(0.1, 0.07))).lowerBgAtHSource,
        )
    }
}
