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
}
