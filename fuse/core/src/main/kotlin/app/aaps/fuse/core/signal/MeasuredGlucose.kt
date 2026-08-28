package app.aaps.fuse.core.signal

/**
 * DIE GEMESSENE GLUKOSEREIHE - roh UND gefiltert, je Punkt.
 *
 * WARUM ES SIE BRAUCHT (Toni 28.08.). Am Aufrufpunkt des Reglers gab es bis
 * hierher keine Reihe, auf der sich Glukose-Stabilitaet beurteilen laesst.
 * Die einzige punktweise Reihe war [BgiAdjustedSeries.AdjustedSeries], und die
 * traegt `q1 - cumulativeBgi`, also den BGI-BEREINIGTEN ANTRIEB. Bei
 * Fruehstuecksaktivitaet sind das rund +0,7 mg/dl/min Versatz gegenueber der
 * Glukose - das Fuenfzigfache der Rate, um die es bei der Frage "faellt der
 * Zucker noch?" geht. Ein Stabilitaetsnachweis darauf wuerde einen
 * INSULINGETRIEBENEN echten Abfall als ruhig lesen. Genau deshalb bleibt die
 * bereinigte Reihe unberuehrt und bekommt diese hier NEBEN sich.
 *
 * ROH UND GEFILTERT SIND ZWEI DINGE, und beide stehen hier (Tonis Korrektur
 * 28.08.): `q1` ist bereits UKF-Ausgabe, keine Messung. Wer nur q1 durchreicht,
 * hat "gemessene und gefilterte Entwicklung" nicht erfuellt, sondern zweimal
 * dieselbe gefilterte Sicht. Der Rohwert ist ganzzahlig (gemessen: 155 von 155
 * Werten am 28.08., kleinster beobachteter Schritt 1 mg/dl) - seine Aufloesung
 * ist damit bekannt und nachpruefbar, waehrend q1 stetig ist.
 *
 * WAS DIESE KLASSE NICHT TUT: sie bewertet nichts. Sie traegt Punkte, Zeit und
 * Segmentidentitaet. Jede Schwelle, jedes Fenster und jede Toleranz gehoert in
 * den Auswerter, nicht hierher - sonst wandert eine Freigabeentscheidung in die
 * Signalschicht.
 */
data class GlucosePoint(
    val sourceTs: Long,
    /** Der kalibrierte ROHWERT, ungefiltert. Ganzzahlig auf diesem Rig. */
    val rawBg: Double,
    /** Die UKF-Schaetzung ZU DIESEM Punkt, kausal gerechnet (jeder Punkt sieht
     *  nur seine eigene Vergangenheit). */
    val q1: Double,
)

/**
 * Die Reihe im juengsten lueckenfreien R-Segment.
 *
 * @param points aufsteigend nach [GlucosePoint.sourceTs], hoechstens das
 *   18-min-Fenster von [BgiAdjustedSeries.WINDOW_MS], nach einem Segmentbruch
 *   kuerzer.
 * @param segmentStartTs untere Kante des Segments, aus dem die Punkte stammen.
 * @param signalEpochTs die STABILE Segmentidentitaet. Sie wechselt nur bei
 *   einem echten Bruch - anders als eine gleitende Fensterunterkante, die sich
 *   in jedem Zyklus verschiebt und als Identitaet untauglich waere.
 */
class MeasuredGlucose(
    val points: List<GlucosePoint>,
    val segmentStartTs: Long,
    val signalEpochTs: Long,
) {

    val size: Int get() = points.size

    /** Die Punkte der letzten [minutes] Minuten vor [nowTs], aufsteigend.
     *
     *  KEINE Bewertung: wer weniger Punkte zurueckbekommt als er braucht, muss
     *  das selbst als "nicht beurteilbar" behandeln - eine kurze Reihe ist
     *  hier kein Fehler, sondern die ehrliche Auskunft nach einem Bruch. */
    fun lastMinutes(nowTs: Long, minutes: Int): List<GlucosePoint> {
        if (minutes <= 0) return emptyList()
        val von = nowTs - minutes * 60_000L
        return points.filter { it.sourceTs in von..nowTs }
    }

    /**
     * Die Punkte, die ein Fenster von [minutes] Minuten vor [nowTs]
     * TATSAECHLICH ABDECKEN - einschliesslich des KLAMMERPUNKTES, also des
     * letzten Punktes vor der Fensterkante.
     *
     * WARUM DAS NOETIG IST (Toni 28.08.). [lastMinutes] nimmt nur Punkte
     * INNERHALB des Fensters. Bei konstantem 61-Sekunden-Takt liegen sechs
     * Punkte ueber 305 Sekunden; ein Fuenf-Minuten-Fenster wirft den
     * aeltesten heraus, und uebrig bleiben fuenf Punkte ueber 244 Sekunden -
     * ein Fenster, das seine eigene Laenge nie erreicht. Das wiederholt sich
     * in JEDEM Zyklus, auch bei vollkommen konstantem Zucker und beliebig
     * langer Vorgeschichte, und haette damit genau die Wartezeit wieder
     * eingebaut, die hier beseitigt werden sollte.
     *
     * Mit dem Klammerpunkt sind es sechs Punkte ueber 305 Sekunden - das
     * Fenster ist abgedeckt. Die Spanne faellt dadurch etwas laenger aus als
     * [minutes]; wer sie braucht, misst sie am Ergebnis.
     */
    fun windowCovering(nowTs: Long, minutes: Int, minSpanMin: Double): List<GlucosePoint> {
        if (minutes <= 0) return emptyList()
        val kante = nowTs - minutes * 60_000L
        val drin = points.filter { it.sourceTs in kante..nowTs }
        if (drin.size < 2) return drin
        val spanne = (drin.last().sourceTs - drin.first().sourceTs) / 60_000.0
        // NUR WENN NOETIG. Ein bedingungsloser Klammerpunkt verlaengert das
        // Fenster in JEDEM Zyklus um einen Takt und zieht damit einen
        // aelteren Wert herein - "Abfall dann Plateau" wurde dadurch
        // strenger beurteilt, obwohl das Plateau die Fensterlaenge deckte.
        // Gebraucht wird er ausschliesslich dort, wo die Spanne sonst ihre
        // eigene Mindestlaenge nicht erreicht.
        if (spanne >= minSpanMin) return drin
        val klammer = points.lastOrNull { it.sourceTs < kante } ?: return drin
        return listOf(klammer) + drin
    }

    companion object {

        val EMPTY = MeasuredGlucose(emptyList(), 0L, 0L)
    }
}
