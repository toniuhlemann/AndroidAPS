package app.aaps.fuse.plugin

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.utils.MidnightUtils
import app.aaps.fuse.core.observer.ActivityValidity
import app.aaps.fuse.core.signal.BgiAdjustedSeries
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
        /** `null` heisst: nicht berechenbar (zu wenige Punkte/Paare) — NICHT 0. */
        val rSigned: Double?,
        val activity: ActivityValidity,
        val samplesUsed: Int,
        val rawSeriesSize: Int,
        val q1Outlier: Boolean,
    )

    sealed interface Outcome {

        data class Ok(val signal: Signal) : Outcome
        /** Kein Ersatzwert, kein Nullwert — ein benannter Grund. */
        data class Unavailable(val reason: String) : Outcome
    }

    fun read(): Outcome {
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
        val leading = UkfQ1.leadingEdge(readings.takeLast(UkfQ1.WINDOW_SAMPLES))
            ?: return Outcome.Unavailable("q1 not computable from ${readings.size} points")

        // Ein Sample je Rohpunkt im 18-min-Fenster. q1 wird KAUSAL je Punkt
        // gerechnet: jeder Punkt sieht nur seine eigene Vergangenheit.
        val windowStart = sourceTs - BgiAdjustedSeries.WINDOW_MS
        val samples = ArrayList<BgiAdjustedSeries.Sample>()
        for ((index, point) in readings.withIndex()) {
            if (point.tsMs < windowStart) continue
            val prefix = readings.subList(maxOf(0, index + 1 - UkfQ1.WINDOW_SAMPLES), index + 1)
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
        if (samples.isEmpty()) return Outcome.Unavailable("no samples in window")

        val adjusted = BgiAdjustedSeries.adjust(samples)
        val rSigned = BgiAdjustedSeries.theilSen(adjusted, sourceTs)

        return Outcome.Ok(
            Signal(
                sourceTs = sourceTs,
                rawBg = newest.value,
                q1 = leading.glucose,
                rSigned = rSigned,
                // Die Aktivitaet wurde AM Zeitpunkt selbst gerechnet, nicht per
                // LOCF uebernommen — sie ist damit definitionsgemaess kausal
                // und aktuell.
                activity = ActivityValidity.VALID,
                samplesUsed = samples.size,
                rawSeriesSize = readings.size,
                q1Outlier = leading.outlier,
            )
        )
    }
}
