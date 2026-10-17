package app.aaps.fuse.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.iobCalc
import app.aaps.fuse.core.insulin.InsulinSample
import app.aaps.fuse.core.insulin.UnitInsulinSampler

/**
 * Die EINZIGE Naht zwischen dem puren Einheitskern und AAPS (v0.3.1 C1.1).
 *
 * Hier wird das Insulinmodell GESAMPELT, nicht nachgebaut: eine eigene Kopie der oref-Formel
 * wuerde bei jedem AAPS-Update lautlos auseinanderlaufen, und FUSE wuerde mit einem anderen Modell
 * dosieren als der Loop bilanziert.
 *
 * v4 (FUSE-Vertrag 3): die Insulindefinition ist eine [ICfg] und haengt an JEDER Bolus-Zeile; die
 * DIA steckt in ihr. Der Sampler bekommt genau die [ICfg], mit der der Bolus-IOB dieses Zyklus
 * rechnet (der Zyklus prueft vorher, dass alle Boli im Fenster dieselbe Kurve haben, sonst pausiert
 * FUSE). Er rechnet ueber denselben Weg wie der Bolus-IOB ([iobCalc]), also mit demselben Modell.
 * Mengen sind Insulin-Einheiten, wie BS.amount.
 *
 * Negative Offsets kommen hier nie an — die Null vor der Lieferung setzt der pure Kern selbst, weil
 * `iobCalcForTreatment` sie NICHT setzt (`t < td` ohne Pruefung auf `t >= 0`).
 */
class AapsUnitInsulinSampler(
    private val iCfg: ICfg,
    private val deliveryTs: Long,
) : UnitInsulinSampler {

    init {
        // A sentinel insulin (a row not yet stamped) is no insulin: the curve would be clamped nonsense.
        require(iCfg.isUsable) { "unit kernel needs a real insulin definition" }
    }

    override fun sampleAfterDelivery(doseU: Double, offsetMin: Int): InsulinSample {
        require(offsetMin >= 0) { "kernel must never sample before delivery: offsetMin=$offsetMin" }
        val virtualBolus = BS(timestamp = deliveryTs, amount = doseU, type = BS.Type.SMB, iCfg = iCfg)
        val iob = virtualBolus.iobCalc(deliveryTs + offsetMin * 60_000L)
        return InsulinSample(iobU = iob.iobContrib, activityUPerMin = iob.activityContrib)
    }
}
