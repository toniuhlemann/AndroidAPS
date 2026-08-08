package app.aaps.fuse.plugin

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.utils.MidnightUtils
import app.aaps.fuse.core.observer.ActivityValidity
import app.aaps.fuse.core.signal.BgiAdjustedSeries
import app.aaps.fuse.core.signal.SignalWindow
import app.aaps.fuse.core.signal.UkfQ1

/**
 * Die beiden Signalgroessen, die der Observer bereits GERECHNET erwartet:
 * `q1` und `rSigned`.
 *
 * Zwei Fallen stecken hier, beide im Quellcode nachgeprueft:
 *
 * 1. DIE AKTIVITAET KOMMT NICHT AUS DEM IOB-ARRAY.
 *    `calculateIobArrayForSMB` liefert ausschliesslich ZUKUNFT
 *    (`val t = now + i * 5 * 60000`), [BgiAdjustedSeries] braucht dagegen die
 *    letzten 18 Minuten. Wer das Array nimmt, bekommt entweder keine Werte oder
 *    `ActivityValidity.FUTURE` — und der Observer verlaesst WARMUP nie.
 *    Die einzige oeffentliche Methode mit freiem Zeitstempel ist
 *    `calculateFromTreatmentsAndTemps(toTime, profile)`; ihr internes Raster ist
 *    bereits die Minute (`roundUpTime` rundet auf 60_000 ms auf).
 *
 * 2. DIE ROHREIHE IST NEUESTE-ZUERST.
 *    `getBgReadingsDataTableCopy()` liefert absteigend, `BgiAdjustedSeries.adjust`
 *    WIRFT bei absteigender Reihenfolge (`require(dtMin >= 0.0)`). Deshalb wird
 *    hier einmal explizit aufsteigend sortiert statt sich auf eine Annahme zu
 *    verlassen.
 *
 * KOSTEN, ehrlich benannt: ein Cache-Miss in `calculateFromTreatmentsAndTemps`
 * kostet drei blockierende Room-Abfragen ueber ein DIA-breites Fenster. Bei
 * 1-min-CGM entwertet praktisch jede Minute den Cache der letzten Minuten, es
 * bleiben also einige echte Misses je Zyklus. Das Fenster ist deshalb genau
 * [BgiAdjustedSeries.WINDOW_MS] breit und keine Minute mehr.
 */
