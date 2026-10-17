package app.aaps.fuse.core.predictor

import kotlin.math.exp

/**
 * Antriebs-Abklingmodelle M1-M3 (Spec K2-P §6, Fixblock v0.1.2 Q7).
 *
 * NUR die reinen Funktionen — der Sweep/Evaluator, der zwischen ihnen waehlt,
 * ist vom ersten Kotlin-GO ausdruecklich ausgenommen (R70-F6/R71-C): Suchraster,
 * Score und Tie-Schwelle muessen feststehen, BEVOR ein Modellergebnis sichtbar
 * ist. Diese Klassen entscheiden nichts, sie rechnen nur.
 *
 * KEIN VORZEICHEN HIER (C10, Codex H5): die Rebound-Kuerzung darf nur auf
 * POSITIVE Antriebsanteile wirken - fuer negative wuerde derselbe schnellere
 * Zerfall den negativen Beitrag verkleinern und die untere Bahn ANHEBEN.
 * Umgesetzt ist das trotzdem NICHT hier als `tauPositive/tauNegative`, sondern
 * in [TrajectoryCore] ueber [app.aaps.fuse.core.predictor.PredictorInput.decayNegativeDrive].
 * Gruende:
 *  - `factorAt(sMin)` bliebe sonst eine Funktion mit stiller Zusatzbedeutung;
 *    jeder Aufrufer muesste das Vorzeichen mitgeben, und die bestehenden
 *    M1/M2/M3-Tests pruefen genau diese eine Signatur.
 *  - Die Kuerzung ist eine Eigenschaft des ZYKLUS (Rebound-Fenster), nicht des
 *    Zerfallsmodells. Zwei taus in M1 wuerden eine Regelentscheidung in eine
 *    reine Rechenklasse verschieben.
 *  - So bleibt "welches Modell" und "wie streng je Vorzeichen" getrennt
 *    testbar, und M2/M3 erben die Eigenschaft ohne eigene Aenderung.
 */
sealed interface DriveDecayModel {

    val modelId: String

    /** Faktor in [0,1], mit dem der Antrieb nach `sMin` Minuten fortwirkt. */
    fun factorAt(sMin: Double): Double

    /** M1: reiner Exponentialzerfall. */
    data class ExponentialDecay(val tauMin: Double) : DriveDecayModel {
        init { require(tauMin in 10.0..240.0) { "tau out of domain: $tauMin" } }
        override val modelId get() = "M1_EXPONENTIAL_DECAY"
        override fun factorAt(sMin: Double) = exp(-sMin / tauMin)
    }

    /**
     * M2: haelt den Antrieb konstant und beginnt DANN den Zerfall.
     * Bewusst NICHT "Totzeit" genannt — der Name aus v0.1.1 war irrefuehrend,
     * ein klassischer Totzeitpfad liefert in der Haltephase nichts (R70-F6).
     * Die Stueckfunktion ist bei s = td stetig (beide Aeste ergeben 1.0).
     */
    data class HoldThenExponentialDecay(val holdMin: Double, val tauMin: Double) : DriveDecayModel {
        init {
            require(holdMin in 0.0..60.0) { "hold out of domain: $holdMin" }
            require(tauMin in 10.0..240.0) { "tau out of domain: $tauMin" }
        }
        override val modelId get() = "M2_HOLD_THEN_EXPONENTIAL_DECAY"
        override fun factorAt(sMin: Double) =
            if (sMin <= holdMin) 1.0 else exp(-(sMin - holdMin) / tauMin)
    }

    /**
     * M3: zweiphasig. `w` liegt streng in (0,1) — bei w = 0 oder 1 waere eine
     * Zeitkonstante wirkungslos und das Modell in Wahrheit M1. Diese Entartung
     * wird nicht toleriert, sondern von [of] kanonisch auf M1 reduziert;
     * einen "fast M3"-Zustand gibt es nicht (R71 §7.3).
     */
    data class TwoPhaseExponentialDecay(
        val weightFast: Double,
        val tauFastMin: Double,
        val tauSlowMin: Double,
    ) : DriveDecayModel {
        init {
            require(weightFast > 0.0 && weightFast < 1.0) { "w must be in (0,1): $weightFast" }
            require(tauFastMin in 5.0..60.0) { "tauFast out of domain" }
            require(tauSlowMin in 30.0..360.0) { "tauSlow out of domain" }
            require(tauFastMin < tauSlowMin) { "tauFast must be < tauSlow" }
        }
        override val modelId get() = "M3_TWO_PHASE_EXPONENTIAL_DECAY"
        override fun factorAt(sMin: Double) =
            weightFast * exp(-sMin / tauFastMin) + (1.0 - weightFast) * exp(-sMin / tauSlowMin)

        companion object {
            /** Kanonische Erzeugung: Randgewichte ergeben M1 mit der wirksamen
             *  Zeitkonstante, nie ein entartetes M3. */
            fun of(weightFast: Double, tauFastMin: Double, tauSlowMin: Double): DriveDecayModel = when {
                weightFast <= 0.0 -> ExponentialDecay(tauSlowMin)
                weightFast >= 1.0 -> ExponentialDecay(tauFastMin)
                else              -> TwoPhaseExponentialDecay(weightFast, tauFastMin, tauSlowMin)
            }
        }
    }
}
