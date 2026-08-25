package app.aaps.fuse.core.signal

/**
 * WIEVIEL REIHE BRAUCHT EIN r? - unveraenderlich und je Runner injiziert
 * (Bauauftrag Toni 25.08. abends, "dosierneutraler Reife-Replay").
 *
 * DIE FRAGE, DIE DIESE KLASSE MESSBAR MACHT: nach einer echten CGM-Luecke
 * wartet FUSE heute ~5-6 Minuten, bis wieder ein r existiert. Gemessen
 * wurde am 25.08., dass diese Wartezeit NICHT von der Lueckenlaenge
 * abhaengt - sie ist die Theil-Sen-Reifebedingung selbst. Eine
 * verschobene Segmentgrenze (s. [GapPolicy]) beseitigt nur die kurzen
 * Grenzfaelle; alles darueber wartet weiter.
 *
 * Die Schranken bei 1-min-Kadenz - n Punkte ergeben (n-1)(n-2)/2 Paare
 * mit dt >= 2 min:
 *
 *   3 Punkte ->  1 Paar     4 Punkte ->  3 Paare
 *   5 Punkte ->  6 Paare    6 Punkte -> 10 Paare
 *
 * Deshalb bindet in der Produktion (5 Punkte / 8 Paare) die PAARSCHRANKE
 * und nicht die Punktschranke: es braucht 6 Punkte, also 5 Minuten.
 *
 * WARUM UNVERAENDERLICH UND INJIZIERT: dieselbe Lehre wie bei
 * [GapPolicy]. Ein prozessweiter Schalter liesse alle Runner und Tests
 * im selben Prozess EINEN Wert teilen; zwei Matrixlaeufe koennten sich
 * vermischen, und die Reihenfolge der Laeufe wuerde bedeutungstragend.
 * Die Produktionskonstanten in [BgiAdjustedSeries] bleiben unangetastet -
 * sie sind der Vorgabewert, nicht der Hebel.
 *
 * KEIN `data class`: deren `copy()` waere ein zweiter Bauweg am privaten
 * Konstruktor vorbei - dieselbe Begruendung wie bei
 * [BgiAdjustedSeries.AdjustedInterval].
 */
class MaturityPolicy private constructor(
    /** Mindestzahl Punkte im Fenster. */
    val minPoints: Int,
    /** Mindestzahl Paarsteigungen mit dt >= [BgiAdjustedSeries.PAIR_DT_MIN_MS]. */
    val minSlopes: Int,
    /**
     * VOR diesem Zeitpunkt gilt die Produktion; 0 = immer diese Politik.
     *
     * DER ANLASS ist ein gemessenes Replay-Artefakt (25.08. abends): ein
     * Replay startet KALT. In den ersten Minuten seines Fensters ist die
     * Referenz blind, weil noch keine Reihe da ist - nicht, weil das
     * Geraet blind war. Am Geraet lief die Reihe durch.
     *
     * Ohne diese Sperre dosierte 3x1 im Kaltstart des Falls 25.08. 11:42
     * dreimal 0,550 U = 1,65 U, und das war die Haelfte der gemessenen
     * "Mehrmenge". Schlimmer noch: diese Dosen wandern ueber Ledger,
     * Evidenz und Deckel in SPAETERE Zyklen, in denen alle Varianten
     * dasselbe r haben - der Kaltstart verseucht den ganzen Lauf.
     *
     * Mit der Sperre sind alle Varianten im Vorlauf BITGLEICH; das ist
     * ein pruefbares Tor und keine Annahme.
     *
     * Am Geraet ist der Wert konstruktionsbedingt 0 und die Politik
     * ohnehin die Produktion - dieser Pfad existiert nur offline.
     */
    val activeFromTs: Long,
) {

    /** Die WIRKSAME Punktschranke zum Zeitpunkt [ts]. */
    fun minPointsAt(ts: Long): Int =
        if (activeFromTs == 0L || ts >= activeFromTs) minPoints else PRODUCTION.minPoints

    /** Die WIRKSAME Paarschranke zum Zeitpunkt [ts]. */
    fun minSlopesAt(ts: Long): Int =
        if (activeFromTs == 0L || ts >= activeFromTs) minSlopes else PRODUCTION.minSlopes

    /**
     * Wieviele Punkte es bei 1-min-Kadenz TATSAECHLICH braucht - beide
     * Schranken zusammen. Genau diese Zahl minus eins sind die Minuten,
     * die nach einem Segmentbruch vergehen.
     *
     * Nur fuer Anzeige und Auswertung: der Schaetzer selbst prueft immer
     * beide Schranken einzeln, weil die Kadenz nicht garantiert 1 min ist.
     */
    fun effectivePointsAt1Min(): Int {
        var n = maxOf(2, minPoints)
        while ((n - 1) * (n - 2) / 2 < minSlopes) n++
        return n
    }

    /** Kurzkennung fuer Trail und Dateinamen, z.B. "5x8". */
    fun tag(): String = "${minPoints}x$minSlopes"

    override fun equals(other: Any?): Boolean =
        other is MaturityPolicy && other.minPoints == minPoints &&
            other.minSlopes == minSlopes && other.activeFromTs == activeFromTs

    override fun hashCode(): Int = (minPoints * 31 + minSlopes) * 31 + activeFromTs.hashCode()

    override fun toString(): String =
        "MaturityPolicy(${tag()}${if (activeFromTs == 0L) "" else "@$activeFromTs"})"

    companion object {

        /** Obergrenzen fuer einen Replay-Wert - jenseits davon ist die
         *  Frage keine Reifefrage mehr, sondern ein anderer Kandidat. */
        const val MAX_POINTS = 40
        const val MAX_SLOPES = 400

        /**
         * DIE UNTERGRENZE, die nicht verhandelbar ist: unter zwei Punkten
         * gibt es keine Steigung, und unter EINER Paarsteigung waere der
         * "Median" eine Behauptung ohne Beleg. `theilSen` gaebe dann auf
         * einer leeren Liste keinen Wert, sondern eine Ausnahme.
         */
        const val MIN_POINTS_FLOOR = 2
        const val MIN_SLOPES_FLOOR = 1

        /**
         * DAS GERAET. Identisch zu den gelockten Konstanten in
         * [BgiAdjustedSeries] - der Kandidat aendert sich nicht dadurch,
         * dass er jetzt durch einen Parameter reist.
         */
        val PRODUCTION = MaturityPolicy(
            BgiAdjustedSeries.MIN_POINTS,
            BgiAdjustedSeries.MIN_SLOPES,
            0L,
        )

        /**
         * Eine Politik fuer den OFFLINE-Replay. Unbrauchbare Werte ergeben
         * die Produktion, statt still eine Reihe ohne Beleg zuzulassen.
         */
        fun of(points: Int, slopes: Int, activeFromTs: Long = 0L): MaturityPolicy =
            if (points in MIN_POINTS_FLOOR..MAX_POINTS && slopes in MIN_SLOPES_FLOOR..MAX_SLOPES &&
                activeFromTs >= 0L)
                MaturityPolicy(points, slopes, activeFromTs)
            else PRODUCTION

        /** "5x8" -> [of]. Unlesbares ergibt die Produktion. */
        fun parse(text: String?, activeFromTs: Long = 0L): MaturityPolicy {
            val teile = text?.trim()?.split("x", "X") ?: return PRODUCTION
            if (teile.size != 2) return PRODUCTION
            val p = teile[0].trim().toIntOrNull() ?: return PRODUCTION
            val s = teile[1].trim().toIntOrNull() ?: return PRODUCTION
            return of(p, s, activeFromTs)
        }
    }
}
