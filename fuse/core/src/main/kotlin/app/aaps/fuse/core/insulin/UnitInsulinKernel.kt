package app.aaps.fuse.core.insulin

import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.fuse.core.util.Sha
import kotlin.math.abs

/**
 * Was EINE Einheit Insulin tut — GESAMPELT aus dem aktiven AAPS-Insulinmodell,
 * nicht nachgebaut (K2-C v0.3.1 §C1.1, R77-F5).
 *
 * Warum sampeln statt rechnen: Eine eigene Kopie der oref-Formel liefe bei
 * jedem AAPS-Update lautlos auseinander. FUSE wuerde dann mit einem anderen
 * Insulinmodell dosieren als der Loop bilanziert — und der Unterschied waere
 * nirgends sichtbar.
 *
 * DIE NULLREGEL VOR DER LIEFERUNG IST KEINE FORMSACHE. Verifiziert in
 * `InsulinOrefBasePlugin.kt:85`:
 *
 *     if (t < td) { ... }        // KEINE Pruefung auf t >= 0
 *
 * Fuer t < 0 rechnet die Formel weiter: `t` ist negativ, `(1 - t/td) > 1` und
 * `exp(-t/tau)` waechst exponentiell — es entsteht eine grosse NEGATIVE
 * Aktivitaet und ueber `bgi = -activity * isf` eine grosse POSITIVE BGI-Rate.
 * Eine Kandidatendosis wuerde die Bahn ANHEBEN, bevor sie geliefert ist, und
 * damit noch mehr Insulin rechtfertigen. Deshalb setzt der Kern die Null
 * selbst; er ueberlaesst sie NICHT dem Modell.
 */

/** Eine Stuetzstelle des Einheitskerns. Beides bezogen auf 1 U. */
data class KernelPoint(val offsetMin: Int, val iobPerU: Double, val activityPerUPerMin: Double)

/** Rohwert des aktiven Insulinmodells. */
data class InsulinSample(val iobU: Double, val activityUPerMin: Double)

/**
 * Die einzige Naht zwischen purem Kern und AAPS. Der Android-Adapter
 * implementiert sie ueber `activePlugin.activeInsulin.iobCalcForTreatment(...)`
 * mit einem virtuellen Bolus; alles andere bleibt auf der JVM replaybar.
 *
 * [offsetMin] ist NIE negativ — der Kern fragt vor der Lieferung nicht an.
 */
fun interface UnitInsulinSampler {

    fun sampleAfterDelivery(doseU: Double, offsetMin: Int): InsulinSample
}

enum class KernelReason {
    NON_FINITE_SAMPLE,   // NaN/Infinity aus dem Modell
    NON_LINEAR_MODEL,    // 2 U ergibt nicht das Doppelte von 1 U -> Skalierung waere falsch
    NEGATIVE_ACTIVITY,   // ein einzelner Bolus kann nicht negativ wirken
    IOB_OUT_OF_RANGE,    // IOB je Einheit ausserhalb [0, 1]
    TAIL_NOT_EXHAUSTED,  // am Ende des Modellhorizonts steckt noch IOB -> Abschneiden verliert Insulin
    EMPTY_SUPPORT,       // Modellhorizont <= 0
}

sealed interface KernelOutcome {
    data class Ok(val kernel: UnitInsulinKernel) : KernelOutcome
    data class Rejected(val reason: KernelReason, val detail: String) : KernelOutcome
}

class UnitInsulinKernel internal constructor(
    val deliveryTs: Long,
    val points: List<KernelPoint>,
    val model: InsulinModelProvenance,
    val insulinPluginId: String,
) {

    /** Letzte Stuetzstelle in Minuten nach der Lieferung. */
    val supportMin: Int get() = points.last().offsetMin

    /** Erster Zeitpunkt, den der Kern NICHT mehr traegt. */
    val supportEndTs: Long get() = deliveryTs + supportMin * 60_000L

    val modelHash: String by lazy {
        val sb = StringBuilder(CANONICALIZATION_ID).append('|')
        sb.append(insulinPluginId).append('|')
            .append(model.insulinType).append(':')
            .append(Sha.lossless(model.diaHours)).append(':')
            .append(model.peakMin).append(':')
            .append(model.codeProvenance).append('|')
        for (p in points) {
            sb.append(p.offsetMin).append(',')
                .append(Sha.lossless(p.iobPerU)).append(',')
                .append(Sha.lossless(p.activityPerUPerMin)).append(';')
        }
        Sha.of(sb.toString())
    }

    /** Deckt der Kern [tsMs] noch ab? Ein "nein" ist ein Grund zum Fail-closed,
     *  kein Grund fuer eine Null (die waere eine stille Untertreibung der Wirkung). */
    fun covers(tsMs: Long): Boolean = tsMs <= supportEndTs

    /** IOB einer Dosis [doseU] zum Zeitpunkt [tsMs]. Vor der Lieferung exakt 0. */
    fun iobAt(tsMs: Long, doseU: Double): Double = doseU * interpolate(tsMs) { it.iobPerU }

    /** Aktivitaet (U/min) einer Dosis [doseU] zum Zeitpunkt [tsMs]. Vor der Lieferung exakt 0. */
    fun activityAt(tsMs: Long, doseU: Double): Double = doseU * interpolate(tsMs) { it.activityPerUPerMin }

    private inline fun interpolate(tsMs: Long, field: (KernelPoint) -> Double): Double {
        // C1: die Null vor der Lieferung steht HIER, nicht im Modell.
        if (tsMs < deliveryTs) return 0.0
        if (tsMs >= supportEndTs) return 0.0
        val offset = (tsMs - deliveryTs) / 60_000.0
        val lo = offset.toInt()
        val frac = offset - lo
        val a = field(points[lo])
        if (frac == 0.0) return a
        val b = field(points[lo + 1])
        return a + (b - a) * frac
    }

    companion object {

        const val CANONICALIZATION_ID = "fuse-k2c-unitkernel-v1-lossless"
    }
}

