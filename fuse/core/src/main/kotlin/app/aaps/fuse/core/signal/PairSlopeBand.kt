package app.aaps.fuse.core.signal

import kotlin.math.floor

/**
 * Die Untergrenze des Antriebs — aus dem Schaetzer selbst, nicht erfunden.
 *
 * WARUM DAS HIER STEHT UND NICHT IN [BgiAdjustedSeries]: jene Datei ist der
 * gelockte Kandidat, jede Abweichung dort waere ein neuer, ungelockter
 * Kandidat. Die Berechnung einer Untergrenze ist Policy und darf sich aendern,
 * ohne die Identitaet des Schaetzers anzutasten.
 *
 * WARUM EIN RANG UND KEINE ZWEITE STATISTIK: die gesamte Verteilungsinformation
 * des Sen-Schaetzers steckt in der sortierten Liste seiner paarweisen
 * Steigungen. Der Median ist bereits ein Rang daraus. Eine Untergrenze als
 * weiterer Rang ist definitorisch derselbe Schaetzer an anderer Stelle — keine
 * hinzuerfundene Konvention.
 *
 * WARUM AUSDRUECKLICH NICHT DAS KANONISCHE SEN-KONFIDENZINTERVALL:
 *
 *  1. Sens `Var(S)` ist ueber ALLE C(n,2) Paare hergeleitet.
 *     [BgiAdjustedSeries] verwirft aber alle Paare unter [BgiAdjustedSeries]
 *     `PAIR_DT_MIN_MS` — die Liste hat 153 statt 171 Eintraege. Der Rangindex
 *     aus `Var(S)` zeigt dann in eine andere Liste als die, in die er
 *     indiziert. Die Ueberdeckung waere nicht ungenau, sondern undefiniert.
 *  2. Sen setzt unabhaengige Beobachtungen voraus. Eingang ist hier q1, die
 *     kausale UKF-Leading-Edge: jeder Punkt ist eine Funktion seines eigenen
 *     Praefixfensters, und die Paare ueberlappen. Positive Autokorrelation
 *     macht `Var(S)` ZU KLEIN und das Intervall ZU SCHMAL — der Fehler zeigt in
 *     die gefaehrliche Richtung.
 *
 * Deshalb: gebaut wird Sens RANGFORM, aber der Rang kommt aus einem
 * DEKLARIERTEN Quantil. Benannt wird es als Quantil, nicht als Konfidenz.
 *
 * EIGENSCHAFT, DIE MAN KENNEN MUSS, BEVOR MAN DIE ERSTEN DATEN DEUTET:
 * Ueber 18 Minuten mit Mahlzeiten-Onset ist die Linearitaetsannahme genau das,
 * was nicht gilt. Eine breite Paarverteilung ist dort ein ECHTES Signal
 * (Kruemmung), kein Rauschen. Das Band ist damit am BREITESTEN genau beim
 * Onset — also dann, wenn der Guard den SMB blockiert, den man gerade braucht —
 * und am schmalsten auf der flachen Linie. Das ist umgekehrt zu einem
 * rauschgetriebenen Band. Wer die erste Beobachtung "der Guard feuert zu oft"
 * als Rauschen fehldiagnostiziert, sucht an der falschen Stelle.
 */
object PairSlopeBand {

    /**
     * Ergebnis. KEINE data class: sie traegt die Paarliste, und `equals` ueber
     * eine Liste von 153 Doubles waere teuer und niemals sinnvoll.
     *
     * `lower <= mean` ist STRUKTURELL garantiert (s. [quantile]) und nicht bloss
     * geprueft — damit kann die Invariante in `DriveEstimate` aus diesem Pfad
     * nie werfen.
     */
    class Estimate(
        val mean: Double,
        val lower: Double,
        val pairCount: Int,
    ) {

        /** Wie weit die Guardbahn unter der Mittelbahn ansetzt [mg/dl/min].
         *  Die Groesse, an der man nach dem ersten Lauf ablesen kann, ob das
         *  Quantil brauchbar gewaehlt war. */
        val spread: Double get() = mean - lower
    }

    /** Zulaessiger Bereich des Quantils in Prozent. Ueber 50 waere es keine
     *  Untergrenze mehr, sondern eine Anhebung der Guardbahn. */
    const val MIN_PCT = 5
    const val MAX_PCT = 50

    /**
     * Kurzform fuer den Grund und den Export. Der Prozentsatz MUSS drinstehen:
     * zwei Laeufe mit q10 und q25 sind verschiedene Groessen und duerfen nicht
     * denselben Namen tragen. `W18`/`DT2` stehen drin, weil Fensterbreite und
     * Paarfilter die Verteilung bestimmen, `RF` fuer die Rangregel, `1` fuer
     * eine spaetere Kalibrierung.
     */
    fun methodId(qPct: Int): String = "TS-PS-Q$qPct-W18-DT2-RF1"

    /**
     * EINE Funktion liefert Mittel UND Untergrenze ODER null.
     *
     * Es gibt bewusst keinen Zustand "Mittel vorhanden, Band fehlt". Ein
     * Rueckfall `zu wenige Paare -> lower = mean` wuerde den heutigen
     * Null-Abstand ausgerechnet in der Lage mit der schlechtesten Datenlage
     * still wiederherstellen.
     */
    fun estimate(points: List<BgiAdjustedSeries.AdjustedPoint>, nowTs: Long, quantilePct: Int): Estimate? {
        val slopes = BgiAdjustedSeries.pairSlopes(points, nowTs) ?: return null
        val mean = BgiAdjustedSeries.median(slopes)
        return Estimate(mean, quantile(slopes, mean, quantilePct), slopes.size)
    }

    /**
     * Rangregel auf der SORTIERten Liste: `idx = floor(p * (N-1))`, keine
     * Interpolation.
     *
     * "Das 25-%-Quantil" hat je nach Bibliothek neun Definitionen. Die
     * Floor-Rangregel ist in jeder Sprache reproduzierbar und rundet immer zur
     * pessimistischen Seite.
     *
     * `quantilePct == 50` liefert den MEDIAN SELBST, nicht die Rangregel:
     * bei gerader Paaranzahl laege `floor(0.5*(N-1))` um einen halben
     * Rangschritt daneben. So ist 50 bitgleich zum Verhalten ohne Band und
     * damit ein echter Ein/Aus-Schalter auf EINER Spur.
     *
     * Fuer p <= 0.5 gilt strukturell `idx <= Medianindex`, also immer
     * `lower <= mean`.
     */
    internal fun quantile(sorted: List<Double>, mean: Double, quantilePct: Int): Double {
        if (quantilePct >= MAX_PCT) return mean
        val p = quantilePct.coerceAtLeast(0) / 100.0
        val idx = floor(p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
