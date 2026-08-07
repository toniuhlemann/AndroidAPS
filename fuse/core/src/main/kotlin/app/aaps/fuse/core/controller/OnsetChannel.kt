package app.aaps.fuse.core.controller

import kotlin.math.max
import kotlin.math.min

/**
 * Der oeffnende schnelle Kanal - die BRUECKE zwischen "der UKF sieht den
 * Anstieg" und "Theil-Sen bestaetigt ihn".
 *
 * GEMESSEN an vier Onsets (06.-07.08.2026): der 18-min-Median folgt einem
 * Steigungssprung mit 0 % bis Minute 5 und 100 % erst ab Minute 13; die
 * UKF-Rate ist gedaempft, aber richtungstreu. Fruehstueck 07.08.: dreimal
 * `ukfRate >= 0,5` in Folge war um 09:13 erreicht, `r` erst 09:14/09:15 -
 * beim langsamen Muesli-Onset am 06.08. laegen ~6 Minuten dazwischen. Fuer
 * FCL zaehlt jede dieser Minuten, weil die Insulinwirkung selbst nochmal
 * ~40 min braucht.
 *
 * FORM (Handoff §18, uebernommen): kein Ersatz des primaeren Antriebs,
 * sondern ein separater, begrenzter Kanal -
 *
 *  - PERSISTENZ: [PERSIST_N] aufeinanderfolgende 1-min-Werte ueber der
 *    Schwelle, lueckenlos. Ein einzelner Ausschlag oeffnet nichts.
 *  - KINEMATISCHES GATE, tragend: geprueft wird die ROHE [Sample.ukfRatePerMin],
 *    nicht der bereinigte Antrieb. Insulinaktivitaet kann die rohe Rate nur
 *    SENKEN - eine positive rohe Rate heisst, BG steigt TROTZ Insulin. Damit
 *    kann die Rueckkopplung, die die Nacht 06./07.08. dominierte (fastDrive
 *    aufgeblaeht durch eigene Aktivitaet bei roh ~0), diesen Kanal
 *    strukturell nicht oeffnen.
 *  - HAFTUNGSHUELLE: hoechstens [Input.envelopeU] zusaetzliche Einheiten je
 *    Episode, solange Theil-Sen nicht bestaetigt hat. Zum Vergleich KC2-53:
 *    die Rueckholkapazitaet ueber 30 min betraegt 0,225-0,35 U - die Huelle
 *    ist also bewusst NICHT rueckholbar klein, sondern eine begrenzte Wette,
 *    deren Preis beziffert ist.
 *  - UEBERGABE: sobald `rSigned >= threshold`, ist der Kanal SCHLAFEND -
 *    Theil-Sen traegt, die normale Rampe uebernimmt, die Huelle zaehlt nicht
 *    weiter. Der Kanal wirkt nur im unbestaetigten Kopf des Anstiegs.
 *
 * Der Kanal HEBT die Mittelbahn (max(r, drive)) - er ist damit ausdruecklich
 * NICHT einseitig wie Bremsbahn und Abschlag. Genau deshalb: Persistenz,
 * kinematisches Gate, Huelle, und die Guard-/Schwanzbahn bleiben auf der
 * UNGEHOBENEN unteren Bahn.
 */
object OnsetChannel {

    /** Drei aufeinanderfolgende Minuten. Zwei waeren ein Rauschpaar, vier
     *  verschenken eine Minute gegen das gemessene 09:13-Fenster. */
    const val PERSIST_N = 3

    /** Mit gesetztem Mahlzeiten-Marker reicht EIN Wert ueber der Schwelle:
     *  der Nutzer hat die Vorinformation geliefert, die die Persistenz sonst
     *  ersetzen muss. Gemessen 07.08.: die erste CGM-Regung kam 08:51, eine
     *  Minute nach dem Marker - Persistenz 3 haette sie verworfen. */
    const val PERSIST_N_MARKER = 1

    /** Marker-Fenster [min]. Danach verfaellt der Marker von selbst - ein
     *  vergessener Knopf darf nicht den ganzen Tag die Schwelle senken. */
    const val MARKER_WINDOW_MIN = 90

