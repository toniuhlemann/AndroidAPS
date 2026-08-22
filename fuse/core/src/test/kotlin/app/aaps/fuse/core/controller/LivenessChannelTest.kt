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
}
