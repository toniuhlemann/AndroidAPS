package app.aaps.fuse.core.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Sichert die GELOCKTEN R(t)-Regeln (Spec v0.5 §1.1 + Addendum A6):
 * rechte Integrationsregel, Theil-Sen-Minima (T26), half-up-Bins (T57).
 * Eine Aenderung, die diese Tests bricht, ist eine Kandidatenaenderung.
 */
class BgiAdjustedSeriesTest {

    private fun s(min: Int, q1: Double, act: Double = 0.0, isf: Double = 90.0) =
        BgiAdjustedSeries.Sample(min * 60_000L, q1, act, isf)

    // --- adjust: rechte Integrationsregel ---

    @Test
    fun `erstes Sample traegt cumulativeBgi 0`() {
        val out = BgiAdjustedSeries.adjust(listOf(s(0, 120.0, act = 0.02)))
        assertEquals(120.0, out.single().adjusted, 1e-9)
    }

    @Test
    fun `rechter Wert - Inkrement nutzt activity des AKTUELLEN Samples`() {
        // t0: act 0.00; t1: act 0.02, isf 90 -> bgiRate(t1) = -1.8 mg/dl/min.
        // RECHTE Regel: Inkrement fuer (t0,t1] = -1.8 * 1 min. adjusted(t1) =
        // q1 - (-1.8) = q1 + 1.8. Die LINKE Regel ergaebe +0.0 — der Test
        // unterscheidet die beiden ausdruecklich.
        val out = BgiAdjustedSeries.adjust(listOf(s(0, 120.0, act = 0.0), s(1, 120.0, act = 0.02)))
        assertEquals(121.8, out[1].adjusted, 1e-9)
    }

    @Test
    fun `konstante Wirkung akkumuliert linear`() {
        // act 0.01, isf 90 -> bgiRate -0.9/min. Nach 10 min: cumBgi = -9.
        // Fallender q1 (-0.9/min) wird dadurch exakt flach: adjusted konstant.
        val seg = (0..10).map { s(it, 120.0 - 0.9 * it, act = 0.01) }
        val out = BgiAdjustedSeries.adjust(seg)
        out.forEach { assertEquals(120.0, it.adjusted, 1e-9) }
    }

    // --- theilSen: Mindestbedingungen (T26) ---

    private fun pts(n: Int, slope: Double, stepMin: Int = 2) =
        (0 until n).map { BgiAdjustedSeries.AdjustedPoint(it * stepMin * 60_000L, 100.0 + slope * it * stepMin) }

    @Test
    fun `4 Punkte - null (unter MIN_POINTS)`() {
        val p = pts(4, 1.0)
        assertNull(BgiAdjustedSeries.theilSen(p, p.last().sourceTs))
    }

    @Test
    fun `5 Punkte mit 2-min-Schritt - genau 10 Paare, gueltig`() {
        val p = pts(5, 1.0)
        val r = BgiAdjustedSeries.theilSen(p, p.last().sourceTs)
        assertNotNull(r)
        assertEquals(1.0, r!!, 1e-9)
    }

    @Test
    fun `6 zulaessige Paare - null (unter MIN_SLOPES)`() {
        // 5 Punkte im 1-min-Raster: nur Paare mit dt>=2 zaehlen -> C(5,2)=10,
        // davon dt>=2min: (0,2)(0,3)(0,4)(1,3)(1,4)(2,4) = 6 < 8 -> null.
        val p = (0 until 5).map { BgiAdjustedSeries.AdjustedPoint(it * 60_000L, 100.0 + it.toDouble()) }
        assertNull(BgiAdjustedSeries.theilSen(p, p.last().sourceTs))
    }

    @Test
    fun `Ausreisser verschiebt den Median nicht`() {
        val p = pts(10, 0.5).toMutableList()
        p[5] = BgiAdjustedSeries.AdjustedPoint(p[5].sourceTs, p[5].adjusted + 40.0)
        val r = BgiAdjustedSeries.theilSen(p, p.last().sourceTs)
        assertNotNull(r)
        assert(abs(r!! - 0.5) < 0.15) { "Theil-Sen nicht robust: $r" }
    }

    @Test
    fun `Fenster - Punkte aelter als 18 min zaehlen nicht`() {
        // 5 alte Punkte weit vor dem Fenster + 4 frische: nur 4 im Fenster -> null
        val old = (0 until 5).map { BgiAdjustedSeries.AdjustedPoint(it * 120_000L, 100.0) }
        val nowTs = 60 * 60_000L
        val fresh = (0 until 4).map { BgiAdjustedSeries.AdjustedPoint(nowTs - it * 120_000L, 100.0) }
        assertNull(BgiAdjustedSeries.theilSen(old + fresh, nowTs))
    }

    // --- minuteBin: half-up (T57) ---

    @Test
    fun `bin - 29,999s bleibt im Bin 0 und 30,000s springt in Bin 1`() {
        assertEquals(0, BgiAdjustedSeries.minuteBin(29_999L, 0L, 20))
        assertEquals(1, BgiAdjustedSeries.minuteBin(30_000L, 0L, 20))
    }

    @Test
    fun `bin - negative Distanz und Ueberlauf sind null`() {
        assertNull(BgiAdjustedSeries.minuteBin(-1L, 0L, 20))
        assertNull(BgiAdjustedSeries.minuteBin(21 * 60_000L, 0L, 20))
        assertEquals(20, BgiAdjustedSeries.minuteBin(20 * 60_000L, 0L, 20))
    }
}
