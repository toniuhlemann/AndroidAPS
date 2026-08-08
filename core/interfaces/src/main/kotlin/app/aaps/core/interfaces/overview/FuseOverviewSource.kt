package app.aaps.core.interfaces.overview

/**
 * Liefert die FUSE-Zeitreihen fuer die Overview-Untergraphen.
 *
 * Bewusst ein Interface in core:interfaces statt einer DB-Tabelle: FUSE darf
 * seine Ergebnisse nicht in autoISF-Tabellen persistieren, und eine eigene
 * Tabelle nur fuer Graphen waere Vorbau. Quelle ist der Prozess-Ring des
 * FusePlugin - nach einem App-Neustart beginnt der Graph leer und fuellt sich
 * im 1-min-Takt. Der Trail (fuse_state_history.jsonl) bleibt die vollstaendige
 * Historie.
 */
interface FuseOverviewSource {

    data class Point(
        val timestamp: Long,
        /** Theil-Sen-Antrieb r [mg/dl/min]. */
        val driveMgdlPerMin: Double?,
        /** BGI-bereinigter schneller Antrieb (ukfRate + activity*isf). */
        val fastDriveMgdlPerMin: Double?,
        /** minLower - guardFloor [mg/dl], an der Quelle auf -50..150 geklippt:
         *  interessant ist der Nulldurchgang, nicht die Tiefe einer
         *  unphysiologischen Bahn. */
        val guardMarginMgdl: Double?,
    )

    fun fuseGraphPoints(fromTime: Long, endTime: Long): List<Point>

    /** Aktuelle Rampenkanten (unten, oben) [mg/dl/min] fuer die Referenz-
     *  linien im Antriebs-Untergraphen - dynamisch aus den Preferences. */
    fun fuseRampLevels(): Pair<Double, Double>

    /** Marker-Druck-Zeitpunkte im Fenster - je einer wird als senkrechte
     *  Linie in die FUSE-Untergraphen gezeichnet (Essensbeginn sichtbar). */
    fun fuseMealMarkerTimes(fromTime: Long, endTime: Long): List<Long>
}
