package app.aaps.fuse.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.iobCalc
import app.aaps.fuse.core.insulin.KernelOutcome
import app.aaps.fuse.core.insulin.UnitInsulinKernelBuilder
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * KC2-61: der Einheitskern deckt sich an ALLEN Stuetzstellen mit dem Bolus-IOB von AAPS — geprueft
 * gegen den ECHTEN Rechenweg, nicht gegen eine Kopie. v4 hat kein Insulin-Plugin mehr: die Kurve ist
 * eine [ICfg] am Bolus, und der Bolus-IOB rechnet ueber `BS.iobCalc` (FUSE-Vertrag 3).
 *
 * Und KC2-60 von der anderen Seite: hier ist der Beweis, dass `iobCalcForTreatment` vor der Lieferung
 * KEINE Null liefert, sondern eine falsch gerichtete Wirkung. Deshalb setzt der pure Kern sie selbst.
 */
class AapsUnitInsulinSamplerTest {

    private val dia = 9.0
    private val peak = 75
    private val iCfg = ICfg("Test", peak = peak, dia = dia, concentration = 1.0)
    private val deliveryTs = 1_700_000_000_000L

    private fun sampler(ts: Long = deliveryTs) = AapsUnitInsulinSampler(iCfg, ts)
    private fun model() = InsulinModelProvenance("ICFG:test", iCfg.dia, iCfg.peak, FuseCycleRunner.INSULIN_SOURCE)
    private fun unitBolus() = BS(timestamp = deliveryTs, amount = 1.0, type = BS.Type.SMB, iCfg = iCfg)

    @Test
    fun `KC2-61 der Kern gibt an jeder Stuetzstelle den Bolus-IOB von AAPS wieder`() {
        val out = UnitInsulinKernelBuilder.build(sampler(), deliveryTs, model(), FuseCycleRunner.INSULIN_SOURCE)
        assertTrue(out is KernelOutcome.Ok, "builder rejected: $out")
        val kernel = (out as KernelOutcome.Ok).kernel

        assertEquals(540, kernel.supportMin)
        for (m in 0..540) {
            val direct = unitBolus().iobCalc(deliveryTs + m * 60_000L)
            assertEquals(direct.iobContrib, kernel.iobAt(deliveryTs + m * 60_000L, 1.0), 1e-12, "iob@$m")
            assertEquals(direct.activityContrib, kernel.activityAt(deliveryTs + m * 60_000L, 1.0), 1e-12, "activity@$m")
        }

        // Plausibilitaet des echten Modells: voll bei 0, leer am DIA-Ende,
        // Aktivitaetsmaximum um den Peak.
        assertEquals(1.0, kernel.iobAt(deliveryTs, 1.0), 1e-9)
        assertEquals(0.0, kernel.iobAt(deliveryTs + 540 * 60_000L, 1.0), 0.0)
        val peakMinute = (0..540).maxByOrNull { kernel.activityAt(deliveryTs + it * 60_000L, 1.0) }!!
        assertEquals(peak, peakMinute)
    }

    @Test
    fun `KC2-60 der Bolus-IOB liefert VOR der Lieferung eine falsch gerichtete Wirkung`() {
        // 30 min vor der Abgabe: die oref-Formel prueft nur `t < td`, nicht
        // `t >= 0`. Ergebnis ist eine grosse NEGATIVE Aktivitaet -> ueber
        // bgi = -activity*isf ein kraeftiger ANSTIEG. Eine Kandidatendosis
        // wuerde die Bahn anheben, bevor sie geliefert ist.
        val before = unitBolus().iobCalc(deliveryTs - 30 * 60_000L)
        assertTrue(before.activityContrib < -0.001, "erwartet negative Aktivitaet, war ${before.activityContrib}")

        // GEMESSEN, nicht angenommen: auch die IOB-Seite ist vor der Lieferung
        // falsch - das Modell meldet einen Phantom-IOB, als waere schon Insulin
        // an Bord. Das ist schlicht keine Aussage ueber die Wirklichkeit.
        assertTrue(before.iobContrib > 0.5, "erwartet Phantom-IOB vor der Lieferung, war ${before.iobContrib}")
        assertTrue(before.iobContrib < 1.0)

        // Der Kern setzt die Null selbst - und fragt gar nicht erst zurueck.
        val kernel = (UnitInsulinKernelBuilder.build(sampler(), deliveryTs, model(), "x") as KernelOutcome.Ok).kernel
        assertEquals(0.0, kernel.activityAt(deliveryTs - 30 * 60_000L, 1.0), 0.0)
        assertEquals(0.0, kernel.iobAt(deliveryTs - 30 * 60_000L, 1.0), 0.0)
    }

    @Test
    fun `der Sampler fragt niemals vor der Lieferung an`() {
        val ex = runCatching { sampler().sampleAfterDelivery(1.0, -1) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "erwartet IllegalArgumentException, war $ex")
    }

    @Test
    fun `ein Platzhalter-Insulin ist kein Insulin - der Sampler verweigert`() {
        // Die DB-Migration v33 stempelt alte Zeilen mit -1; daraus wuerde eine geklemmte Unsinnskurve.
        val sentinel = ICfg("legacy", insulinEndTime = -1L, insulinPeakTime = -1L)
        val ex = runCatching { AapsUnitInsulinSampler(sentinel, deliveryTs) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "erwartet IllegalArgumentException, war $ex")
    }

    @Test
    fun `die Dosis skaliert linear - das ist die Voraussetzung des Einheitskerns`() {
        val s = sampler()
        for (m in listOf(0, 10, 75, 200, 539)) {
            val one = s.sampleAfterDelivery(1.0, m)
            val quarter = s.sampleAfterDelivery(0.25, m)
            assertEquals(0.25 * one.iobU, quarter.iobU, 1e-15, "iob@$m")
            assertEquals(0.25 * one.activityUPerMin, quarter.activityUPerMin, 1e-15, "activity@$m")
        }
    }
}