class FuseSignalSource(
    private val iobCobCalculator: IobCobCalculator,
    private val profileFunction: ProfileFunction,
) {

    data class Signal(
        val sourceTs: Long,
        /** Der KALIBRIERTE ROHWERT am Anker, ungefiltert. Die Sprungerkennung
         *  des Observers laeuft darauf: ein Kalibriersprung soll gesehen werden,
         *  bevor q1 ihn glaettet. */
        val rawBg: Double,
        val q1: Double,
        /** `null` heisst: nicht berechenbar (zu wenige Punkte/Paare) — NICHT 0.
         *
         *  TRAEGHEIT, gemessen: nach einem Steigungssprung folgt der Median mit
         *  0 % bis Minute 5, 50 % bei Minute 9, 100 % ab Minute 13. Theil-Sen
         *  ist robust gegen Rauschen — am Mahlzeiten-Onset heisst Robustheit
         *  Langsamkeit. Deshalb stehen daneben zwei schnellere Maasse. */
        val rSigned: Double?,
        /**
         * Die EIGENE Ratenschaetzung des Filters (`UkfQ1.Result.ratePerMin`).
         *
         * Sie wurde bisher weggeworfen, obwohl der Filter sie in jedem Zyklus
         * mitrechnet — ein Kalman-Zustand, kein Batch-Median, und damit
         * konstruktionsbedingt schneller als [rSigned]. Kostet nichts.
         */
        val ukfRatePerMin: Double,
        /**
         * Steigung der ROHEN Reihe ueber die letzten Minuten — das naivste und
         * schnellste Maass, und ungefaehr das, womit autoISF ueber `delta`
         * arbeitet. Es ist hier NICHT im Regelpfad; es steht da, damit
         * messbar wird, wieviel Vorsprung ein kurzes Fenster wirklich hat.
         */
        val rawSlopePerMin: Double?,
        /** Das vom UKF GELERNTE Messrauschen R - die einzige eingebaute
         *  Signalqualitaetszahl. Bisher nirgends exportiert; eine spaetere
         *  Qualitaets-Schranke braucht genau diese Reihe zum Kalibrieren. */
        val ukfLearnedR: Double,
        /**
         * Insulinaktivitaet und ISF AM ANKER — sie werden gebraucht, um eine
         * rohe Rate BGI-zu-bereinigen:
         *
         *     bereinigt = roh + activity * isf
         *
         * `rSigned` ist bereinigt, [ukfRatePerMin] und [rawSlopePerMin] sind es
         * NICHT. Wer eine der beiden ungefiltert als Antrieb einsetzt, laesst
         * `TrajectoryCore` die Insulinwirkung ein ZWEITES Mal abziehen.
         */
        val activityAtAnchor: Double,
        val isfAtAnchor: Double,
        /** Die BGI-korrigierte Reihe des Fensters. Sie wird durchgereicht, damit
         *  der Zyklus die Untergrenze mit dem eingestellten Quantil bilden kann,
         *  OHNE dass die Signalquelle Preferences liest — die Fensterbildung
         *  bleibt so eine Sache, die Bandpolitik eine andere. */
        val adjusted: List<BgiAdjustedSeries.AdjustedPoint>,
        val activity: ActivityValidity,
        val samplesUsed: Int,
        val rawSeriesSize: Int,
        val q1Outlier: Boolean,
        /** Was den Reihenanfang gesetzt hat: NONE / SENSOR_CHANGE /
         *  CALIBRATION_START. Gehoert in den Export - ein q1 aus einem frisch
         *  begrenzten Fenster ist eine andere Zahl als eines aus voller
         *  Historie, und das darf man hinterher nicht raten muessen. */
        val boundedBy: SignalWindow.Bound,
        val windowFromTs: Long,
    )

    sealed interface Outcome {

        data class Ok(val signal: Signal) : Outcome
        /** Kein Ersatzwert, kein Nullwert — ein benannter Grund. */
        data class Unavailable(val reason: String) : Outcome
    }

    /**
     * @param sensorStartTs Beginn der Sensorlaufzeit, `<= 0` = unbekannt.
     * @param calibrationStartTs Beginn der Kalibrierung, `<= 0` = unbekannt.
     */
    companion object {

        /** Fenster des Rohvergleichsmaasses. 5 min, weil das die Groesse ist,
         *  mit der AAPS' `delta` arbeitet — der Vergleich soll fair sein. */
        const val RAW_SLOPE_WINDOW_MS = 5 * 60_000L
    }

    /** Einfache Sekante ueber das Rohfenster. `null` bei zu wenig Abstand —
     *  kein Ersatzwert. */
    private fun rawSlope(points: List<UkfQ1.Point>, nowTs: Long): Double? {
        val from = nowTs - RAW_SLOPE_WINDOW_MS
        val first = points.firstOrNull { it.tsMs >= from } ?: return null
        val dtMin = (nowTs - first.tsMs) / 60_000.0
        if (dtMin < 2.0) return null
        return (points.last().value - first.value) / dtMin
    }

    fun read(sensorStartTs: Long, calibrationStartTs: Long): Outcome {
        // Aufsteigend, und nur kalibrierte Rohwerte: `raw` ist der Eingang, den
        // auch der Fork-Q1 nutzt. `value` waere der (moeglicherweise schon
        // geglaettete) Anzeigewert, `noise` ist entgegen dem Namen der
        // huckepack transportierte UNKALIBRIERTE Sensorwert — beides waere hier
        // falsch.
        val readings = iobCobCalculator.ads.getBgReadingsDataTableCopy()
            .asSequence()
            .mapNotNull { gv -> gv.raw?.takeIf { it > 39.0 }?.let { UkfQ1.Point(gv.timestamp, it) } }
            .sortedBy { it.tsMs }
            .toList()
        if (readings.isEmpty()) return Outcome.Unavailable("no raw glucose values")

        val newest = readings.last()
        val sourceTs = newest.tsMs

        // R60-F1: die Reihe beginnt am Regimewechsel, nicht am Datenanfang.
        // EINMAL vorne angewandt und nicht in der Praefixschleife - das ist
        // wirkungsgleich und kann nicht in einem der ~19 Durchlaeufe vergessen
        // werden: jedes Praefix ist ein Suffix der aufsteigenden Reihe, also gilt
        //   praefix(beschnitten) = praefix(voll) geschnitten mit {ts >= fromTs}.
        val window = SignalWindow.of(sourceTs, sensorStartTs, calibrationStartTs)
        val series = readings.filter { it.tsMs >= window.fromTs }
        if (series.isEmpty()) return Outcome.Unavailable("window empty after ${window.label} @${window.fromTs}")

        val leading = UkfQ1.leadingEdge(series.takeLast(UkfQ1.WINDOW_SAMPLES))
            ?: return Outcome.Unavailable("q1 not computable from ${series.size} points (${window.label})")

        // Ein Sample je Rohpunkt im 18-min-Fenster. q1 wird KAUSAL je Punkt
        // gerechnet: jeder Punkt sieht nur seine eigene Vergangenheit.
        // Audit R95 NEU-03: die r-Reihe beginnt im juengsten LUECKENFREIEN
        // Segment (dt > 3 min = Bruch) - vorher ueberspannte der Theil-Sen
        // CGM-Luecken und war eine Minute nach der Luecke wieder dosierfaehig.
        // q1/UKF bleiben unbeschnitten (eigene, dt-bewusste Filterung); nur r
        // faellt bis zur Segmentreife benannt aus ("drive not estimable").
        val windowStart = BgiAdjustedSeries.segmentStart(
            series.map { it.tsMs }, sourceTs - BgiAdjustedSeries.WINDOW_MS
        )
        val samples = ArrayList<BgiAdjustedSeries.Sample>()
        for ((index, point) in series.withIndex()) {
            if (point.tsMs < windowStart) continue
            val prefix = series.subList(maxOf(0, index + 1 - UkfQ1.WINDOW_SAMPLES), index + 1)
            val q1 = UkfQ1.leadingEdge(prefix)?.glucose ?: continue
            val profile = profileFunction.getProfile(point.tsMs)
                ?: return Outcome.Unavailable("no profile at ${point.tsMs}")
            val isf = profile.getIsfMgdlTimeFromMidnight(MidnightUtils.secondsFromMidnight(point.tsMs))
            if (!isf.isFinite() || isf <= 0.0) return Outcome.Unavailable("isf=$isf at ${point.tsMs}")
            // NUR lesen: der Rueckgabewert kann eine Cache-REFERENZ sein
            // (IobTotal ist eine data class mit var-Feldern). Wer daran
            // schreibt, veraendert den Cache der ganzen App.
            val activity = iobCobCalculator.calculateFromTreatmentsAndTemps(point.tsMs, profile).activity
            if (!activity.isFinite()) return Outcome.Unavailable("activity not finite at ${point.tsMs}")
            samples.add(BgiAdjustedSeries.Sample(point.tsMs, q1, activity, isf))
        }
        if (samples.isEmpty()) return Outcome.Unavailable("no samples in window (${window.label})")

        val adjusted = BgiAdjustedSeries.adjust(samples)
        val rSigned = BgiAdjustedSeries.theilSen(adjusted, sourceTs)

        return Outcome.Ok(
            Signal(
                sourceTs = sourceTs,
                rawBg = newest.value,
                q1 = leading.glucose,
                rSigned = rSigned,
                // C1b (Codex-Adjudication): nach einem Segmentbruch traegt der
                // erste Punkt KEINE Rate. NaN statt der frueheren 0.0 - alle
                // Konsumenten pruefen bereits isFinite() und werden dadurch
                // fail-closed (Onset-Sample entfaellt, aktivierte Bremsbahn
                // bricht den Zyklus ab), statt eine erfundene Null zu glauben.
                ukfRatePerMin = if (leading.rateUnavailable) Double.NaN else leading.ratePerMin,
                ukfLearnedR = leading.learnedR,
                activityAtAnchor = samples.last().activity,
                isfAtAnchor = samples.last().profileIsf,
                rawSlopePerMin = rawSlope(readings, sourceTs),
                adjusted = adjusted,
                // Die Aktivitaet wurde AM Zeitpunkt selbst gerechnet, nicht per
                // LOCF uebernommen — sie ist damit definitionsgemaess kausal
                // und aktuell.
                activity = ActivityValidity.VALID,
                samplesUsed = samples.size,
                rawSeriesSize = series.size,
                q1Outlier = leading.outlier,
                boundedBy = window.bound,
                windowFromTs = window.fromTs,
            )
        )
    }
}
