package app.aaps.fuse.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.fuse.core.insulin.InsulinSample
import app.aaps.fuse.core.insulin.UnitInsulinSampler

/**
 * Die EINZIGE Naht zwischen dem puren Einheitskern und AAPS (v0.3.1 C1.1).
 *
 * Hier wird das aktive Insulinmodell GESAMPELT, nicht nachgebaut: eine eigene
 * Kopie der oref-Formel wuerde bei jedem AAPS-Update lautlos auseinanderlaufen,
 * und FUSE wuerde mit einem anderen Modell dosieren als der Loop bilanziert.
 *
 * [diaHours] wird UEBERGEBEN und nicht aus `insulin.dia` gezogen: dieser Getter
 * liest ueber `profileFunction.getProfile()` das AKTUELLE Profil
 * (`InsulinOrefBasePlugin.kt:69-73`) und wuerde damit eine andere DIA
 * verwenden koennen als die, mit der der Zyklus gerechnet hat. Die
 * Zyklus-DIA gehoert in den Snapshot, nicht in einen Nebenpfad.
 *
 * Negative Offsets kommen hier nie an — die Null vor der Lieferung setzt der
 * pure Kern selbst, weil `iobCalcForTreatment` sie NICHT setzt (`t < td` ohne
 * Pruefung auf `t >= 0`).
 */
class AapsUnitInsulinSampler(
    private val insulin: Insulin,
    private val diaHours: Double,
    private val deliveryTs: Long,
) : UnitInsulinSampler {

    override fun sampleAfterDelivery(doseU: Double, offsetMin: Int): InsulinSample {
        require(offsetMin >= 0) { "kernel must never sample before delivery: offsetMin=$offsetMin" }
        val virtualBolus = BS(timestamp = deliveryTs, amount = doseU, type = BS.Type.SMB)
        val iob = insulin.iobCalcForTreatment(virtualBolus, deliveryTs + offsetMin * 60_000L, diaHours)
        return InsulinSample(iobU = iob.iobContrib, activityUPerMin = iob.activityContrib)
    }
}
