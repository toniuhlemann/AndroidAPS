package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.SafetyReason
import app.aaps.fuse.core.predictor.PredictorReason

/**
 * DER PREDICTORFREIE MARKERPFAD - wann er ueberhaupt existieren darf.
 *
 * DAS PROBLEM, eine Stufe frueher als das bekannte: wird die Trajektorie
 * verworfen, bricht der Zyklus ab, BEVOR eine Menge entstehen kann. Die
 * Autorisierungsgrenze `markerLowAuthorizedU` wird in [PrimeRelease.lift]
 * erzeugt - was davor abbricht, kann kein Boden mehr heilen. Dieselbe
 * Fehlerklasse wie der frueher bedingungslose Schwanz-Deckel, nur weiter vorn.
 *
 * DIE VERSUCHUNG WAERE EINE LISTE "Modellfehler duerfen durch". Sie ist falsch,
 * und Tonis Begruendung vom 11.08. ist die tragende: der Marker bestaetigt
 * KOHLENHYDRATE, nicht die Integrität der Eingaben. Wer den Knopf drueckt,
 * weiss, dass gleich etwas kommt - er weiss nicht, ob die Zeitachse monoton
 * ist, ob das Profil einen ISF-Slot hat oder ob die Aktivitaet plausibel ist.
 *
 * DESHALB NUR ZWEI GRUENDE, und beide sagen dasselbe: "die Reichweite meiner
 * Rechnung endet vor dem Horizont" - eine Aussage ueber das WERKZEUG, nicht
 * ueber die Daten.
 *
 *   ARRAY_TOO_SHORT         das IOB-Array deckt den Horizont nicht
 *   PENDING_MODEL_TOO_SHORT der Einheitskern der Transportmenge deckt ihn nicht
 *
 * ALLE ACHT ANDEREN BLEIBEN ZU, auch die, die "modellhaft" klingen:
 *
 *   NON_FINITE_INPUT, NON_MONOTONIC_TIMESTAMPS  Daten- und Zeitachsenfehler
 *   GRID_MISMATCH, SKEW_BEFORE_ARRAY_START      Zeitraster stimmt nicht
 *   ISF_OUT_OF_BOUNDS, MISSING_ISF_SLOT         Profilfehler
 *   ACTIVITY_OUT_OF_BOUNDS, DRIVE_OUT_OF_BOUNDS Plausibilitaetsbefunde
 *
 * Die letzten vier sind besonders verfuehrerisch, weil sie nach "pessimistische
 * Prognose" aussehen. Sie sind es nicht: eine Aktivitaet oder ein Antrieb
 * ausserhalb der Policy-Grenzen heisst, dass eine EINGABE unglaubwuerdig ist -
 * und dann ist auch die ISF unglaubwuerdig, mit der die Freigabe gerechnet
 * wuerde.
 */
object MarkerFallback {

    /**
     * Die einzigen zwei ueberstimmbaren Gruende.
     *
     * Als Menge und nicht als `when`: eine neue [PredictorReason] landet damit
     * automatisch auf der GESCHLOSSENEN Seite. Die umgekehrte Vorgabe - eine
     * Liste der geschlossenen Gruende - haette eine neue Ursache still
     * geoeffnet, und das ist die Richtung, in der ein Fehler Insulin kostet.
     */
    val OVERRIDABLE: Set<PredictorReason> = setOf(
        PredictorReason.ARRAY_TOO_SHORT,
        PredictorReason.PENDING_MODEL_TOO_SHORT,
    )

    /** Warum der Pfad NICHT genommen wurde - fuer den Export, damit ein
     *  ausgebliebener Fallback von einem nicht vorhandenen unterscheidbar ist. */
    enum class Denial {
        REASON_NOT_OVERRIDABLE,
        TRANSPORT_NOT_ACCOUNTED,
        SETTING_OFF,
        NO_MARKER,
        NO_MEASURED_LOW,
        HEALTH_NOT_READY,
    }

    /**
     * @param transportCommitmentU die offene Transportmenge DIESES Zyklus - die
     *   ZAHL, nicht ein Praedikat darueber.
     *
     *   Der Aufrufer reicht denselben Wert an [PrimeRelease.lift] weiter, wo er
     *   von BEIDEN Spielraeumen (iobTH und maxIOB) abgezogen wird. Zuerst stand
     *   hier ein Boolean, und der war kein Beweis: "endlich" gilt auch fuer 0,0,
     *   und ueber den Abzug sagte es nichts.
     *
     *   EHRLICH, was diese Signatur leistet und was nicht: sie verhindert, dass
     *   Freigabegrund und Abzug VERSCHIEDENE Zahlen benutzen. Dass der Abzug
     *   ueberhaupt stattfindet, haelt allein ein Test auf [PrimeRelease.lift] -
     *   entfernt man ihn aus einem der beiden Spielraeume, muss er rot werden.
     *
     *   Tonis ausdrueckliche Auflage vom 11.08., und sie haengt genau an
     *   [PredictorReason.PENDING_MODEL_TOO_SHORT]: dieser Grund sagt, dass der
     *   Einheitskern der bereits publizierten, im IOB noch nicht sichtbaren
     *   Menge das Fenster nicht deckt. Ihre BAHNwirkung ist damit unbekannt -
     *   die MENGE ist es nicht. Solange sie von beiden Spielraeumen abgezogen
     *   wird, kann sie nicht ein zweites Mal finanziert werden; faellt dieser
     *   Abzug weg, ist der Grund nicht mehr ueberstimmbar. Bei
     *   [PredictorReason.ARRAY_TOO_SHORT] spielt er keine Rolle - dort fehlt
     *   das IOB-Array, nicht der Kern der Transportmenge.
     * @param measuredLow die GEMESSENE Tieflage als einziger Sicherheitsgrund.
     *   Leere Menge heisst "kein Tief", nicht "alles in Ordnung" - s. der P0
     *   vom 11.08. mit `all { it == LOW }` auf der leeren Menge.
     */
    fun denial(
        reason: PredictorReason,
        markerAuthorisesLow: Boolean,
        mealMarkerActive: Boolean,
        safetyReasons: Set<SafetyReason>,
        health: Health,
        transportCommitmentU: Double,
    ): Denial? = when {
        reason !in OVERRIDABLE                                  -> Denial.REASON_NOT_OVERRIDABLE
        reason == PredictorReason.PENDING_MODEL_TOO_SHORT &&
            !transportCommitmentU.isFinite()                    -> Denial.TRANSPORT_NOT_ACCOUNTED

        !markerAuthorisesLow                                    -> Denial.SETTING_OFF
        !mealMarkerActive                                       -> Denial.NO_MARKER
        safetyReasons != setOf(SafetyReason.LOW)                -> Denial.NO_MEASURED_LOW
        // READY ist die SCHMALSTE verfuegbare Aussage ueber die Signalguete und
        // deckt genau, was Toni verlangt hat: frisch (kein STALE, kein WARMUP),
        // monoton (kein INPUT_STEP_RECOVERY), gueltige ISF und Aktivitaet
        // (kein ISF_MISSING, ACTIVITY_MISSING/STALE/FUTURE), und ausreichend
        // Historie. Eine eigene Frischepruefung waere eine zweite Wahrheit
        // ueber dieselbe Frage.
        health != Health.READY                                  -> Denial.HEALTH_NOT_READY
        else                                                    -> null
    }
}
