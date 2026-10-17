package app.aaps.fuse.core.insulin

import app.aaps.fuse.core.predictor.InsulinModelProvenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.pow

/**
 * KC2-05, KC2-37, KC2-60, KC2-61 (Kernanteil).
 *
 * Die Referenzformel steht ABSICHTLICH nur im Test: im Produktionscode gibt es
 * keine Kopie der oref-Gleichung, weil sie bei jedem AAPS-Update auseinander-
 * laufen wuerde. Der Test darf sie haben — er prueft damit, dass der Kern
 * exakt das wiedergibt, was ihm gereicht wurde.
 */
class UnitInsulinKernelTest {

    private val dia = 9.0
    private val peak = 75
    private val deliveryTs = 1_700_000_000_000L

    private val model = InsulinModelProvenance(
        insulinType = "OREF_RAPID_ACTING", diaHours = dia, peakMin = peak, codeProvenance = "test",
    )

    /** Wortgleich zu `InsulinOrefBasePlugin.iobCalcForTreatment` — INKLUSIVE der
     *  fehlenden Pruefung auf `t >= 0`. Genau diese Luecke macht C1 noetig. */
    private fun oref(amount: Double, tMin: Double): InsulinSample {
        val td = dia * 60
        val tp = peak.toDouble()
        if (tMin >= td) return InsulinSample(0.0, 0.0)
        val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
        val a = 2 * tau / td
        val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
        val activity = amount * (s / tau.pow(2.0)) * tMin * (1 - tMin / td) * exp(-tMin / tau)
        val iob = amount * (1 - s * (1 - a) * ((tMin.pow(2.0) / (tau * td * (1 - a)) - tMin / tau - 1) * exp(-tMin / tau) + 1))
        return InsulinSample(iob, activity)
    }

    private val sampler = UnitInsulinSampler { doseU, offsetMin -> oref(doseU, offsetMin.toDouble()) }

    private fun kernel(): UnitInsulinKernel {
        val out = UnitInsulinKernelBuilder.build(sampler, deliveryTs, model, "oref-test")
        assertTrue(out is KernelOutcome.Ok, "builder rejected: $out")
        return (out as KernelOutcome.Ok).kernel
    }

    // ---- KC2-60 ----------------------------------------------------------

    @Test
    fun `KC2-60 vor der Lieferung sind IOB und Aktivitaet exakt null`() {
        val k = kernel()
        for (deltaMs in listOf(-1L, -60_000L, -30 * 60_000L, -8 * 3_600_000L)) {
            assertEquals(0.0, k.iobAt(deliveryTs + deltaMs, 1.0), 0.0, "iob at $deltaMs")
            assertEquals(0.0, k.activityAt(deliveryTs + deltaMs, 1.0), 0.0, "activity at $deltaMs")
        }
        assertEquals(1.0, k.iobAt(deliveryTs, 1.0), 1e-12)
    }

    @Test
    fun `KC2-60 das Modell selbst liefert vor der Lieferung eine falsche Richtung`() {
        // Der Beweis, warum die Null im Adapter stehen muss und nicht im Modell:
        // 30 min VOR der Lieferung rechnet die oref-Formel weiter und liefert
        // eine grosse NEGATIVE Aktivitaet. Ueber bgi = -activity*isf waere das
        // ein kraeftiger ANSTIEG - eine Dosis wuerde sich selbst rechtfertigen.
        val raw = oref(1.0, -30.0)
        assertTrue(raw.activityUPerMin < -0.001, "erwartet negative Aktivitaet, war ${raw.activityUPerMin}")
        // und der Kern lehnt genau das ab
        assertEquals(0.0, kernel().activityAt(deliveryTs - 30 * 60_000L, 1.0), 0.0)
    }

    // ---- KC2-61 (Kernanteil) ---------------------------------------------

    @Test
    fun `KC2-61 der Kern gibt an jeder Stuetzstelle exakt den Modellwert wieder`() {
        val k = kernel()
        for (m in 0..model.modelSupportMin) {
            val expected = oref(1.0, m.toDouble())
            assertEquals(expected.iobU, k.iobAt(deliveryTs + m * 60_000L, 1.0), 1e-12, "iob@$m")
            assertEquals(expected.activityUPerMin, k.activityAt(deliveryTs + m * 60_000L, 1.0), 1e-12, "act@$m")
        }
    }

