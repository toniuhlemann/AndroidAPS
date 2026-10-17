package app.aaps.fuse.core.predictor

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bolus-Deckungs-Abschlag fuer die UNTERE Bahn.
 *
 * GEMESSEN in einer realen Nacht (fuse_state_history.jsonl, ganze Nacht):
 * sehr viele SMBs, zusammen mehrere Einheiten, Serien bis 21 Minuten. Mechanik: `r` ist
 * die BGI-bereinigte Steigung, also naeherungsweise
 *
 *     r = roher Trend + Insulinaktivitaet x ISF.
 *
 * Mitten in der Nacht stand der rohe Trend bei ~0, die eigene Aktivitaet aber
 * deutlich positiv -> r hoch -> volle Anstiegs-Ratio -> naechste Dosis -> mehr
 * Aktivitaet -> hoeheres r. Der Regler liest seine eigene Reaktion als Beweis
 * einer groesseren Stoerung. Arithmetisch korrekt, strategisch falsch.
 *
 * Der Abschlag stellt der Guardbahn die Gegenfrage: WAS, WENN NUR DER
 * SICHTBARE TREND REAL IST und der vom BOLUS-Insulin verdeckte Anteil der
 * Stoerung nicht anhaelt?
 *
 *     lower = min(bandLower, mean - lambda * max(0, bolusActivity * isf))
 *
 * BEWUSST nur die BOLUS-Aktivitaet: die Basal-Aktivitaet deckt im
 * Gleichgewicht die EGP. Sie mit abzuziehen wuerde jede ruhige Nacht in einen
 * GUARD_FLOOR-Zero-Temp treiben (schon wenig Basal-IOB zoege die Bahn unter den Boden).
 *
 * EINSEITIGKEIT, tragend: der Term ist >= 0 und wirkt ausschliesslich auf
 * `lower`. Die Mittelbahn - und damit `insulinReq` und jede Dosis - bleibt
 * unberuehrt. Der Abschlag kann Dosen nur verkleinern oder blocken, nie
 * vergroessern. Am Onset aus der Ruhe ist die Bolus-Aktivitaet ~0 und der
 * Abschlag verschwindet - er bremst genau dort nicht, wo FCL vorne reagieren
 * muss.
 */
object DriveDiscount {

    data class Applied(
        val lambda: Double,
        val bolusActivityUPerMin: Double,
        val isfMgdlPerU: Double,
        /** `lambda * max(0, bolusActivity*isf)` [mg/dl/min] - das tatsaechlich Abgezogene. */
        val termMgdlPerMin: Double,
        val lowerBeforeMgdlPerMin: Double,
        val lowerAfterMgdlPerMin: Double,
    )

    /**
     * Wirft bei unbrauchbarer Eingabe - der Aufrufer steht im CoreInputGuard,
     * der daraus einen benannten Abbruch macht. Ein stiller `return band` bei
     * NaN waere ein Abschlag, der genau dann verschwindet, wenn die Datenlage
     * am schlechtesten ist.
     */
    fun apply(
        meanMgdlPerMin: Double,
        bandLowerMgdlPerMin: Double,
        bolusActivityUPerMin: Double,
        isfMgdlPerU: Double,
        lambda: Double,
    ): Applied {
        require(meanMgdlPerMin.isFinite()) { "mean=$meanMgdlPerMin" }
        require(bandLowerMgdlPerMin.isFinite()) { "bandLower=$bandLowerMgdlPerMin" }
        require(bolusActivityUPerMin.isFinite()) { "bolusActivity=$bolusActivityUPerMin" }
        require(isfMgdlPerU.isFinite() && isfMgdlPerU > 0.0) { "isf=$isfMgdlPerU" }
        require(lambda.isFinite() && lambda >= 0.0) { "lambda=$lambda" }
        val term = lambda * max(0.0, bolusActivityUPerMin * isfMgdlPerU)
        return Applied(
            lambda = lambda,
            bolusActivityUPerMin = bolusActivityUPerMin,
            isfMgdlPerU = isfMgdlPerU,
            termMgdlPerMin = term,
            lowerBeforeMgdlPerMin = bandLowerMgdlPerMin,
            lowerAfterMgdlPerMin = min(bandLowerMgdlPerMin, meanMgdlPerMin - term),
        )
    }

    /** `TS-PS-Q50-...+BD100` = Bolus-Discount, lambda in Prozent. `+BD0` sagt
     *  ausdruecklich "konfiguriert aus" - Abwesenheit des Suffixes hiesse
     *  dagegen "alter Build ohne Abschlag". */
    fun methodId(base: String, lambda: Double): String = "$base+BD${(lambda * 100).roundToInt()}"
}
