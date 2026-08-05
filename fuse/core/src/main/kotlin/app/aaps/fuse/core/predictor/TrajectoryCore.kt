package app.aaps.fuse.core.predictor

import kotlin.math.abs

/**
 * Der K2-P-Trajektorienkern (Spec v0.1 + v0.1.1 + v0.1.2).
 *
 * Rechnet ZWEI Bahnen (mean und lower) ueber einen Horizont und liefert deren
 * Minimum. Ein Endwert allein genuegt nicht: die Bahn kann ein Tief durchlaufen
 * und danach wieder steigen — genau der Fall, der spaeter eine Dosis verbietet.
 *
 * Die oeffentliche API liefert bewusst KEINE Dosis, keine Rate und keine Dauer.
 */
object TrajectoryCore {

    private const val STEP_MS = 60_000L

    /** Toleranz der Rasterpruefung (R71-A/Q8). */
    const val GRID_TOLERANCE_MS = 1_000L

    fun predict(input: PredictorInput): PredictorOutcome {
        val t = input.trajectory
        val anchor = input.predictionAnchorTs

        // R74-F5: fail-closed BEVOR irgendetwas gerechnet wird. Ein
        // Forschungs-Predictor darf bei ungueltiger Eingabe niemals werfen und
        // niemals ein formal gueltiges Ok(NaN) liefern — beides waere schlimmer
        // als eine Ablehnung, weil es wie ein Ergebnis aussieht.
        if (t.points.isEmpty())
            return PredictorOutcome.Rejected(PredictorReason.ARRAY_TOO_SHORT, "points empty")
        if (input.horizonMin <= 0)
            return PredictorOutcome.Rejected(PredictorReason.ARRAY_TOO_SHORT, "horizon=${input.horizonMin}")
        if (!input.bgAtAnchor.isFinite())
            return PredictorOutcome.Rejected(PredictorReason.NON_FINITE_INPUT, "bgAtAnchor")

        // --- Zeitachsen-Gates -------------------------------------------------
        // BG/Q1 haengt an sourceTs, das IOB-Array an seinem eigenen now. Beides
        // ist nicht identisch; ohne Gate koennte schon der erste Schritt vor dem
        // Arrayanfang liegen. Es wird NICHT rueckwaerts extrapoliert.
        val firstQueryTs = anchor + STEP_MS
        val lastQueryTs = anchor + input.horizonMin * STEP_MS
        if (firstQueryTs < t.firstTs)
            return PredictorOutcome.Rejected(
                PredictorReason.SKEW_BEFORE_ARRAY_START,
                "firstQuery=$firstQueryTs < arrayFirst=${t.firstTs}",
            )
        if (lastQueryTs > t.lastTs)
            return PredictorOutcome.Rejected(
                PredictorReason.ARRAY_TOO_SHORT,
                "horizon reaches $lastQueryTs, array ends ${t.lastTs} (spanMin=${t.spanMin})",
            )

        // --- Eingaben pruefen -------------------------------------------------
        var prevTs = Long.MIN_VALUE
        var gridStepMs = 0L
        for (p in t.points) {
            if (!p.activity.isFinite() || !p.iob.isFinite() || !p.basalIob.isFinite())
                return PredictorOutcome.Rejected(PredictorReason.NON_FINITE_INPUT, "point ${p.timeMs}")
            if (p.timeMs <= prevTs)
                return PredictorOutcome.Rejected(PredictorReason.NON_MONOTONIC_TIMESTAMPS, "at ${p.timeMs}")
            if (prevTs != Long.MIN_VALUE) {
                val step = p.timeMs - prevTs
                if (gridStepMs == 0L) gridStepMs = step
                else if (Math.abs(step - gridStepMs) > GRID_TOLERANCE_MS)
                    return PredictorOutcome.Rejected(
                        PredictorReason.GRID_MISMATCH,
                        "step ${step}ms != ${gridStepMs}ms at ${p.timeMs}",
                    )
            }
            prevTs = p.timeMs
            // NUR der Betrag wird geprueft: negative Aktivitaet ist nach einer
            // Zero-/Low-TBR gueltige Physik (netBasalRate = rate - basalRate) und
            // erzeugt ueber bgi = -activity*isf korrekt positives BGI.
            input.bounds.maxAbsActivityUPerMin?.let { lim ->
                if (abs(p.activity) > lim)
                    return PredictorOutcome.Rejected(PredictorReason.ACTIVITY_OUT_OF_BOUNDS, "|${p.activity}| > $lim")
            }
        }
        input.bounds.maxAbsDriveMgdlPerMin?.let { lim ->
            if (abs(input.drive.meanMgdlPerMin) > lim || abs(input.drive.lowerMgdlPerMin) > lim)
                return PredictorOutcome.Rejected(PredictorReason.DRIVE_OUT_OF_BOUNDS, "drive beyond $lim")
        }

        // --- Integration ------------------------------------------------------
        val pts = ArrayList<TrajectoryPoint>(input.horizonMin)
        var meanBg = input.bgAtAnchor
        var lowerBg = input.bgAtAnchor
        var minMean = input.bgAtAnchor
        var minLower = input.bgAtAnchor
        var timeToMinLower = 0

        for (i in 1..input.horizonMin) {
            val ts = anchor + i * STEP_MS
            val sMin = i.toDouble()

            val activity = interpolateActivity(t.points, ts)
                ?: return PredictorOutcome.Rejected(PredictorReason.GRID_MISMATCH, "no activity at $ts")
            val isf = isfAt(input.isfSlots, ts)
                ?: return PredictorOutcome.Rejected(PredictorReason.MISSING_ISF_SLOT, "no ISF slot at $ts")
            // NaN entkommt einem Bereichsvergleich: sowohl `<` als auch `>` sind
            // fuer NaN false. Endlichkeit muss deshalb EIGENS geprueft werden.
            if (!isf.isFinite() || isf < input.bounds.minIsfMgdlPerU || isf > input.bounds.maxIsfMgdlPerU)
                return PredictorOutcome.Rejected(PredictorReason.ISF_OUT_OF_BOUNDS, "isf=$isf")

            // Vorzeichentreu, identisch zur gelockten K1-Regel bgiRate = -activity*profileIsf.
            val bgiRate = -activity * isf
            val f = input.decay.factorAt(sMin)
            val dMean = input.drive.meanMgdlPerMin * f
            val dLower = input.drive.lowerMgdlPerMin * f

            // RECHTE Regel: der Wert bei ts gilt fuer das Intervall (ts-1min, ts].
            // Dieselbe Konvention wie cumulativeBgi in K1 — eine zweite waere eine
            // Fehlerquelle ohne Nutzen.
            meanBg += (dMean + bgiRate)
            lowerBg += (dLower + bgiRate)

            if (meanBg < minMean) minMean = meanBg
            if (lowerBg < minLower) { minLower = lowerBg; timeToMinLower = i }

            pts.add(TrajectoryPoint(i, ts, meanBg, lowerBg, bgiRate, dMean, dLower))
        }

        return PredictorOutcome.Ok(
            PredictorResult(
                points = pts,
                minMeanBg = minMean,
                minLowerBg = minLower,
                timeToMinLowerMin = timeToMinLower,
                bgAtHorizonMean = meanBg,
                bgAtHorizonLower = lowerBg,
                lineageKind = t.lineage.lineageKind,
                trajectoryContentHash = t.contentHash,
                iobArraySpanMin = t.spanMin,
                iobArrayGridMin = t.gridMin,
                modelTailBeyondArrayMin = t.modelTailBeyondArrayMin,
                inputSkewMs = t.firstTs - anchor,
            )
        )
    }

    /** Lineare Interpolation zwischen den 5-min-Stuetzstellen; ausserhalb des
     *  Arrays gibt es KEINEN Wert (kein Extrapolieren, Spec §1 P2). */
    internal fun interpolateActivity(points: List<IobPoint>, ts: Long): Double? {
        if (points.isEmpty() || ts < points.first().timeMs || ts > points.last().timeMs) return null
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].timeMs <= ts) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        if (ts == a.timeMs) return a.activity
        if (ts == b.timeMs) return b.activity
        val w = (ts - a.timeMs).toDouble() / (b.timeMs - a.timeMs).toDouble()
        return a.activity + (b.activity - a.activity) * w
    }

    /** ISF des absolut aufgeloesten Blocks; ein Profile-Switch im Horizont wird
     *  dadurch in Live und Replay gleich behandelt. */
    internal fun isfAt(slots: List<IsfSlot>, ts: Long): Double? =
        slots.firstOrNull { ts >= it.startTsInclusive && ts < it.endTsExclusive }?.isfMgdlPerU
}
