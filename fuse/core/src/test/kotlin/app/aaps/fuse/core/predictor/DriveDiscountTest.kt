package app.aaps.fuse.core.predictor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class DriveDiscountTest {

    // ---- Die Nacht, die den Abschlag erzwungen hat ------------------------

    /**
     * GEMESSEN 07.08. ~03:00 (fuse_state_history.jsonl): roher Trend ~0,
     * r = +3,2 - davon ~3,0 eigene Insulinaktivitaet. effectiveSmbRatio stand
     * auf 0,35; Folge der Nacht: 108 SMBs / 8,50 U bei q1 83-190.
     * Mit dem Abschlag faellt die UNTERE Bahn auf den sichtbaren Trend zurueck.
     */
    @Test
    fun `die Nachtlage 07-08 wird auf den sichtbaren Trend zurueckgefuehrt`() {
        val d = DriveDiscount.apply(
            meanMgdlPerMin = 3.2, bandLowerMgdlPerMin = 3.2,
            bolusActivityUPerMin = 0.0375, isfMgdlPerU = 80.0, // 3.0 mg/dl/min
            lambda = 1.0,
        )
        assertEquals(3.0, d.termMgdlPerMin, 1e-12)
        assertEquals(3.2 - 3.0, d.lowerAfterMgdlPerMin, 1e-12)
        // Die Mittelbahn kennt dieses Objekt gar nicht - der Abschlag KANN sie
        // nicht anfassen. Der Test dokumentiert das als Vertrag.
    }

    /** Onset aus der Ruhe: keine Bolus-Aktivitaet -> kein Abschlag. Genau dort,
     *  wo FCL vorne reagieren muss, bremst der Abschlag nicht. */
    @Test
    fun `am Onset aus der Ruhe verschwindet der Abschlag`() {
        val d = DriveDiscount.apply(2.79, 2.5, 0.0, 95.0, 1.0)
        assertEquals(0.0, d.termMgdlPerMin, 0.0)
        assertEquals(2.5, d.lowerAfterMgdlPerMin, 0.0)
    }

    // ---- Vertraege --------------------------------------------------------

    @Test
    fun `lambda 0 ist bit-identisch zum Stand ohne Abschlag`() {
        val d = DriveDiscount.apply(3.2, 2.9, 0.05, 80.0, 0.0)
        assertEquals(0.0, d.termMgdlPerMin, 0.0)
        assertEquals(2.9, d.lowerAfterMgdlPerMin, 0.0)
    }

    /** Negative "Aktivitaet" (numerischer Artefakt) darf die Untergrenze nie
     *  ANHEBEN - der Term ist bei 0 geklemmt. */
    @Test
    fun `negative Aktivitaet hebt die Untergrenze nicht an`() {
        val d = DriveDiscount.apply(3.2, 2.9, -0.05, 80.0, 1.0)
        assertEquals(0.0, d.termMgdlPerMin, 0.0)
        assertEquals(2.9, d.lowerAfterMgdlPerMin, 0.0)
    }

    /** EINSEITIGKEIT als Eigenschaft: ueber einen Raster aus Lagen ist die
     *  Untergrenze mit Abschlag NIE oberhalb der Untergrenze ohne. */
    @Test
    fun `der Abschlag kann die Untergrenze nur senken`() {
        var checked = 0
        for (mean in listOf(-3.0, -0.5, 0.0, 0.7, 2.4, 5.2))
            for (bandLower in listOf(mean - 1.2, mean - 0.3, mean))
                for (act in listOf(0.0, 0.004, 0.02, 0.0375, 0.09))
                    for (lambda in listOf(0.0, 0.5, 1.0, 2.0)) {
                        val d = DriveDiscount.apply(mean, bandLower, act, 80.0, lambda)
                        assertTrue(d.lowerAfterMgdlPerMin <= bandLower + 1e-12) {
                            "angehoben bei mean=$mean band=$bandLower act=$act l=$lambda"
                        }
                        assertTrue(d.termMgdlPerMin >= 0.0)
                        checked++
                    }
        assertTrue(checked >= 300)
    }

    @Test
    fun `das Band bleibt bindend wenn es strenger ist als der Abschlag`() {
        // Band schon 1.5 unter mean, Abschlag nur 0.8 -> Band gewinnt.
        val d = DriveDiscount.apply(3.0, 1.5, 0.01, 80.0, 1.0)
        assertEquals(1.5, d.lowerAfterMgdlPerMin, 1e-12)
        assertTrue(abs(d.termMgdlPerMin - 0.8) < 1e-12)
    }

    @Test
    fun `unbrauchbare Eingaben werfen statt still zu verschwinden`() {
        assertThrows<IllegalArgumentException> { DriveDiscount.apply(Double.NaN, 1.0, 0.0, 80.0, 1.0) }
        assertThrows<IllegalArgumentException> { DriveDiscount.apply(1.0, 1.0, Double.NaN, 80.0, 1.0) }
        assertThrows<IllegalArgumentException> { DriveDiscount.apply(1.0, 1.0, 0.0, 0.0, 1.0) }
        assertThrows<IllegalArgumentException> { DriveDiscount.apply(1.0, 1.0, 0.0, 80.0, -0.1) }
    }

    @Test
    fun `die methodId traegt lambda in Prozent`() {
        assertEquals("TS-PS-Q50-W18-DT2-RF1+BD100", DriveDiscount.methodId("TS-PS-Q50-W18-DT2-RF1", 1.0))
        assertEquals("UKF_RATE_RESTRAINT_V1+BD0", DriveDiscount.methodId("UKF_RATE_RESTRAINT_V1", 0.0))
        assertEquals("X+BD50", DriveDiscount.methodId("X", 0.5))
    }
}
