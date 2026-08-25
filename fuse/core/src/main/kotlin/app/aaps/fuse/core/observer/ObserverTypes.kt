package app.aaps.fuse.core.observer

import java.security.MessageDigest

/**
 * Typen des K1-Observers (Spec v0.5 §3/§13 + Addendum v0.5.1).
 *
 * Drei ORTHOGONALE Achsen statt eines Misch-Enums: health, safety, phase.
 * Ein Zustand wie "DEGRADED weil Activity fehlt UND gleichzeitig LOW" muss
 * darstellbar sein, ohne fuer jede Kombination einen Enumwert zu erfinden —
 * deshalb sind die Gruende MENGEN.
 */

/** Health-Gruende. Jeder verschwindet EINZELN (Addendum A1); READY gilt erst
 *  bei leerer Menge. Es gibt bewusst keinen Grund "unbekannt": wer keinen
 *  benennbaren Grund hat, ist READY. */
enum class HealthReason {
    WARMUP,                      // Prozessstart, Sensorwechsel, dt > 60 min
    STALE,                       // kein neues Sample seit STALE_MIN
    SENSOR_EMBARGO,              // sourceTs < sensorEpoch + SENSOR_EMBARGO_MIN
    CALIBRATION_BLIND,           // sourceTs < calibrationEpoch + CALIB_BLIND_MIN
    INPUT_STEP_RECOVERY,         // nach unklassifiziertem Eingangssprung
    INSUFFICIENT_SIGNAL_HISTORY, // R nicht berechenbar (< 5 Punkte / < 8 Paare)
    Q1_MISSING,
    ACTIVITY_MISSING,
    ACTIVITY_STALE,
    ACTIVITY_FUTURE,             // activityModelTs > sourceTs -> nicht kausal
    ISF_MISSING,
}

enum class SafetyReason { LOW }

enum class Health { WARMUP, READY, DEGRADED }

enum class Phase { REARMING, ARMED, CANDIDATE, RISE_ACTIVE, CARRY, TURN }

/** Ursachen, die Phase und/oder R-Segment zuruecksetzen. Bewusst GETRENNT
 *  benannt (Spec §4.4): ein Sprung ohne Epochwechsel kann auch ein
 *  Sensorartefakt sein und darf die Ursachenstatistik nicht verfaelschen. */
enum class ResetCause {
    SENSOR_RESET,
    CALIBRATION_RESET,
    INPUT_STEP_SUSPECTED,
    CONTINUITY_BREAK,   // dt > STATE_CONT_MAX_MIN
    R_SEGMENT_BREAK,    // dt > R_SEGMENT_BREAK_MIN
    FULL_REINIT,        // dt > REINIT_MIN
    INPUT_DROP,         // Queue-Drop, vom Adapter gemeldet
}

enum class TransitionType { RISE_CONFIRMED, ABORT, GATED, PHASE_CHANGE }

data class Transition(
    val type: TransitionType,
    val from: Phase,
    val to: Phase,
    val reasons: Set<String>,
    val triggerSourceTs: Long,
    val triggerComputeTs: Long,
    val candidateId: String? = null,
    val eventId: String? = null,
)

/**
 * Peak auf signalInputBg — der LIVE-Peak. Er endet mit der Phase.
 *
 * RICHTIGSTELLUNG: hier stand frueher, `outcomePeak` werde eingefroren und
 * ueberlebe die Phase. Das war eine Behauptung ueber Code, den es nicht gibt —
 * weder [Peak] noch [ObserverStep] haben ein solches Feld, und beide Endpfade
 * der Maschine loeschen `livePeak` gemeinsam mit `eventId`.
 *
 * Das Einfrieren gehoert auch nicht hierher: solange Phase, `eventId`,
 * `livePeak` und der Ankerzeitpunkt je Zyklus EXPORTIERT werden, laesst sich
 * der Peak einer Episode nachtraeglich exakt bestimmen — aus der Rohreihe, die
 * ohnehin in der Datenbank liegt. Die Bewertungsregel bleibt damit aenderbar,
 * statt in der Zustandsmaschine festzustehen.
 */
data class Peak(val sourceTs: Long, val value: Double)

/** Eingabe je Observer-Aufruf. Der Adapter hat Q1, R und die Gueltigkeiten
 *  bereits bestimmt; die Maschine rechnet NICHT selbst nach — sie entscheidet.
 *  null bedeutet ueberall NICHT VORHANDEN, nie 0 (Spec §12 U1-U5). */
data class ObserverInput(
    val sourceTs: Long,
    val computeTs: Long,
    val signalInputBg: Double?,
    val q1: Double?,
    val rSigned: Double?,
    val sensorEpoch: Long,
    val calibrationEpoch: Long,
    val activity: ActivityValidity,
    val profileIsfValid: Boolean,
    val inputGap: Boolean = false,
)

enum class ActivityValidity { VALID, MISSING, STALE, FUTURE }

/** Ergebnis eines Aufrufs. [accepted] = false bei Duplikat/Out-of-order:
 *  dann wurde KEIN Phasenschritt gemacht (die Health-Clock lief trotzdem). */
data class ObserverStep(
    val accepted: Boolean,
    val health: Health,
    val healthReasons: Set<HealthReason>,
    val safetyReasons: Set<SafetyReason>,
    val phase: Phase,
    val transition: Transition?,
    val candidateId: String?,
    val eventId: String?,
    val livePeak: Peak?,
    val quietAccumMin: Double,
    val confirmCount: Int,
    val carryDurMin: Double,
    val resetCauses: Set<ResetCause>,
)

/** Gelockte Parameter (Spec §13). Als Datenklasse, damit Tests Kanten setzen
 *  koennen — der Produktivpfad verwendet ausschliesslich die Defaults. */
data class ObserverParams(
    val candidateKey: String = "K1-Q1-BGI-W18-T050-CC2-R5-v4",
    val thr: Double = 0.50,
    val confirmN: Int = 2,
    val rearmMin: Double = 5.0,
    val eventWindowMin: Double = 30.0,
    /**
     * SEIT 25.08. AUS [app.aaps.fuse.core.signal.GapPolicy] - hier stand
     * ein eigenes Literal `3.0`, unabhaengig von
     * `BgiAdjustedSeries.SEGMENT_BREAK_MS`. Zwei Wahrheiten fuer dieselbe
     * Grenze: die r-Reihe haette sich verbinden lassen, waehrend der
     * Observer weiter bricht (oder umgekehrt). Der Default liest jetzt
     * die eine Politik; ein Test darf ihn weiterhin ausdruecklich setzen.
     */
    val rSegmentBreakMin: Double = app.aaps.fuse.core.signal.GapPolicy.rSegmentBreakMin,
    val stateContMaxMin: Double = 1.5,
    val staleMin: Double = 5.0,
    val reinitMin: Double = 60.0,
    val turnPeakAgeMin: Double = 3.0,
    val turnDropMgdl: Double = 10.0,
    val turnExitMin: Double = 5.0,
    val sensorEmbargoMin: Double = 30.0,
    // C9-01: EINE Zahl fuer den Sprungzaun - hier und in der Signalkette
    // (Reihenbeschnitt) muss dieselbe Grenze gelten, sonst haelt der eine an,
    // waehrend der andere weiterrechnet.
    val inputStepMgdl: Double = app.aaps.fuse.core.signal.SignalWindow.INPUT_STEP_MGDL,
    val calibBlindMin: Double = 10.0,
    val lowEnterMgdl: Double = 75.0,
    val lowExitMgdl: Double = 80.0,
    val lowExitMin: Double = 5.0,
)

internal fun sha256(vararg parts: Any?): String =
    MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
