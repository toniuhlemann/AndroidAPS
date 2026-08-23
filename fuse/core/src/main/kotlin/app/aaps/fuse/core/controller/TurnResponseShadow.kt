package app.aaps.fuse.core.controller

import kotlin.math.max

/**
 * DOSIERNEUTRALE Wendeklassifikation fuer den kontrollierten Tau-Shadow.
 *
 * Sie beantwortet nur, ob der schnelle, BGI-bereinigte Antrieb gegenueber
 * dem 18-min-Theil-Sen gerade nachhaltig nach oben oder unten dreht. Keine
 * Entscheidung im Regler liest dieses Ergebnis; der Runner exportiert damit
 * R60/R55/R50/R45 und einen adaptiven Kandidaten zum Offline-Vergleich.
 *
 * Die Autoritaet ist absichtlich asymmetrisch:
 *  - TURNING_UP darf im Shadow nur die MITTELBAHN heben. Untergrenze, Guard
 *    und Tail bleiben auf dem produktiven Zeugnis.
 *  - TURNING_DOWN darf nur die schnelle Bremsbahn verkuerzen. Sie kann damit
 *    eine Kandidatenmenge verkleinern, niemals vergroessern.
 *  - negative schnelle Drives behalten den PRODUKTIVEN Negativ-Tau. Ihr
 *    schnellerer Zerfall wuerde die Sicherheitsbahn anheben und waere damit
 *    kein Brems-Shadow.
 *  - seit dem 22.08. gilt fuer die positiven Varianten: sie duerfen den
 *    produktiven Tau nur KUERZEN, nie verlaengern. Faehrt die Produktion im
 *    Rebound-Fenster bereits min(driveTauMin, 15), gilt deren Tau - die
 *    fruehere harte 45-60-Matrix war dort keine Baseline (Review 22.08.).
 *    Die Ableitung steht im Runner an EINER Stelle: abgelesen aus dem
 *    produktiven PredictorInput, nicht nachgebaut.
 */
object TurnResponseShadow {

    /** Algorithmusstand der Matrix fuer die Auswertung - bei jeder
     *  Aenderung an Varianten oder Rechenweg hochzaehlen. */
    const val METHOD_ID = "TAU-R60-R55-R50-R45-ADAPTIVE-DOWN-v1"

    const val SAMPLE_COUNT = 3
    const val MAX_GAP_MS = 90_000L
    const val MIN_TOTAL_CHANGE_MGDL_PER_MIN = 0.20
    const val MAIN_TAU_MIN = 60
    const val ADAPTIVE_RESTRAINT_TAU_MIN = 50

    val STATIC_RESTRAINT_TAUS_MIN: List<Int> = listOf(60, 55, 50, 45)

    data class Sample(
        val tsMs: Long,
        val rawRateMgdlPerMin: Double,
        val fastDriveMgdlPerMin: Double,
    )

    enum class Phase { UNKNOWN, ALIGNED, TURNING_UP, TURNING_DOWN }

    enum class Reason {
        SIGNAL_UNHEALTHY,
        OUTLIER,
        SLOW_NOT_FINITE,
        TOO_FEW_SAMPLES,
        GAP,
        NOT_ASCENDING,
        SAMPLE_NOT_FINITE,
        NEGATIVE_DRIVE_PRESERVED,
        NO_CONFIRMED_TURN,
        UP_CONFIRMED,
        DOWN_CONFIRMED,
    }

    data class Classification(
        val phase: Phase,
        val reason: Reason,
        val slowDriveMgdlPerMin: Double?,
        val fastDriveMgdlPerMin: Double?,
        /** aktuell minus 1/2/3 Minuten zuvor. */
        val delta1MgdlPerMin: Double?,
        val delta2MgdlPerMin: Double?,
        val delta3MgdlPerMin: Double?,
        /** Nur fuer TURNING_UP; die untere Bahn wird damit NICHT gehoben. */
        val upwardMeanDriveMgdlPerMin: Double?,
        /** 50 nur bei bestaetigter positiver Abwaertswende, sonst 60. */
        val adaptiveRestraintTauMin: Int,
    )

