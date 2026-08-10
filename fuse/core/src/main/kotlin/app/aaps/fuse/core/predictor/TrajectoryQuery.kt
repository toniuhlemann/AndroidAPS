package app.aaps.fuse.core.predictor

/**
 * Reine Abfragen auf einem fertigen [PredictorResult] (S0-Telemetrie).
 *
 * WARUM NICHT IM PREDICTOR: der Boden ist eine Groesse des REGLERS
 * (`FuseController.Limits.guardFloorMgdl`), keine des Modells. Haette der
 * Predictor ihn, traege er eine Regel - und ein Kern, der eine Grenze kennt,
 * faengt irgendwann an, sie anzuwenden. Deshalb nimmt jede Abfrage hier den
 * Boden als ARGUMENT, und der Aufrufer reicht seinen eigenen herein.
 *
 * Beide Groessen beantworten zusammen die Frage aus Invariante I16 - WIE TIEF
 * und WIE BALD. Der Auditbefund dazu (E.3/E.8): der Guard ist heute ein reiner
 * Schwellentest, das Ausmass der Verletzung hat keinen Verbraucher, und eine
 * Bahn bei 69 mg/dl ist von einer bei -382 nicht zu unterscheiden.
 *
 * NICHTS hiervon entscheidet etwas. Die Werte gehen in den Export.
 */
object TrajectoryQuery {

    /**
     * Wie weit die SICHERHEITSBAHN unter den Boden faellt [mg/dl], sonst 0.
     *
     * Bewusst >= 0 und nicht vorzeichenbehaftet: "wie tief darunter" ist die
     * Frage, und ein negatives Defizit waere nur eine zweite Schreibweise fuer
     * "kein Defizit".
     */
    fun floorDeficitMgdl(result: PredictorResult, floorMgdl: Double): Double {
        val min = result.minSafetyLowerBg
        if (!min.isFinite() || !floorMgdl.isFinite()) return 0.0
        return (floorMgdl - min).coerceAtLeast(0.0)
    }

    /**
     * Nach wievielen Minuten die Sicherheitsbahn den Boden ERSTMALS
     * unterschreitet, oder `null`, wenn sie ihn ueber den ganzen Horizont haelt.
     *
     * `null` heisst ausdruecklich "nie im Bewertungsfenster" und nicht "nicht
     * berechnet" - der Unterschied zaehlt, weil ein fehlender Wert sonst als
     * "sofort" gelesen werden koennte.
     *
     * Der Anker selbst wird NICHT geprueft: er ist eine Messung, keine
     * Prognose, und ein Boden-Unterschreiten dort ist ein anderer Befund
     * (aktueller Tief-BG) mit einem anderen Zustaendigen.
     */
    fun timeToFloorMin(result: PredictorResult, floorMgdl: Double): Int? {
        if (!floorMgdl.isFinite()) return null
        for (p in result.points) {
            val v = p.safetyLowerBg
            if (v.isFinite() && v < floorMgdl) return p.offsetMin
        }
        return null
    }

    /**
     * Dasselbe Defizit ueber MEHRERE Bahnen — die Form, die der Regler braucht.
     *
     * Der Guard entscheidet gegen `minSafetyLowerOf(prediction, restraint)`,
     * nicht gegen eine einzelne Bahn. Diese Funktion ruft GENAU DIESE Funktion
     * auf, statt das Minimum ein zweites Mal zu bilden: sonst koennten
     * Telemetrie und Entscheidung auseinanderlaufen, und die Zahl im Export
     * beschriebe eine Bahn, gegen die niemand entschieden hat.
     */
    fun floorDeficitOf(floorMgdl: Double, vararg trajectories: PredictorResult?): Double {
        val min = minSafetyLowerOf(*trajectories)
        if (!min.isFinite() || !floorMgdl.isFinite()) return 0.0
        return (floorMgdl - min).coerceAtLeast(0.0)
    }