    @Test
    fun `KC2-05 eine Kandidatendosis skaliert linear, sie wird nicht voll angesetzt`() {
        val k = kernel()
        val ts = deliveryTs + 40 * 60_000L
        val single = k.activityAt(ts, 1.0)
        assertEquals(0.35 * single, k.activityAt(ts, 0.35), 1e-15)
        assertEquals(2.0 * single, k.activityAt(ts, 2.0), 1e-15)
        // ... und der Vergleich gegen das Modell mit derselben Dosis
        assertEquals(oref(0.35, 40.0).activityUPerMin, k.activityAt(ts, 0.35), 1e-15)
    }

    @Test
    fun `zwischen zwei Stuetzstellen wird linear interpoliert`() {
        val k = kernel()
        val a = k.iobAt(deliveryTs + 30 * 60_000L, 1.0)
        val b = k.iobAt(deliveryTs + 31 * 60_000L, 1.0)
        val mid = k.iobAt(deliveryTs + 30 * 60_000L + 30_000L, 1.0)
        assertEquals((a + b) / 2.0, mid, 1e-12)
    }

    // ---- KC2-37: Horizontgrenzen -----------------------------------------

    @Test
    fun `KC2-37 der Kern sagt, bis wohin er traegt`() {
        val k = kernel()
        assertEquals(540, k.supportMin)
        assertTrue(k.covers(deliveryTs + 540 * 60_000L))
        assertTrue(!k.covers(deliveryTs + 541 * 60_000L))
        // hinter dem Horizont ist die Wirkung ausgelaufen, nicht abgeschnitten
        assertEquals(0.0, k.iobAt(deliveryTs + 600 * 60_000L, 1.0), 0.0)
    }

    // ---- Fail-closed beim Bau --------------------------------------------

    @Test
    fun `ein nichtlineares Modell wird abgewiesen statt skaliert`() {
        val nonLinear = UnitInsulinSampler { doseU, offsetMin ->
            val base = oref(1.0, offsetMin.toDouble())
            // quadratische Dosisabhaengigkeit
            InsulinSample(base.iobU * doseU * doseU, base.activityUPerMin * doseU * doseU)
        }
        val out = UnitInsulinKernelBuilder.build(nonLinear, deliveryTs, model, "nonlinear")
        assertEquals(KernelReason.NON_LINEAR_MODEL, (out as KernelOutcome.Rejected).reason)
    }

    @Test
    fun `ein Modell mit Restwirkung am Horizontende wird abgewiesen`() {
        val leaking = UnitInsulinSampler { doseU, offsetMin ->
            val base = oref(doseU, offsetMin.toDouble())
            if (offsetMin >= 540) InsulinSample(0.05 * doseU, 0.0) else base
        }
        val out = UnitInsulinKernelBuilder.build(leaking, deliveryTs, model, "leaking")
        assertEquals(KernelReason.TAIL_NOT_EXHAUSTED, (out as KernelOutcome.Rejected).reason)
    }

    @Test
    fun `negative Aktivitaet eines Einzelbolus wird abgewiesen`() {
        val negative = UnitInsulinSampler { doseU, offsetMin ->
            if (offsetMin == 10) InsulinSample(oref(doseU, 10.0).iobU, -0.001)
            else oref(doseU, offsetMin.toDouble())
        }
        val out = UnitInsulinKernelBuilder.build(negative, deliveryTs, model, "negative")
        assertEquals(KernelReason.NEGATIVE_ACTIVITY, (out as KernelOutcome.Rejected).reason)
    }

    @Test
    fun `NaN aus dem Modell erzeugt keine Bahn`() {
        val broken = UnitInsulinSampler { _, offsetMin ->
            if (offsetMin == 3) InsulinSample(Double.NaN, 0.0) else oref(1.0, offsetMin.toDouble())
        }
        val out = UnitInsulinKernelBuilder.build(broken, deliveryTs, model, "broken")
        assertEquals(KernelReason.NON_FINITE_SAMPLE, (out as KernelOutcome.Rejected).reason)
    }

    @Test
    fun `der modelHash trennt verschiedene Modelle und ist stabil`() {
        val a = kernel().modelHash
        val b = kernel().modelHash
        assertEquals(a, b)
        val other = UnitInsulinKernelBuilder.build(
            sampler, deliveryTs, model.copy(peakMin = 55), "oref-test"
        )
        assertNotEquals(a, (other as KernelOutcome.Ok).kernel.modelHash)
        assertEquals(64, a.length)
    }
}
