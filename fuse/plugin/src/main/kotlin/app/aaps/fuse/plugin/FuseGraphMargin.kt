package app.aaps.fuse.plugin

/**
 * DIE SCHWANZ-KANTE FUER DEN UNTERGRAPHEN, in mg/dl.
 *
 * Eigene Datei, weil beide Ring-Pfade sie brauchen - der LIVE-Pfad aus
 * [app.aaps.fuse.core.controller.TailLiability.Report] und der WARMSTART aus
 * dem Trail-JSON. Zwei Umrechnungen nebeneinander waeren zwei Wahrheiten, und
 * die Graph-Linie muss in beiden Faellen dasselbe bedeuten.
 *
 * DER FALL, DER DIESE FUNKTION NOETIG MACHT (Trail-Messung 15.08.): von 131
 * `tail`-Bloecken der ersten Produktivstunden trugen 73 einen GUELTIGEN
 * `headroomU` (-0,498 U, also gesperrt), aber KEINEN ISF-Nenner - der
 * unphysiologische Ausgang des Reports setzt `budgetU = 0` und
 * `headroomU = -existing`, laesst `isfTailMgdlPerU` aber auf NaN. Eine naive
 * Multiplikation ergibt NaN und damit eine LUECKE in der Linie: der Graph
 * haette "keine Schwanz-Kante" gezeigt, waehrend der Schwanz am haertesten
 * sperrte. Das waere die gefaehrliche Richtung des Irrtums.
 *
 * Die Regel deshalb, in dieser Reihenfolge:
 *  - `invalidReason != null` -> LUECKE. Der Schwanz-Guard greift dann
 *    ausdruecklich nicht (s. Report.invalidReason); eine Linie waere eine
 *    Sperre, die es nicht gibt.
 *  - beide Zahlen auswertbar -> `headroomU * isfTail`, geklippt.
 *  - Nenner fehlt, aber `headroomU < 0` -> UNTERER ANSCHLAG. Das Vorzeichen
 *    ist sicher (es sperrt), die Tiefe ist es nicht - also die Aussage
 *    zeichnen, die belegt ist, und nicht die Zahl, die es nicht gibt.
 *  - Nenner fehlt und `headroomU >= 0` -> LUECKE. "Offen" ohne Nenner zu
 *    behaupten waere wieder die gefaehrliche Richtung.
 */
internal object FuseGraphMargin {

    /** Dieselbe Klippung wie beim Guard-Abstand: der Nulldurchgang ist die
     *  Aussage, nicht die Tiefe einer unphysiologischen Bahn. */
    const val LOWER_MGDL = -50.0
    const val UPPER_MGDL = 150.0

    /**
     * ZWEITER RIEGEL gegen die Android-optString-Falle (Geraetebefund 15.08.):
     * `optString` liefert dort fuer ein JSON-null den String "null", waehrend
     * dieselbe API auf der JVM den Default liefert. Ein Aufrufer, der das
     * uebersieht, wuerde jede Zeile fuer ungueltig halten - deshalb gelten
     * hier auch "null" und Leerstring als KEIN Grund.
     */
    fun tailMarginMgdl(headroomU: Double?, isfTailMgdlPerU: Double?, invalidReason: String?): Double? {
        val grund = invalidReason?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        if (grund != null) return null
        val h = headroomU ?: return null
        if (!h.isFinite()) return null
        val isf = isfTailMgdlPerU
        if (isf != null && isf.isFinite()) {
            val v = h * isf
            if (v.isFinite()) return v.coerceIn(LOWER_MGDL, UPPER_MGDL)
        }
        return if (h < 0.0) LOWER_MGDL else null
    }
}
