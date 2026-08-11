package app.aaps.fuse.core.signal

/**
 * Vier Zahlen ueber den RAND einer Datenluecke.
 *
 * WOZU: am 10.08. stand nach einer 37-Minuten-Luecke ein Wert von 90 mit
 * FRISCHEM Zeitstempel im Datensatz, drei Minuten spaeter 105. FUSE las daraus
 * +4,21 mg/dl/min und gab 0,85 U in ein Ereignis, das es nicht gab.
 *
 * WARUM VIER UND NICHT EINE: die naheliegende Pruefung - "war die Rate
 * auffaellig" - findet genau diesen Fall NICHT. Der erste Punkt nach der Luecke
 * hat `(90-105)/35 = -0,43` mg/dl/min, voellig unauffaellig. Der SPRUNG kommt
 * drei Minuten spaeter, und dann ist die Luecke schon nicht mehr frisch. Es
 * braucht den ABSTAND zur Luecke ([postGapIndex]) zusammen mit dem SCHRITT.
 *
 * WARUM HIER UND NICHT IN DER SIGNALQUELLE: dort haengt alles an
 * `profileFunction` und `iobCobCalculator` und ist praktisch nicht pruefbar.
 * Diese Rechnung braucht nichts als Zeitstempel und Werte.
 *
 * KEINE REGEL, KEINE SCHWELLE. Tonis eigener Messwert steht dagegen:
 * 4,85 mg/dl/min im Mahlzeitenkopf - ein Plausibilitaetszaun bei 5 haette
 * keinen Abstand und traefe echte Mahlzeiten. Ob aus diesen Zahlen je eine
 * Regel wird, entscheiden Daten.
 */
object PostGapMetrics {

    /**
     * @param gapBeforeMin Abstand der letzten beiden Rohwerte [min]. 0, wenn es
     *   keine zwei gibt.
     * @param stepFromLastMgdl Schritt zum Vorgaenger, VORZEICHENBEHAFTET - ein
     *   Ruecksprung nach oben ist etwas anderes als einer nach unten.
     * @param stepRateActualMgdlPerMin derselbe Schritt ueber den TATSAECHLICHEN
     *   Abstand. Bei einer Luecke ist das etwas voellig anderes als "pro
     *   Minute".
     * @param postGapIndex der wievielte Punkt seit dem Segmentbruch. 1 = der
     *   erste nach der Luecke, also der verdaechtige.
     */
    data class Result(
        val gapBeforeMin: Double,
        val stepFromLastMgdl: Double,
        val stepRateActualMgdlPerMin: Double,
        val postGapIndex: Int,
    )

    /**
     * @param ts aufsteigende Zeitstempel der ROHreihe.
     * @param values zugehoerige Werte, gleiche Laenge.
     * @param segmentStartTs die Bruchkante aus [BgiAdjustedSeries.segmentStart].
     *
     * Gerechnet wird auf der ROHREIHE, nicht auf den bereits beschnittenen
     * Samples: der Punkt VOR dem Schnitt ist genau der, um den es geht.
     */
    fun of(ts: List<Long>, values: List<Double>, segmentStartTs: Long): Result {
        require(ts.size == values.size) { "ts/values unterschiedlich lang: ${ts.size}/${values.size}" }
        if (ts.size < 2) return Result(0.0, 0.0, 0.0, ts.size)
        val gapMin = (ts[ts.size - 1] - ts[ts.size - 2]) / 60_000.0
        val step = values[values.size - 1] - values[values.size - 2]
        return Result(
            gapBeforeMin = gapMin,
            stepFromLastMgdl = step,
            stepRateActualMgdlPerMin = if (gapMin > 0.0) step / gapMin else 0.0,
            postGapIndex = ts.count { it >= segmentStartTs },
        )
    }
}
