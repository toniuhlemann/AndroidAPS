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
 *  - negative schnelle Drives bleiben bei Tau 60. Ihr schnellerer Zerfall
 *    wuerde die Sicherheitsbahn anheben und waere damit kein Brems-Shadow.
 */
object TurnResponseShadow {

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
        /** Bei negativem Drive immer 60, unabhaengig vom angefragten Tau. */
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

    data class Report(
        val classification: Classification,
        val variants: List<Variant>,
        /** Reine Rechenzeit der Variantenmatrix, nicht des Regelpfads. */
        val computeDurationMs: Double,
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
