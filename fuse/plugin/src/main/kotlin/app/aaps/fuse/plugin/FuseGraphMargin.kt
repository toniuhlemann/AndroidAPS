package app.aaps.fuse.plugin

import app.aaps.core.interfaces.overview.FuseOverviewSource

/**
 * DIE SCHWANZ-KANTE FUER DEN UNTERGRAPHEN, in mg/dl.
 *
 * Eigene Datei, weil beide Ring-Pfade sie brauchen - der LIVE-Pfad aus
 * [app.aaps.fuse.core.controller.TailLiability.Report] und der WARMSTART aus
 * dem Trail-JSON. Zwei Umrechnungen nebeneinander waeren zwei Wahrheiten, und
 * die Graph-Linie muss in beiden Faellen dasselbe bedeuten.
 *
 * DER FALL, DER DIESE FUNKTION NOETIG MACHT (Trail-Messung): von den
 * `tail`-Bloecken der ersten Produktivstunden trug mehr als die Haelfte einen GUELTIGEN
 * `headroomU` (negativ, also gesperrt), aber KEINEN ISF-Nenner - der
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
     *  Aussage, nicht die Tiefe einer unphysiologischen Bahn. Toni: unten weiter
     *  als -50 - sonst klebt eine tief gesperrte Kante am Rand und man sieht
     *  nicht, wie weit sie zu ist. */
    const val LOWER_MGDL = -150.0
    const val UPPER_MGDL = 150.0

    /** Der Guard-Abstand einer Untergrenze [mg/dl]: `lower - floor`, geklippt; nicht auswertbar = Luecke. */
    fun guardMarginMgdl(lowerMgdl: Double?, floorMgdl: Double?): Double? {
        val l = lowerMgdl ?: return null
        val f = floorMgdl ?: return null
        val v = l - f
        return if (v.isFinite()) v.coerceIn(LOWER_MGDL, UPPER_MGDL) else null
    }

    /**
     * Der Kontozustand fuer den Statusstreifen - aus Geltung, Rueckfall und Kontostatus, im Live- und im
     * Warmstartpfad dieselbe Regel. Ein Rueckfall gewinnt (Toni: den Ausfall deutlich zeigen); ohne Konto `null`.
     */
    fun accountStatus(applies: Boolean, fallback: String?, status: String?): FuseOverviewSource.AccountStatus? {
        val rueckfall = fallback?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        if (rueckfall != null) return FuseOverviewSource.AccountStatus.OHNE_KONTO
        if (!applies) return null
        return when (status?.trim()) {
            "INITIAL", "ACTIVE" -> FuseOverviewSource.AccountStatus.AKTIV
            "WAITING_FOR_STABILITY" -> FuseOverviewSource.AccountStatus.WARTET
            "PAUSED", "NO_MODEL" -> FuseOverviewSource.AccountStatus.PAUSIERT
            "FROZEN" -> FuseOverviewSource.AccountStatus.EINGEFROREN
            "LOCKED_BY_LOW" -> FuseOverviewSource.AccountStatus.GESPERRT
            "EXPECTATION_OFF" -> FuseOverviewSource.AccountStatus.OHNE_KONTO
            "EXPIRED", "BUDGET_USED", "WITHDRAWN" -> FuseOverviewSource.AccountStatus.BEENDET
            else -> null
        }
    }

    /**
     * ZWEITER RIEGEL gegen die Android-optString-Falle (Geraetebefund):
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