    /**
     * Das punktweise Minimum ueber mehrere Bahnen, MINUTE FUER MINUTE.
     *
     * ZUSAMMENGEFUEHRT WIRD UEBER `offsetMin`, NICHT UEBER DEN LISTENINDEX.
     * Die erste Fassung lief ueber `points[i]` der ersten Bahn und griff mit
     * demselben i in die anderen. Das ist nur richtig, solange alle Bahnen
     * exakt dasselbe Minutenraster fuehren - heute tun sie das, weil beide aus
     * einem kopierten Eingang durch dieselbe Schleife entstehen. Aber das ist
     * eine ANNAHME ueber fremden Code, keine Eigenschaft dieser Funktion, und
     * sie stand nirgends geschrieben. Ueber `offsetMin` zusammengefuehrt ist
     * das Ergebnis unabhaengig davon richtig, und bei gleichen Rastern - also
     * heute immer - kommt exakt dieselbe Folge heraus.
     *
     * Minuten, die nur EINE Bahn kennt, gehen mit deren Wert ein: eine
     * fehlende Minute ist keine Entwarnung.
     *
     * Der ANKER ist als Minute 0 enthalten, mit dem kleinsten der Ankerwerte -
     * ohne ihn waere das Ergebnis nicht mit [minSafetyLowerOf] konsistent, das
     * ihn ueber `minSafetyLowerBg` mitfuehrt.
     */
    internal fun pointwiseSafetyLower(trajectories: List<PredictorResult>): List<Pair<Int, Double>> {
        if (trajectories.isEmpty()) return emptyList()
        val byMinute = sortedMapOf<Int, Double>()
        byMinute[0] = trajectories.minOf { it.bgAtAnchor }
        for (t in trajectories) for (p in t.points) {
            val v = p.safetyLowerBg
            if (!v.isFinite()) continue
            val cur = byMinute[p.offsetMin]
            if (cur == null || v < cur) byMinute[p.offsetMin] = v
        }
        return byMinute.entries.map { it.key to it.value }
    }

    /**
     * WANN das Minimum liegt, gegen das der Regler ENTSCHEIDET (S0, I16).
     *
     * [PredictorResult.timeToMinSafetyLowerMin] gehoert einer EINZELNEN Bahn.
     * Der Guard rechnet aber gegen `minSafetyLowerOf(prediction, restraint)` -
     * und wenn das Minimum aus der Bremsbahn stammt, beschreibt der Index der
     * Hauptbahn einen anderen Zeitpunkt. Live gesehen am 10.08.:
     *
     *     minLower = 71,17    Anker ~ 90,61    timeToMin(Hauptbahn) = 0
     *
     * Die Hauptbahn hatte ihr Minimum wirklich am Anker; die 71,17 kamen aus
     * der Bremsbahn. Beide Zahlen stimmten - nebeneinander gelesen ergaben sie
     * eine unmoegliche Bahn.
     *
     * 0 heisst "der Anker ist das Minimum". Bei Gleichstand gewinnt die
     * FRUEHERE Minute (striktes `<`) - dieselbe Regel wie in `TrajectoryCore`.
     */
    fun timeToMinSafetyLowerOf(vararg trajectories: PredictorResult?): Int? {
        val series = pointwiseSafetyLower(trajectories.filterNotNull())
        if (series.isEmpty()) return null
        var best = Double.MAX_VALUE
        var at = 0
        for ((minute, v) in series) if (v < best) { best = v; at = minute }
        return at
    }

    /**
     * WIE BALD ueber mehrere Bahnen: die erste Minute, in der das punktweise
     * Minimum den Boden unterschreitet.
     *
     * Der ANKER zaehlt hier ABSICHTLICH NICHT mit (`minute > 0`): er ist eine
     * Messung, keine Prognose, und ein Boden-Unterschreiten dort ist ein
     * anderer Befund mit einem anderen Zustaendigen - dieselbe Grenze wie in
     * [timeToFloorMin].
     *
     * ACHTUNG, ASYMMETRIE: [floorDeficitOf] laeuft ueber [minSafetyLowerOf] und
     * SCHLIESST den Anker ein. Ein aktuell tiefer BG erzeugt dort also ein
     * Defizit, waehrend hier `null` steht. Das ist gewollt - "wie tief" fragt
     * nach dem schlimmsten Punkt, "wie bald" nach der Prognose -, aber es muss
     * dastehen, sonst liest jemand das Paar als Widerspruch.
     */
    fun timeToFloorOf(floorMgdl: Double, vararg trajectories: PredictorResult?): Int? {
        if (!floorMgdl.isFinite()) return null
        for ((minute, v) in pointwiseSafetyLower(trajectories.filterNotNull()))
            if (minute > 0 && v < floorMgdl) return minute
        return null
    }
}
