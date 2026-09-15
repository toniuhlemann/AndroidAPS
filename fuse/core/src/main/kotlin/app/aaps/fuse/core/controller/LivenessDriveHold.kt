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
 *    Kandidat ist ein Mahlzeitenkandidat - CORRECTION hebt nie). Verlust der
 *    Autorisierung oder eine andere Autorisierungsidentitaet (Markerwechsel)
 *    setzt die Bestaetigung zurueck,
 *  - nur ein laufender, bereits bewaffneter Liveness-Lauf (alle Tore davor
 *    unveraendert; keine Bewaffnung wird dadurch frueher),
 *  - nur der produktive Zerfall (ExponentialDecay mit dem konfigurierten tau,
 *    ohne vorzeichenbewusste Rebound-Kuerzung) - im Rebound-Fenster nie,
 *  - nur positiver Modellantrieb, und nur wenn die gemessene BGI-bereinigte
 *    Rate ihn in [CONFIRM_CYCLES] Zyklen in Folge mindestens erreicht
 *    (Plateau, Dip, Fallen und ein einzelner Messausreisser brechen die Folge),
 *  - Anhebung hoechstens [UPLIFT_CAP_MGDL] - bei ISF 80 und Ratio 0,35 also
 *    hoechstens ~0,18 U Kandidat je Zyklus,
 *  - nur der Bedarf: untere Bahnen, Guard, Tail, Ratio, maxSMB, Kanaldeckel,
 *    Expositionsgrenze, Transporthaftung und Pumpenraster bleiben.
 * Keine Menge ueber diese Rechnung hinaus; kein Zustand ueberlebt einen Neustart
 * (die Bestaetigung beginnt dann neu - konservativ).
 */
object LivenessDriveHold {

    /** Haltedauer des Antriebs [min]. Fest - kein Stellhebel. */
    const val HOLD_MIN = 20

    /** Obergrenze der Anhebung der Freigabe-Mittelbahn [mg/dl]. */
    const val UPLIFT_CAP_MGDL = 40.0

    /** Zyklen in Folge mit gemessener Rate >= Modellantrieb. */
    const val CONFIRM_CYCLES = 2

    enum class Denial {
        DISABLED,
        NOT_ACTIVE,
        NOT_MEAL_AUTHORIZED,
        DECAY_NOT_BASELINE,
        HORIZON_INVALID,
        DRIVE_NOT_POSITIVE,
        FAST_DRIVE_MISSING,
        FAST_BELOW_DRIVE,
        NOT_CONFIRMED,
    }

    data class Input(
        val enabled: Boolean,
        /** Der Liveness-Lauf ist bewaffnet und rechnet in diesem Zyklus einen Kandidaten. */
        val livenessActive: Boolean,
        /** Gueltige MEAL-Autorisierung in diesem Zyklus ([DosingContext.Decision.mealAuthorized]). */
        val mealAuthorized: Boolean,
        /** Identitaet der Autorisierung ([DosingContext.Decision.authorizationId]); 0 = keine. */
        val authorizationId: Long,
        /** Identitaet, unter der [previousStreak] bestaetigt wurde; 0 = keine. */
        val previousAuthorizationId: Long,
        /** Modellantrieb der Mittelbahn [mg/dl/min] (vor dem Zerfall). */
        val driveMeanMgdlPerMin: Double,
        /** Gemessene BGI-bereinigte Rate [mg/dl/min]; null = nicht berechenbar. */
        val fastDriveMgdlPerMin: Double?,
        val decay: DriveDecayModel,
        val decayNegativeDrive: DriveDecayModel?,
        /** Konfiguriertes tau - der Zerfall muss GENAU dieser sein. */
        val baselineTauMin: Int,
        val releaseHorizonMin: Int,
        /** Bestaetigte Zyklen bis zum Vorzyklus. */
        val previousStreak: Int,
    )

    /**
     * [upliftMgdl] >= 0; [streak] ist der Stand fuer den naechsten Zyklus und
     * gilt NUR fuer [authorizationId] (0 bei jeder Ablehnung vor der Bestaetigung).
     */
    data class Result(val upliftMgdl: Double, val streak: Int, val denial: Denial?, val authorizationId: Long = 0L)

    /** Zusatzgewicht der Halte- gegenueber der reinen Exponentialbahn bis [horizonMin]. */
    fun extraWeight(horizonMin: Int, tauMin: Double, holdMin: Int = HOLD_MIN): Double {
        if (horizonMin <= 0) return 0.0
        val exp = DriveDecayModel.ExponentialDecay(tauMin)
        val hold = DriveDecayModel.HoldThenExponentialDecay(min(holdMin, horizonMin).toDouble(), tauMin)
        return (1..horizonMin).sumOf { s -> hold.factorAt(s.toDouble()) - exp.factorAt(s.toDouble()) }
    }

    fun decide(i: Input): Result {
        if (!i.enabled) return Result(0.0, 0, Denial.DISABLED)
        if (!i.livenessActive) return Result(0.0, 0, Denial.NOT_ACTIVE)
        if (!i.mealAuthorized || i.authorizationId <= 0L) return Result(0.0, 0, Denial.NOT_MEAL_AUTHORIZED)
        val tau = (i.decay as? DriveDecayModel.ExponentialDecay)?.tauMin
        if (tau == null || tau != i.baselineTauMin.toDouble() || i.decayNegativeDrive != null)
            return Result(0.0, 0, Denial.DECAY_NOT_BASELINE)
        if (i.releaseHorizonMin <= 0) return Result(0.0, 0, Denial.HORIZON_INVALID)
        val drive = i.driveMeanMgdlPerMin
        if (!drive.isFinite() || drive <= 0.0) return Result(0.0, 0, Denial.DRIVE_NOT_POSITIVE)
        val fast = i.fastDriveMgdlPerMin
        if (fast == null || !fast.isFinite()) return Result(0.0, 0, Denial.FAST_DRIVE_MISSING)
        if (fast < drive) return Result(0.0, 0, Denial.FAST_BELOW_DRIVE)
        // Eine Bestaetigung unter einer ANDEREN Autorisierung zaehlt nicht.
        val vorher = if (i.previousAuthorizationId == i.authorizationId) i.previousStreak.coerceAtLeast(0) else 0
        val streak = minOf(vorher + 1, 99)
        if (streak < CONFIRM_CYCLES) return Result(0.0, streak, Denial.NOT_CONFIRMED, i.authorizationId)
        val uplift = min(UPLIFT_CAP_MGDL, drive * extraWeight(i.releaseHorizonMin, tau))
        return Result(uplift.coerceAtLeast(0.0), streak, null, i.authorizationId)
    }
}
