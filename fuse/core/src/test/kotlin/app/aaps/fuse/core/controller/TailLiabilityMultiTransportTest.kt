package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * C3-01 (P0, Codex Fix-Pass-5-Closure Abschnitt G.2/K): MEHRERE offene
 * Transport-Posten sind NICHT eine Dosis.
 *
 * Bis Fix-Pass 5 wurden alle offenen Betraege summiert und am AELTESTEN
 * offenen Zeitstempel als EINE Dosis modelliert. Das ist fuer die RESTwirkung
 * nach dem Horizont nicht konservativ: von einer aelteren Dosis ist mehr
 * Wirkung bereits verbraucht, es bleibt also WENIGER Haftung uebrig. Codex'
 * Gegenprobe (linearer 240-min-Testkernel, H = 60 min) steht unten Zahl fuer
 * Zahl.
 *
 * Die geforderte Eigenschaft heisst woertlich "never less conservative than
 * modelling both separately" - genau die prueft [TailLiability.sumOf].
 */
class TailLiabilityMultiTransportTest {

    /** Der lineare Testkernel des Audits: DIA-Traeger 240 min, Restwirkung
     *  faellt linear von der vollen Menge auf 0.
     *
     *  @param ageMin Alter der Dosis JETZT [min], @param hMin Haftungshorizont. */
    private fun linearResidual(amountU: Double, ageMin: Double, hMin: Double): Double =
        amountU * maxOf(0.0, 1.0 - (ageMin + hMin) / 240.0)

    // ---- Codex' numerische Gegenprobe -------------------------------------

    /**
     * P1 = 0,10 U (12 min alt), P2 = 0,30 U (2 min alt), H = 60 min.
     *
     *   getrennt   = 0,10*(1-72/240) + 0,30*(1-62/240) = 0,2925 U
     *   aggregiert = 0,40*(1-72/240)                   = 0,2800 U
     *
     * Die Aggregation am aeltesten Zeitpunkt unterschlaegt 0,0125 U Haftung.
     */
    @Test
    fun `Codex-Gegenprobe - zwei Posten haften zusammen mehr als die Oldest-Aggregation`() {
        val p1 = TailLiability.Dose(0.10, linearResidual(0.10, ageMin = 12.0, hMin = 60.0))
        val p2 = TailLiability.Dose(0.30, linearResidual(0.30, ageMin = 2.0, hMin = 60.0))
        val getrennt = p1.liabilityAtHU + p2.liabilityAtHU
        val aggregiertAmAeltesten = linearResidual(0.40, ageMin = 12.0, hMin = 60.0)

        assertEquals(0.2925, getrennt, 1e-12) { "Codex' Zahl fuer die getrennte Modellierung" }
        assertEquals(0.2800, aggregiertAmAeltesten, 1e-12) { "Codex' Zahl fuer die alte Aggregation" }

        val summe = TailLiability.sumOf(listOf(p1, p2))
        assertTrue(summe.liabilityAtHU >= 0.2925 - 1e-12) {
            "Haftung ${summe.liabilityAtHU} liegt unter der getrennten Modellierung 0,2925"
        }
        assertTrue(summe.liabilityAtHU > aggregiertAmAeltesten) {
            "Die alte Untererfassung von 0,0125 U ist noch da"
        }
        assertEquals(0.40, summe.amountU, 1e-12)
        assertTrue(summe.modelled) { "beide Restwirkungen sind gerechnet - der Vermerk darf nicht bounded sagen" }
    }