    /** Zwei 1-min-Werte gelten als aufeinanderfolgend bis 90 s Abstand -
     *  dieselbe Toleranz wie die Persistenzpruefung der Serienanalyse. */
    const val MAX_GAP_MS = 90_000L

    /** So viele geschlossene Minuten in Folge, bis die Huelle neu bewaffnet
     *  wird. Verhindert, dass ein flatternder Onset die Huelle je Flanke neu
     *  bekommt. */
    const val REARM_QUIET_MIN = 10

    data class Sample(
        val tsMs: Long,
        /** Rohe UKF-Rate [mg/dl/min] - das kinematische Gate. */
        val ukfRatePerMin: Double,
        /** BGI-bereinigter Antrieb `ukfRate + activity*isf` [mg/dl/min] - was
         *  bei Oeffnung als Antrieb dient. */
        val fastDriveMgdlPerMin: Double,
    )

    data class Input(
        val enabled: Boolean,
        /** Aufsteigend nach [Sample.tsMs]; nur die letzten [PERSIST_N] werden
         *  geprueft. */
        val samples: List<Sample>,
        val rSignedMgdlPerMin: Double,
        /** Schwelle fuer Gate UND Uebergabe - dieselbe wie `riseRampLowR`,
         *  damit "Kanal schliesst" und "Rampe beginnt" ein Punkt sind. */
        val thresholdMgdlPerMin: Double,
        val q1Outlier: Boolean,
        /** Mahlzeiten-Marker aktiv (Knopf im FUSE-Tab, Fenster
         *  [MARKER_WINDOW_MIN] min). Senkt NUR die Persistenzanforderung -
         *  er erzeugt selbst keine Dosis und hebt keine Bahn. */
        val mealMarkerActive: Boolean,
        val envelopeU: Double,
        val spentU: Double,
    )

    data class Result(
        /** Kanal hebt in diesem Zyklus die Mittelbahn und kappt an der Huelle. */
        val active: Boolean,
        /** Der Marker war zum Entscheidungszeitpunkt aktiv - fuer Trail und
         *  Fensterzuordnung (Mahlzeit vs. Korrektur). */
        val mealMarker: Boolean,
        /** Konservativ das MINIMUM der letzten [PERSIST_N] Antriebe - der
         *  juengste Wert allein waere der rauschanfaelligste. Nur bei
         *  [active]. */
        val driveMgdlPerMin: Double?,
        val remainingU: Double,
        val reason: String,
    )

    fun evaluate(input: Input): Result {
        val remaining = max(0.0, input.envelopeU - input.spentU)
        fun closed(reason: String) = Result(false, input.mealMarkerActive, null, remaining, reason)

        if (!input.enabled) return closed("DISABLED")
        // Uebergabe VOR allen anderen Gates: ein bestaetigter Anstieg gehoert
        // der normalen Rampe, auch wenn der Kanal gerade offen war.
        if (input.rSignedMgdlPerMin.isFinite() && input.rSignedMgdlPerMin >= input.thresholdMgdlPerMin)
            return closed("R_CONFIRMED")
        if (input.q1Outlier) return closed("OUTLIER")

        val persistN = if (input.mealMarkerActive) PERSIST_N_MARKER else PERSIST_N
        val last = input.samples.takeLast(persistN)
        if (last.size < persistN) return closed("TOO_FEW_SAMPLES")
        for (i in 1 until last.size) {
            if (last[i].tsMs - last[i - 1].tsMs > MAX_GAP_MS) return closed("GAP")
            if (last[i].tsMs <= last[i - 1].tsMs) return closed("NOT_ASCENDING")
        }
        if (last.any { !it.ukfRatePerMin.isFinite() || !it.fastDriveMgdlPerMin.isFinite() })
            return closed("NOT_FINITE")
        // Das kinematische Gate: die ROHE Rate muss ueber der Schwelle liegen.
        if (last.any { it.ukfRatePerMin < input.thresholdMgdlPerMin }) return closed("UKF_BELOW_THR")
        if (remaining <= 0.0) return closed("ENVELOPE_SPENT")

        return Result(
            active = true,
            mealMarker = input.mealMarkerActive,
            driveMgdlPerMin = last.minOf { it.fastDriveMgdlPerMin },
            remainingU = remaining,
            reason = if (input.mealMarkerActive) "OPEN_MARKER" else "OPEN",
        )
    }
}