    data class Variant(
        val name: String,
        val requestedRestraintTauMin: Int,
        /** Tatsaechlich gerechneter Tau: bei negativem Drive der produktive
         *  Negativ-Tau, sonst min(angefragt, produktiver Positiv-Tau) - im
         *  Rebound-Fenster also z. B. 15 statt der angefragten 45-60. */
        val restraintTauMin: Int,
        val adaptive: Boolean,
        val predAtReleaseMgdl: Double?,
        /** Sicherheits-Unterkante am Freigabehorizont, getrennt von der Mittelbahn. */
        val safetyLowerAtReleaseMgdl: Double?,
        val minSafetyLowerMgdl: Double?,
        val tailHeadroomU: Double?,
        val insulinReqU: Double?,
        val ratioCapU: Double?,
        /** Ergebnis der Kandidatensuche, VOR Prime/Foundation und Gates. */
        val candidateSmbU: Double?,
        val candidateBinding: String?,
        val candidateReject: String?,
    )

    /**
     * ADAPTIVE-DOWN als SCHATTEN (Toni 22.08.). Der 5b-Replay hat gezeigt:
     * am Korrektur-AUSGANG ist nichts mehr zu holen (vier Bremskandidaten,
     * 0,00 U ueber 39,5h - die Tuer ist durch Guard/SAFETY_HOLD/Riegel schon
     * zu). Das Tief-Insulin fliesst FRUEHER, waehrend abbremsender Anstiege,
     * lizenziert vom langsamen r gegen kollabierenden fastDrive (6,10 U in
     * 39,5h; Schadensblock 20.08. 18:47 mit 2,90 U gegen den fast
     * signaturgleichen Gutfall 21.08. 13:59 mit Peak 196 danach). Per Zyklus
     * sind die beiden NICHT trennbar - nur der VERLAUF trennt sie. Deshalb
     * drei Ausloese-Varianten derselben einseitigen Senkung, alle
     * dosierneutral, zum Offline-Vergleich:
     *
     *   BASE  die aktuelle Mittelbahn (Referenz, keine Senkung)
     *   NOW   min(r, fastDrive) sofort, sobald fast < slow
     *   P2    dito, aber erst nach ZWEI zusammenhaengenden Rueckgaengen
     *   P3    dito nach DREI Rueckgaengen
     *
     * Die bestehende Persistenzregel des Klassifikators (2 Rueckgaenge +
     * 0,20-Gesamtabfall + slow >= Rampe) ist offline aus P2 und dem separat
     * exportierten `phase` synthetisierbar - sie braucht keine eigene Zeile.
     *
     * AUTORITAET WIE BEIM UP-SPIEGEL, nur andersherum: gesenkt wird der
     * BEDARF; die PRODUKTIVEN Zertifikate (Guard, Tail, Bremsbahn des
     * Reglers) bleiben unangetastet. Innerhalb der Varianten-Zeile ziehen
     * untere und prior-freie Kante mit der Mittelbahn mit (Bandordnung) -
     * die Zeile rechnet ihren Guard damit auf der gesenkten Bahn und ist
     * STRENGER als produktiv. [DownVariant.avoidedSmbU] ist deshalb eine
     * OBERGRENZE; `candidateBinding` trennt offline Bedarfssenkung von
     * Guard-Bindung, `insulinReqU` traegt die reine Bedarfsgroesse.
     */
    data class DownVariant(
        val name: String,
        /** War die Ausloesebedingung dieser Variante in diesem Zyklus wahr?
         *  Bei false traegt die Zeile die Referenzwerte der Mittelbahn. */
        val triggered: Boolean,
        val declineStreak: Int,
        /** Der Mittelantrieb dieser Zeile [mg/dl/min] - gesenkt nur bei
         *  triggered. */
        val midDriveMgdlPerMin: Double?,
        /** Die MITTELBAHN am Freigabehorizont - absichtlich OHNE das min()
         *  mit der Bremsbahn (anders als [Variant.predAtReleaseMgdl] und
         *  `decision.predAtReleaseMgdl`): eine tief bindende Bremsbahn
         *  wuerde die Senkung sonst unsichtbar machen, und gemessen werden
         *  soll genau sie. */
        val predAtReleaseMgdl: Double?,
        val insulinReqU: Double?,
        val candidateSmbU: Double?,
        val candidateBinding: String?,
        val candidateReject: String?,
        /** Referenzkandidat minus Variantenkandidat, nie negativ - "was die
         *  Senkung in diesem Zyklus vermieden haette" - auf KANDIDATENSTUFE.
         *  Der 14:10-Livefall hat gezeigt, dass das zu frueh gemessen ist:
         *  produktiv gingen 0,10 U hinaus (Kandidat + Sub-Step-Uebertrag),
         *  die Zeile sah 0,05 und meldete avoided = 0. */
        val avoidedSmbU: Double?,
        /**
         * DIE ENDMENGE DER ZEILE (Pruefauftrag 2, Toni 22.08.): Kandidat
         * plus Lane-eigener Sub-Step-Uebertrag (dieselbe reine Funktion,
         * eigener Uebertragszaehler je Lane), danach dieselbe
         * Wirkungspruefung wie produktiv. Publikations- und Pumpengates
         * wirken auf alle Lanes gleich und bleiben aussen vor.
         */
        val endU: Double? = null,
        /** Tatsaechlich PUBLIZIERTE produktive Menge minus [endU], nie
         *  negativ - "was die Senkung an der Endmenge vermieden haette".
         *  Der Obergrenzen-Charakter bleibt (Lane-Guard rechnet auf der
         *  gesenkten Bahn); fuer Entscheidungen weiterhin insulinReqU und
         *  candidateBinding daneben lesen. */
        val avoidedEndU: Double? = null,
    )

