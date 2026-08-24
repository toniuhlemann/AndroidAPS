package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Die Kernzusagen des Liveness-Kanals als reine Rechnungen. */
class LivenessChannelTest {

    @Test
    fun `der Kandidat ist der Reglerbedarf mit Ratio und maxSMB - kein Fantasiebedarf`() {
        // (193 - 98) / 72 = 1,319 U; 0,35 x 1,319 = 0,462 -> maxSMB 0,30 bindet.
        assertEquals(0.30, LivenessChannel.candidateU(193.0, 98.0, 72.0, 0.35, 0.30), 1e-9)
        // Kleiner Bedarf: Ratio bindet.
        assertEquals(0.35 * 0.5, LivenessChannel.candidateU(134.0, 98.0, 72.0, 0.35, 0.30), 1e-9)
        // Kein positiver Bedarf -> 0, nie negativ.
        assertEquals(0.0, LivenessChannel.candidateU(90.0, 98.0, 72.0, 0.35, 0.30), 1e-9)
        assertEquals(0.0, LivenessChannel.candidateU(Double.NaN, 98.0, 72.0, 0.35, 0.30), 1e-9)
    }

    /**
     * P0 (Codex): der Kanaldeckel ist EIGEN; die strengste Grenze gewinnt
     * und wird benannt. Global 50% zu setzen wuerde Prime und Fundament
     * aushungern - deshalb existiert dieser Vertrag.
     */
    @Test
    fun `die strengste der drei Grenzen gewinnt und traegt ihren Namen`() {
        // Kanaldeckel 4,0 bindet unter globalem iobTH 8 und maxIOB 8.
        val a = LivenessChannel.headroomU(8.0, 4.0, 8.0, 3.0, 0.2)
        assertEquals(0.8, a.headroomU, 1e-9)
        assertEquals("livenessCap", a.binding)
        // Ist das GLOBALE iobTH niedriger, gewinnt es.
        val b = LivenessChannel.headroomU(3.2, 4.0, 8.0, 3.0, 0.0)
        assertEquals(0.2, b.headroomU, 1e-9)
        assertEquals("globalIobTh", b.binding)
        // Nie negativ; Transporthaftung zaehlt wie belegtes IOB.
        assertEquals(0.0, LivenessChannel.headroomU(8.0, 4.0, 8.0, 3.9, 0.3).headroomU, 1e-9)
    }

    @Test
    fun `Quantisierung ist Raster ohne Carry`() {
        assertEquals(0.25, LivenessChannel.quantize(0.29, 0.05), 1e-9)
        assertEquals(0.0, LivenessChannel.quantize(0.04, 0.05), 1e-9)
        assertEquals(0.30, LivenessChannel.quantize(0.30, 0.05), 1e-9)
    }

    @Test
    fun `final ist max und niemals Addition`() {
        assertEquals(0.30, LivenessChannel.finalU(0.10, 0.30), 1e-9)
        assertEquals(0.10, LivenessChannel.finalU(0.10, 0.05), 1e-9, "offener Normalpfad -> kein Zusatz")
        assertEquals(0.10, LivenessChannel.finalU(0.10, 0.0), 1e-9)
    }

    /**
     * BASIS-RATIO NACH PROFIL (Toni 24.08., Pflichttests 1-4/7/12): das
     * MEAL-Profil traegt die R-Rampe selbst; CORRECTION bleibt bei der
     * Korrektur-Ratio, auch bei starkem r. Zahlen des Bauauftrags:
     * Korrektur 0,15, Anstieg 0,35, Rampe 1,50 -> 3,00.
     */
    @Test
    fun `meal-basis laeuft die r-rampe und correction bleibt bei der korrektur-ratio`() {
        fun meal(r: Double?) = LivenessChannel.baseRatio(true, 0.15, 0.35, r, 1.50, 3.00)
        fun corr(r: Double?) = LivenessChannel.baseRatio(false, 0.15, 0.35, r, 1.50, 3.00)
        assertEquals(0.15, meal(1.50), 1e-9, "Pflichttest 1: untere Kante")
        assertEquals(0.25, meal(2.25), 1e-9, "Pflichttest 2: Mitte")
        assertEquals(0.35, meal(3.00), 1e-9, "Pflichttest 3: obere Kante")
        assertEquals(0.35, meal(4.00), 1e-9, "Pflichttest 4: ueber der Rampe bleibt 0,35")
        // Der gemessene Livefall: r 2,69 -> f ~0,79 -> ~0,31.
        assertEquals(0.15 + (2.69 - 1.50) / 1.50 * 0.20, meal(2.69), 1e-9)
        assertEquals(0.15, corr(3.00), 1e-9, "Pflichttest 7: CORRECTION rampt NIE")
        assertEquals(0.15, corr(2.25), 1e-9)
    }

    /** Pflichttest 12: unbrauchbares r oder eine ungueltige Rampe fallen
     *  fail-closed auf die Korrektur-Ratio - keine erfundene Anhebung. */
    @Test
    fun `unbrauchbares r faellt auch im meal-profil auf die korrektur-ratio`() {
        assertEquals(0.15, LivenessChannel.baseRatio(true, 0.15, 0.35, null, 1.50, 3.00), 1e-9)
        assertEquals(0.15, LivenessChannel.baseRatio(true, 0.15, 0.35, Double.NaN, 1.50, 3.00), 1e-9)
        assertEquals(
            0.15, LivenessChannel.baseRatio(true, 0.15, 0.35, 2.5, 3.00, 1.50), 1e-9,
            "Rampe high <= low ist ungueltig",
        )
    }

    /** EINE Mathematik, zwei Aufrufer: die Kanal-Basis im MEAL-Profil ist
     *  exakt die geteilte Rampe des Normalpfads - jede Duplikation oder
     *  Abweichung faellt hier um. */
    @Test
    fun `die meal-basis ist exakt die geteilte rampe des normalpfads`() {
        for (r in listOf(0.0, 1.49, 1.51, 2.0, 2.69, 2.99, 3.5)) {
            assertEquals(
                FuseController.rampSmbRatio(0.15, 0.35, r, 1.50, 3.00),
                LivenessChannel.baseRatio(true, 0.15, 0.35, r, 1.50, 3.00),
                1e-12,
            )
        }
    }
}
