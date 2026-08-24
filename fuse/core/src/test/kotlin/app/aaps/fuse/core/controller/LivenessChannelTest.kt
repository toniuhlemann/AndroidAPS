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
     * BASIS-RATIO AUS DER RAMPE (Toni 24.08., v27-Korrektur): BEIDE
     * Profile skalieren ueber die R-Rampe - der Unterschied MEAL/
     * CORRECTION ist ausschliesslich der Deckel, den der Runner danach
     * anwendet. Zahlen des Bauauftrags: Korrektur 0,15, Anstieg 0,35,
     * Rampe 1,50 -> 3,00; Tonis Korrektur-Beispiele: r 1,76 -> ~0,185,
     * r 2,69 -> ~0,31 (der K-Deckel 0,20 kappt dann im Runner).
     */
    @Test
    fun `die kanal-basis laeuft in jedem profil die r-rampe`() {
        fun basis(r: Double?) = LivenessChannel.baseRatio(0.15, 0.35, r, 1.50, 3.00)
        assertEquals(0.15, basis(1.50), 1e-9, "untere Kante")
        assertEquals(0.25, basis(2.25), 1e-9, "Mitte")
        assertEquals(0.35, basis(3.00), 1e-9, "obere Kante")
        assertEquals(0.35, basis(4.00), 1e-9, "ueber der Rampe bleibt 0,35")
        // Tonis Korrektur-Beispiele aus dem Live-Trail.
        assertEquals(0.15 + (1.76 - 1.50) / 1.50 * 0.20, basis(1.76), 1e-9)
        assertEquals(0.15 + (2.69 - 1.50) / 1.50 * 0.20, basis(2.69), 1e-9)
    }

    /** Unbrauchbares r oder eine ungueltige Rampe fallen fail-closed auf
     *  die Korrektur-Ratio - keine erfundene Anhebung. */
    @Test
    fun `unbrauchbares r faellt auf die korrektur-ratio`() {
        assertEquals(0.15, LivenessChannel.baseRatio(0.15, 0.35, null, 1.50, 3.00), 1e-9)
        assertEquals(0.15, LivenessChannel.baseRatio(0.15, 0.35, Double.NaN, 1.50, 3.00), 1e-9)
        assertEquals(
            0.15, LivenessChannel.baseRatio(0.15, 0.35, 2.5, 3.00, 1.50), 1e-9,
            "Rampe high <= low ist ungueltig",
        )
    }

    /** EINE Mathematik, zwei Aufrufer: die Kanal-Basis ist exakt die
     *  geteilte Rampe des Normalpfads - jede Duplikation oder Abweichung
     *  faellt hier um. */
    @Test
    fun `die kanal-basis ist exakt die geteilte rampe des normalpfads`() {
        for (r in listOf(0.0, 1.49, 1.51, 2.0, 2.69, 2.99, 3.5)) {
            assertEquals(
                FuseController.rampSmbRatio(0.15, 0.35, r, 1.50, 3.00),
                LivenessChannel.baseRatio(0.15, 0.35, r, 1.50, 3.00),
                1e-12,
            )
        }
    }
}