    /**
     * Zusammenhaengende Rueckgaenge des schnellen Drives am Reihenende.
     * 0 = der letzte Schritt war kein Rueckgang. Eine Luecke > [MAX_GAP_MS]
     * oder ein nicht-endlicher Wert beendet die Zaehlung - eine
     * ueberbrueckte Luecke darf keine Persistenz belegen.
     */
    fun declineStreak(samples: List<Sample>): Int {
        var n = 0
        for (i in samples.size - 1 downTo 1) {
            val dt = samples[i].tsMs - samples[i - 1].tsMs
            if (dt <= 0L || dt > MAX_GAP_MS) break
            val a = samples[i - 1].fastDriveMgdlPerMin
            val b = samples[i].fastDriveMgdlPerMin
            if (!a.isFinite() || !b.isFinite() || b >= a) break
            n++
        }
        return n
    }

    data class Report(
        val classification: Classification,
        val variants: List<Variant>,
        /** Reine Rechenzeit der Variantenmatrix, nicht des Regelpfads. */
        val computeDurationMs: Double,
        /** Leer, wenn die Senkung trivial waere (fast >= slow) oder kein
         *  schneller Drive vorliegt. */
        val downVariants: List<DownVariant> = emptyList(),
    )

    fun classify(
        samples: List<Sample>,
        slowDriveMgdlPerMin: Double,
        riseThresholdMgdlPerMin: Double,
        signalHealthy: Boolean,
        q1Outlier: Boolean,
    ): Classification {
        fun unknown(reason: Reason) = Classification(
            Phase.UNKNOWN, reason,
            slowDriveMgdlPerMin.takeIf { it.isFinite() },
            samples.lastOrNull()?.fastDriveMgdlPerMin?.takeIf { it.isFinite() },
            null, null, null, null, MAIN_TAU_MIN,
        )
        if (!signalHealthy) return unknown(Reason.SIGNAL_UNHEALTHY)
        if (q1Outlier) return unknown(Reason.OUTLIER)
        if (!slowDriveMgdlPerMin.isFinite() || !riseThresholdMgdlPerMin.isFinite())
            return unknown(Reason.SLOW_NOT_FINITE)

        val last = samples.takeLast(SAMPLE_COUNT)
        if (last.size < SAMPLE_COUNT) return unknown(Reason.TOO_FEW_SAMPLES)
        for (i in 1 until last.size) {
            val dt = last[i].tsMs - last[i - 1].tsMs
            if (dt <= 0L) return unknown(Reason.NOT_ASCENDING)
            if (dt > MAX_GAP_MS) return unknown(Reason.GAP)
        }
        if (last.any { !it.rawRateMgdlPerMin.isFinite() || !it.fastDriveMgdlPerMin.isFinite() })
            return unknown(Reason.SAMPLE_NOT_FINITE)

        val now = last.last()
        fun prior(minutes: Int): Sample? {
            val target = now.tsMs - minutes * 60_000L
            return samples
                .asSequence()
                .filter { it.tsMs < now.tsMs && kotlin.math.abs(it.tsMs - target) <= MAX_GAP_MS }
                .minByOrNull { kotlin.math.abs(it.tsMs - target) }
        }
        val d1 = prior(1)?.let { now.fastDriveMgdlPerMin - it.fastDriveMgdlPerMin }
        val d2 = prior(2)?.let { now.fastDriveMgdlPerMin - it.fastDriveMgdlPerMin }
        val d3 = prior(3)?.let { now.fastDriveMgdlPerMin - it.fastDriveMgdlPerMin }

        val a = last[0].fastDriveMgdlPerMin
        val b = last[1].fastDriveMgdlPerMin
        val c = last[2].fastDriveMgdlPerMin
        val total = c - a
        val monotoneUp = b > a && c > b
        val monotoneDown = b < a && c < b

        val down = c > 0.0 &&
            slowDriveMgdlPerMin >= riseThresholdMgdlPerMin &&
            c < slowDriveMgdlPerMin &&
            monotoneDown && -total >= MIN_TOTAL_CHANGE_MGDL_PER_MIN
        if (down) return Classification(
            Phase.TURNING_DOWN, Reason.DOWN_CONFIRMED, slowDriveMgdlPerMin, c,
            d1, d2, d3, null, ADAPTIVE_RESTRAINT_TAU_MIN,
        )

        // Vorzeichenriegel: ein negativer Drive bekommt nie das kuerzere Tau.
        if (c < 0.0) return Classification(
            Phase.ALIGNED, Reason.NEGATIVE_DRIVE_PRESERVED, slowDriveMgdlPerMin, c,
            d1, d2, d3, null, MAIN_TAU_MIN,
        )

        val up = now.rawRateMgdlPerMin >= riseThresholdMgdlPerMin &&
            c > slowDriveMgdlPerMin && monotoneUp &&
            total >= MIN_TOTAL_CHANGE_MGDL_PER_MIN
        if (up) return Classification(
            Phase.TURNING_UP, Reason.UP_CONFIRMED, slowDriveMgdlPerMin, c,
            d1, d2, d3,
            // Wie OnsetChannel: konservativ das Minimum der bestaetigenden
            // schnellen Werte, nie unter dem langsamen Antrieb.
            max(slowDriveMgdlPerMin, last.minOf { it.fastDriveMgdlPerMin }),
            MAIN_TAU_MIN,
        )

        return Classification(
            Phase.ALIGNED, Reason.NO_CONFIRMED_TURN, slowDriveMgdlPerMin, c,
            d1, d2, d3, null, MAIN_TAU_MIN,
        )
    }
}
