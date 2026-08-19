package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Nachtfenster + Totband (Tonis Vorschlag 09.08.). */
class NightWindowTest {

    private fun sec(h: Int, m: Int = 0) = (h * 60 + m) * 60

    @Test
    fun `Fenster ueber Mitternacht - 23 bis 07 Uhr`() {
        val start = 23 * 60; val end = 7 * 60
        assertTrue(NightWindow.isNight(sec(23, 0), start, end))
        assertTrue(NightWindow.isNight(sec(2, 30), start, end))
        assertTrue(NightWindow.isNight(sec(6, 59), start, end))
        assertFalse(NightWindow.isNight(sec(7, 0), start, end)) { "07:00 ist bereits Tag" }
        assertFalse(NightWindow.isNight(sec(12, 0), start, end))
        assertFalse(NightWindow.isNight(sec(22, 59), start, end))
    }

    @Test
    fun `Fenster ohne Mitternachtswechsel und AUS-Schaltung`() {
        assertTrue(NightWindow.isNight(sec(13), 12 * 60, 14 * 60))
        assertFalse(NightWindow.isNight(sec(15), 12 * 60, 14 * 60))
        // start == end schaltet ab - sonst waere "immer Nacht" die Folge.
        assertFalse(NightWindow.isNight(sec(3), 60, 60))
        assertFalse(NightWindow.isNight(sec(1), 60, 60))
    }

    @Test
    fun `der groessere Schutzgrund gewinnt`() {
        // Nacht 45 schlaegt Rebound 25.
        assertEquals(45.0, NightWindow.effectiveDeadbandMgdl(true, 25.0, true, 45.0, false, reboundOverrideByEvidence = false, nightOverrideByEvidence = false), 0.0)
        // Rebound allein.
        assertEquals(25.0, NightWindow.effectiveDeadbandMgdl(true, 25.0, false, 45.0, false, reboundOverrideByEvidence = false, nightOverrideByEvidence = false), 0.0)
        // Nacht allein.
        assertEquals(45.0, NightWindow.effectiveDeadbandMgdl(false, 25.0, true, 45.0, false, reboundOverrideByEvidence = false, nightOverrideByEvidence = false), 0.0)
        // Keiner.
        assertEquals(0.0, NightWindow.effectiveDeadbandMgdl(false, 25.0, false, 45.0, false, reboundOverrideByEvidence = false, nightOverrideByEvidence = false), 0.0)
    }

    @Test
    fun `ein erklaerter Marker hebt NUR das Nacht-Totband auf`() {
        // Nachts mit Marker: Nacht-Totband weg...
        assertEquals(0.0, NightWindow.effectiveDeadbandMgdl(false, 25.0, true, 45.0, markerBoost = true, reboundOverrideByEvidence = false, nightOverrideByEvidence = false), 0.0)
        // ...aber das Rebound-Totband bleibt (Hypo-Schutz ist nicht ansagbar).
        assertEquals(25.0, NightWindow.effectiveDeadbandMgdl(true, 25.0, true, 45.0, markerBoost = true, reboundOverrideByEvidence = false, nightOverrideByEvidence = false), 0.0)
    }

    @Test
    fun `abgeschaltete Totbaender liefern null - Einseitigkeit`() {
        assertEquals(0.0, NightWindow.effectiveDeadbandMgdl(true, 0.0, true, 0.0, false, reboundOverrideByEvidence = false, nightOverrideByEvidence = false), 0.0)
        // Negative Eingabe kann nie schuetzen, aber auch nie oeffnen.
        assertEquals(0.0, NightWindow.effectiveDeadbandMgdl(false, 0.0, true, -10.0, false, reboundOverrideByEvidence = false, nightOverrideByEvidence = false), 0.0)
    }

    /** Der gemessene Anlass: 09.08. 05:25-06:24, BG 89-116 bei Ziel 98.
     *  Mit 45er Totband liegt die Schwelle bei 143 - alle Abgaben faellig aus. */
    @Test
    fun `der gemessene Nachtfall waere gesperrt gewesen`() {
        val target = 98.0
        val schwelle = target + NightWindow.effectiveDeadbandMgdl(false, 25.0, true, 45.0, false, reboundOverrideByEvidence = false, nightOverrideByEvidence = false)
        assertEquals(143.0, schwelle, 0.0)
        for (bg in listOf(89.0, 97.0, 103.0, 109.0, 116.0)) assertTrue(bg < schwelle)
    }
    // ---- Evidenzkredit entwaffnet die Totbaender (Toni 15.08.) ------------

    /**
     * "REBOUND-TOTFENSTER DARF DAS MAHLZEITENFENSTER NIEMALS BLOCKEN."
     *
     * Der 2-Tage-Lauf hat die Luecke belegt: nach Ablauf der
     * Marker-Sonderrechte (45/90 min) blockten beide Totbaender Zyklen, in
     * denen die Evidenz-Episode ACTIVE war und Kredit auswies. Der Kredit
     * existiert nur in einer markereroeffneten Episode fuer gemessene,
     * versiegelte, unbezahlte Stoerung - das Gegenteil der Lage, fuer die die
     * Totbaender gebaut sind.
     */
    @Test
    fun `Evidenzkredit entwaffnet beide Totbaender`() {
        assertEquals(
            0.0,
            NightWindow.effectiveDeadbandMgdl(true, 25.0, true, 45.0, markerBoost = false, reboundOverrideByEvidence = true, nightOverrideByEvidence = true),
            0.0,
        )
    }

    /** Und zwar auch das REBOUND-Totband allein - das der Marker-Boost
     *  ausdruecklich NICHT entwaffnet. */
    @Test
    fun `Evidenzkredit entwaffnet auch das Rebound-Totband`() {
        assertEquals(
            0.0,
            NightWindow.effectiveDeadbandMgdl(true, 25.0, false, 0.0, markerBoost = false, reboundOverrideByEvidence = true, nightOverrideByEvidence = true),
            0.0,
        )
    }

    /** GEGENPROBE: ohne Kredit bleibt alles scharf - DORMANT, SUSPENDED,
     *  PENDING_SEAL und Widerruf liefern Kredit 0 und damit false. */
    @Test
    fun `ohne Kredit bleiben die Totbaender scharf`() {
        assertEquals(
            45.0,
            NightWindow.effectiveDeadbandMgdl(true, 25.0, true, 45.0, markerBoost = false, reboundOverrideByEvidence = false, nightOverrideByEvidence = false),
            0.0,
        )
    }
}
