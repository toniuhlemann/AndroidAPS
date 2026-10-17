package app.aaps.fuse.plugin

import app.aaps.fuse.core.insulin.InsulinSample
import app.aaps.fuse.core.insulin.KernelOutcome
import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.insulin.UnitInsulinKernelBuilder
import app.aaps.fuse.core.insulin.UnitInsulinSampler
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * C3: die synthetische Dosis fuer die publizierte, im IOB noch unsichtbare
 * Transportmenge - DERSELBE Einheitskern wie die Kandidatensuche, nur auf einen
 * FRUEHEREN Lieferzeitpunkt verschoben.
 *
 * Der Kern wird NICHT ein zweites Mal gesampelt (das waeren ~540 Modellabfragen
 * je Zyklus): seine Stuetzstellen sind Offsets ab der Lieferung, die
 * Verschiebung ist deshalb reine Zeitrechnung an der Abfragestelle.
 */
class PendingTransportDoseTest {

    private val t0 = 1_700_000_000_000L
    private val supportMin = 120

    /** Quadratisch abklingendes IOB - bewusst NICHT flach, sonst wuerde die
     *  Verschiebung im Test gar nicht sichtbar. */
    private fun kernel(deliveryTs: Long = t0): UnitInsulinKernel {
        val sampler = UnitInsulinSampler { doseU, offsetMin ->
            if (offsetMin >= supportMin) InsulinSample(0.0, 0.0)
            else {
                val rest = 1.0 - offsetMin / supportMin.toDouble()
                InsulinSample(doseU * rest * rest, doseU * 2.0 * rest / supportMin)
            }
        }
        return (UnitInsulinKernelBuilder.build(
            sampler, deliveryTs, InsulinModelProvenance("TEST", 2.0, 60, "test"), "test"
        ) as KernelOutcome.Ok).kernel
    }

    @Test
    fun `die Wirkung haengt am frueheren Lieferzeitpunkt, nicht am Bau des Kerns`() {
        val k = kernel()
        val frueh = t0 - 30 * 60_000L
        val p = KernelPendingInsulin(k, amountU = 0.2, deliveryTs = frueh)

        // Zum LIEFERZEITPUNKT der Transportmenge gilt der Kernanfang.
        assertEquals(k.activityAt(t0, 0.2), p.activityAt(frueh), 1e-15)
        // 30 min spaeter (= jetzt) ist der Kern bereits 30 min gelaufen.
        assertEquals(k.activityAt(t0 + 30 * 60_000L, 0.2), p.activityAt(t0), 1e-15)
        assertTrue(p.activityAt(t0) < p.activityAt(frueh)) { "die Verschiebung wirkt nicht" }
    }

    @Test
    fun `vor der Lieferung ist die Wirkung exakt null`() {
        val p = KernelPendingInsulin(kernel(), amountU = 0.2, deliveryTs = t0 + 60_000L)
        assertEquals(0.0, p.activityAt(t0), 0.0)
        assertEquals(0.0, p.activityAt(t0 + 59_999L), 0.0)
        assertTrue(p.activityAt(t0 + 60_001L) > 0.0)
    }

    @Test
    fun `die Menge skaliert die Wirkung linear`() {
        val k = kernel()
        val a = KernelPendingInsulin(k, 0.1, t0).activityAt(t0 + 10 * 60_000L)
        val b = KernelPendingInsulin(k, 0.2, t0).activityAt(t0 + 10 * 60_000L)
        assertEquals(2.0 * a, b, 1e-15)
    }

    @Test
    fun `der Traeger verschiebt sich mit`() {
        val frueh = t0 - 30 * 60_000L
        val p = KernelPendingInsulin(kernel(), 0.2, frueh)
        assertTrue(p.covers(frueh + (supportMin - 1) * 60_000L))
        assertFalse(p.covers(frueh + (supportMin + 1) * 60_000L))
    }

    // ---- Der Lieferanker -------------------------------------------------

    /** Ohne offene Zeile ist "jetzt" die ehrliche Antwort - dann ist die Menge
     *  gerade erst publiziert worden. */
    @Test
    fun `ohne offene Zeile ist der Anker der Rechenzeitpunkt`() {
        assertEquals(t0, FuseCycleRunner.transportAnchorTs(null, t0))
    }

    /** Konservativ ist FRUEH: mehr Wirkung faellt ins Bewertungsfenster. */
    @Test
    fun `der aelteste offene Vorschlag ist der Anker`() {
        val frueh = t0 - 5 * 60_000L
        assertEquals(frueh, FuseCycleRunner.transportAnchorTs(frueh, t0))
    }

    /**
     * ABER NICHT BELIEBIG FRUEH: eine Zeile bleibt offen, bis sie bewiesen ist.
     * Ein stundenalter Anker verschoebe den Modelltraeger so weit, dass er das
     * Bewertungsfenster nicht mehr deckt - der Zyklus fiele dann dauerhaft mit
     * PENDING_MODEL_TOO_SHORT aus. Gekappt wird auf das ~Doppelte der
     * gemessenen Maximallatenz (854 s).
     */
    @Test
    fun `ein uralter offener Vorschlag wird auf die Messgrenze gekappt`() {
        val uralt = t0 - 6 * 3600_000L
        assertEquals(
            t0 - FuseCycleRunner.TRANSPORT_ANCHOR_MAX_AGE_MIN * 60_000L,
            FuseCycleRunner.transportAnchorTs(uralt, t0),
        )
    }

    /** Ein Zeitstempel aus der Zukunft ist keine fruehere Lieferung. */
    @Test
    fun `ein Anker aus der Zukunft wird auf jetzt gezogen`() {
        assertEquals(t0, FuseCycleRunner.transportAnchorTs(t0 + 10 * 60_000L, t0))
    }

    /** Die Restwirkung am Haftungshorizont ist der Schwanz-Term (C4a). */
    @Test
    fun `iobAt liefert die Restwirkung der ganzen Menge`() {
        val p = KernelPendingInsulin(kernel(), 0.2, t0)
        assertEquals(0.2, p.iobAt(t0), 1e-12)
        assertEquals(0.2 * 0.25, p.iobAt(t0 + 60 * 60_000L), 1e-12)   // (1-0.5)^2
        assertEquals(0.0, p.iobAt(t0 + 180 * 60_000L), 1e-12)
    }
}