    /** DIE PFLICHT-PROPERTY ueber ein Raster: nie weniger konservativ als
     *  beide (bzw. alle) getrennt modelliert. */
    @Test
    fun `Property - die Summe ist nie weniger konservativ als die Einzelmodelle`() {
        val mengen = listOf(0.02, 0.05, 0.10, 0.30, 0.75, 1.50)
        val alter = listOf(0.0, 1.0, 2.0, 7.0, 12.0, 30.0, 120.0, 300.0)
        val horizonte = listOf(30.0, 60.0, 120.0, 240.0)
        for (h in horizonte) for (a1 in alter) for (a2 in alter) for (u1 in mengen) for (u2 in mengen) {
            val d1 = TailLiability.Dose(u1, linearResidual(u1, a1, h))
            val d2 = TailLiability.Dose(u2, linearResidual(u2, a2, h))
            val einzeln = d1.liabilityAtHU + d2.liabilityAtHU
            val summe = TailLiability.sumOf(listOf(d1, d2)).liabilityAtHU
            assertTrue(summe >= einzeln - 1e-12) {
                "h=$h u1=$u1/$a1 u2=$u2/$a2 -> summe=$summe < einzeln=$einzeln"
            }
            // ... und die alte Oldest-Aggregation wird nie unterschritten.
            val oldest = linearResidual(u1 + u2, maxOf(a1, a2), h)
            assertTrue(summe >= oldest - 1e-12) { "h=$h -> summe=$summe < oldest=$oldest" }
        }
    }

    // ---- Vermerk und Rueckfall -------------------------------------------

    /** Ein einziger Posten ohne gerechnete Restwirkung macht die GESAMTE
     *  Transportgroesse `bounded` - der Vermerk darf keine Genauigkeit
     *  behaupten, die nur ein Teil der Posten hat. Die geltende Schranke ist
     *  dann die Gesamtmenge und liegt nie unter der Summe der Einzelhaftungen.
     */
    @Test
    fun `ein Posten ohne Restwirkung macht die Summe bounded und rechnet mit der vollen Menge`() {
        val gerechnet = TailLiability.Dose(0.10, 0.07)
        val unbekannt = TailLiability.Dose(0.30, null)
        val summe = TailLiability.sumOf(listOf(gerechnet, unbekannt))

        assertFalse(summe.modelled) { "eine unbekannte Restwirkung darf nicht als gerechnet gelten" }
        assertEquals(0.40, summe.liabilityAtHU, 1e-12)
        assertTrue(summe.liabilityAtHU >= gerechnet.liabilityAtHU + unbekannt.liabilityAtHU - 1e-12)
        assertTrue(TailLiability.completenessOf(summe, null).contains("transportBounded"))
    }

    /** Kein offener Posten heisst GERECHNETE Null, nicht "unbekannt": der
     *  Vermerk soll `COMPLETE(3of3)` sagen und nicht `transportBounded`. */
    @Test
    fun `keine Posten ergeben eine gerechnete Null`() {
        val summe = TailLiability.sumOf(emptyList())
        assertEquals(0.0, summe.amountU, 0.0)
        assertEquals(0.0, summe.liabilityAtHU, 0.0)
        assertTrue(summe.modelled)
        assertEquals(TailLiability.COMPLETE_3OF3, TailLiability.completenessOf(summe, TailLiability.Dose(0.0, 0.0)))
    }

    /** Ein einzelner Posten ist bitgleich der alte Einzelfall - der Umbau darf
     *  den Normalbetrieb (genau ein offener SMB) nicht verschieben. */
    @Test
    fun `ein einzelner Posten bleibt bitgleich`() {
        val d = TailLiability.Dose(0.25, 0.1875)
        val summe = TailLiability.sumOf(listOf(d))
        assertEquals(d.amountU, summe.amountU, 0.0)
        assertEquals(d.liabilityAtHU, summe.liabilityAtHU, 0.0)
        assertEquals(d.modelled, summe.modelled)
    }

    /** EINSEITIGKEIT: ein zusaetzlicher offener Posten kann die Haftung nur
     *  erhoehen - also den Spielraum nur verkleinern, nie vergroessern. */
    @Test
    fun `ein zusaetzlicher Posten senkt den Spielraum nie weniger`() {
        val basis = listOf(TailLiability.Dose(0.10, 0.07))
        var vorher = TailLiability.sumOf(basis).liabilityAtHU
        val zusatz = mutableListOf<TailLiability.Dose>().apply { addAll(basis) }
        for (u in listOf(0.05, 0.10, 0.20)) {
            zusatz += TailLiability.Dose(u, linearResidual(u, 5.0, 60.0))
            val jetzt = TailLiability.sumOf(zusatz).liabilityAtHU
            assertTrue(jetzt >= vorher - 1e-12) { "u=$u senkte die Haftung" }
            vorher = jetzt
        }
    }
}
