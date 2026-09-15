package app.aaps.fuse.core.controller

import app.aaps.fuse.core.predictor.DriveDecayModel
import kotlin.math.min

/**
 * HALTE-ANHEBUNG DES STOERUNGSTERMS IM LIVENESS-BEDARF - Experiment, Default AUS
 * (Tonis Auftrag 15.09., Ursachenzerlegung Fruehstueck).
 *
 * BEFUND, aus dem der Kandidat stammt (synthetisch nachgebaut, keine Messwerte):
 * am 60-min-Freigabehorizont wirkt der Stoerungsbeitrag mit tau 60 nur mit dem
 * Gewicht 37,6 statt 60 - der Antrieb ist nach einer Stunde auf 37 % gefallen,
 * auch wenn der gemessene Anstieg anhaelt. Das ist EIN Anteil der Unterschaetzung;
 * der groessere (Zunahme der Anstiegsrate) ist mit einem Term, der nicht wachsen
 * kann, nicht erreichbar und wird hier bewusst NICHT angegangen.
 *
 * MECHANIK: die Mittelbahn summiert `drive * factorAt(s)` je Minute. Haelt man
 * den Antrieb die ersten [HOLD_MIN] Minuten und laesst ihn danach mit
 * demselben tau abklingen, aendert sich die Mittelbahn am Horizont um genau
 * `drive * Summe(fHold(s) - fExp(s))`. Diese Differenz wird NUR auf den
 * Liveness-Bedarf gelegt.
 *
 * ENG BEGRENZT:
 *  - nur unter GUELTIGER MEAL-Autorisierung (Tonis Review 15.09.: der
 *    Kandidat ist ein Mahlzeitenkandidat - CORRECTION hebt nie),
 *  - nur ein laufender, bereits bewaffneter Liveness-Lauf (alle Tore davor
 *    unveraendert; keine Bewaffnung wird dadurch frueher),
 *  - nur der produktive Zerfall (ExponentialDecay mit dem konfigurierten tau,
 *    ohne vorzeichenbewusste Rebound-Kuerzung) - im Rebound-Fenster nie,
 *  - nur positiver Modellantrieb und nur, wenn die bereinigte Rate ihn in
 *    DIESEM Zyklus mindestens erreicht (Bedarf),
 *  - nur mit [MeasuredRiseEvidence]-Bestaetigung (frische Rohwerte, Bloecke mit
 *    neuem Hoch, Bedarf je Block einmal - Tonis Vertrag 15.09. abends). Die
 *    fruehere Zwei-Zyklen-Bestaetigung ueber die Rate allein ist entfallen,
 *  - Anhebung hoechstens [UPLIFT_CAP_MGDL] - bei ISF 80 und Ratio 0,35 also
 *    hoechstens ~0,18 U Kandidat je Zyklus,
 *  - nur der Bedarf: untere Bahnen, Guard, Tail, Ratio, maxSMB, Kanaldeckel,
 *    Expositionsgrenze, Transporthaftung und Pumpenraster bleiben.
 */
object LivenessDriveHold {

    /** Haltedauer des Antriebs [min]. Fest - kein Stellhebel. */
    const val HOLD_MIN = 20

    /** Obergrenze der Anhebung der Freigabe-Mittelbahn [mg/dl]. */
    const val UPLIFT_CAP_MGDL = 40.0

    enum class Denial {
        DISABLED,
        NOT_ACTIVE,
        NOT_MEAL_AUTHORIZED,
        DECAY_NOT_BASELINE,
        HORIZON_INVALID,
        DRIVE_NOT_POSITIVE,
        FAST_DRIVE_MISSING,
        FAST_BELOW_DRIVE,
        MEASURED_NOT_CONFIRMED,
    }

    data class Input(
        val enabled: Boolean,
        /** Der Liveness-Lauf ist bewaffnet und rechnet in diesem Zyklus einen Kandidaten. */
        val livenessActive: Boolean,
        /** Gueltige MEAL-Autorisierung in diesem Zyklus ([DosingContext.Decision.mealAuthorized]). */
        val mealAuthorized: Boolean,
        /** Modellantrieb der Mittelbahn [mg/dl/min] (vor dem Zerfall). */
        val driveMeanMgdlPerMin: Double,
        /** Gemessene BGI-bereinigte Rate [mg/dl/min]; null = nicht berechenbar. */
        val fastDriveMgdlPerMin: Double?,
        val decay: DriveDecayModel,
        val decayNegativeDrive: DriveDecayModel?,
        /** Konfiguriertes tau - der Zerfall muss GENAU dieser sein. */
        val baselineTauMin: Int,
        val releaseHorizonMin: Int,
        /** [MeasuredRiseEvidence.Result.confirmed] dieses Zyklus. */
        val measuredConfirmed: Boolean,
    )

    /** [upliftMgdl] >= 0; [denial] null = gehoben. */
    data class Result(val upliftMgdl: Double, val denial: Denial?)

    /** Zusatzgewicht der Halte- gegenueber der reinen Exponentialbahn bis [horizonMin]. */
    fun extraWeight(horizonMin: Int, tauMin: Double, holdMin: Int = HOLD_MIN): Double {
        if (horizonMin <= 0) return 0.0
        val exp = DriveDecayModel.ExponentialDecay(tauMin)
        val hold = DriveDecayModel.HoldThenExponentialDecay(min(holdMin, horizonMin).toDouble(), tauMin)
        return (1..horizonMin).sumOf { s -> hold.factorAt(s.toDouble()) - exp.factorAt(s.toDouble()) }
    }

    /** Bedarf dieses Zyklus: positiver Antrieb und bereinigte Rate >= Antrieb. */
    fun needMet(driveMeanMgdlPerMin: Double, fastDriveMgdlPerMin: Double?): Boolean =
        driveMeanMgdlPerMin.isFinite() && driveMeanMgdlPerMin > 0.0 &&
            fastDriveMgdlPerMin != null && fastDriveMgdlPerMin.isFinite() && fastDriveMgdlPerMin >= driveMeanMgdlPerMin

    fun decide(i: Input): Result {
        if (!i.enabled) return Result(0.0, Denial.DISABLED)
        if (!i.livenessActive) return Result(0.0, Denial.NOT_ACTIVE)
        if (!i.mealAuthorized) return Result(0.0, Denial.NOT_MEAL_AUTHORIZED)
        val tau = (i.decay as? DriveDecayModel.ExponentialDecay)?.tauMin
        if (tau == null || tau != i.baselineTauMin.toDouble() || i.decayNegativeDrive != null)
            return Result(0.0, Denial.DECAY_NOT_BASELINE)
        if (i.releaseHorizonMin <= 0) return Result(0.0, Denial.HORIZON_INVALID)
        val drive = i.driveMeanMgdlPerMin
        if (!drive.isFinite() || drive <= 0.0) return Result(0.0, Denial.DRIVE_NOT_POSITIVE)
        val fast = i.fastDriveMgdlPerMin
        if (fast == null || !fast.isFinite()) return Result(0.0, Denial.FAST_DRIVE_MISSING)
        if (fast < drive) return Result(0.0, Denial.FAST_BELOW_DRIVE)
        if (!i.measuredConfirmed) return Result(0.0, Denial.MEASURED_NOT_CONFIRMED)
        val uplift = min(UPLIFT_CAP_MGDL, drive * extraWeight(i.releaseHorizonMin, tau))
        return Result(uplift.coerceAtLeast(0.0), null)
    }
}