object UnitInsulinKernelBuilder {

    /** Relative Toleranz der Linearitaetsprobe. Die oref-Formel traegt `amount`
     *  als exakten Linearfaktor; alles darueber waere ein anderes Modell. */
    const val LINEARITY_REL_EPS = 1e-9
    private const val ABS_EPS = 1e-12

    /**
     * Sampelt den Einheitskern auf dem MINUTENRASTER von der Lieferung bis zum
     * Ende des Modellhorizonts.
     *
     * Die Linearitaetsprobe ist kein Zierrat: Der ganze Kern lebt davon, dass
     * `doseU * kernel` dasselbe ist wie das Modell mit `doseU`. Fuer die
     * oref-Modelle ist das exakt so — fuer ein kuenftiges Modell nicht
     * zwangslaeufig, und ein stiller Skalierungsfehler waere eine Fehldosis.
     */
    fun build(
        sampler: UnitInsulinSampler,
        deliveryTs: Long,
        model: InsulinModelProvenance,
        insulinPluginId: String,
        linearityProbeOffsets: List<Int> = listOf(0, 5, 15, 30, 60, 120),
    ): KernelOutcome {
        val support = model.modelSupportMin
        if (support <= 0) return KernelOutcome.Rejected(KernelReason.EMPTY_SUPPORT, "modelSupportMin=$support")

        val points = ArrayList<KernelPoint>(support + 1)
        for (m in 0..support) {
            val s = sampler.sampleAfterDelivery(1.0, m)
            if (!s.iobU.isFinite() || !s.activityUPerMin.isFinite())
                return KernelOutcome.Rejected(KernelReason.NON_FINITE_SAMPLE, "offsetMin=$m -> $s")
            if (s.activityUPerMin < -ABS_EPS)
                return KernelOutcome.Rejected(KernelReason.NEGATIVE_ACTIVITY, "offsetMin=$m activity=${s.activityUPerMin}")
            if (s.iobU < -ABS_EPS || s.iobU > 1.0 + 1e-9)
                return KernelOutcome.Rejected(KernelReason.IOB_OUT_OF_RANGE, "offsetMin=$m iob=${s.iobU}")
            points.add(KernelPoint(m, s.iobU, s.activityUPerMin))
        }

        // Der Schwanz muss ausgelaufen sein. Sonst wuerde das Abschneiden am
        // Horizont Insulin verschwinden lassen, das in Wahrheit noch wirkt.
        val tail = points.last()
        if (abs(tail.iobPerU) > 1e-9 || abs(tail.activityPerUPerMin) > 1e-9)
            return KernelOutcome.Rejected(
                KernelReason.TAIL_NOT_EXHAUSTED,
                "offsetMin=${tail.offsetMin} iob=${tail.iobPerU} activity=${tail.activityPerUPerMin}"
            )

        for (m in linearityProbeOffsets) {
            if (m > support) continue
            val one = points[m]
            val two = sampler.sampleAfterDelivery(2.0, m)
            if (!two.iobU.isFinite() || !two.activityUPerMin.isFinite())
                return KernelOutcome.Rejected(KernelReason.NON_FINITE_SAMPLE, "offsetMin=$m doseU=2 -> $two")
            if (!nearlyProportional(two.iobU, one.iobPerU) || !nearlyProportional(two.activityUPerMin, one.activityPerUPerMin))
                return KernelOutcome.Rejected(
                    KernelReason.NON_LINEAR_MODEL,
                    "offsetMin=$m 1U=(${one.iobPerU},${one.activityPerUPerMin}) 2U=(${two.iobU},${two.activityUPerMin})"
                )
        }

        return KernelOutcome.Ok(UnitInsulinKernel(deliveryTs, points, model, insulinPluginId))
    }

    private fun nearlyProportional(doubled: Double, single: Double): Boolean {
        val expected = 2.0 * single
        val diff = abs(doubled - expected)
        return diff <= ABS_EPS || diff <= LINEARITY_REL_EPS * abs(expected)
    }
}
