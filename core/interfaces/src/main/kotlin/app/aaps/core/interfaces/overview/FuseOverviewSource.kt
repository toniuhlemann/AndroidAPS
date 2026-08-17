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
        /**
         * Schwanz-Spielraum in mg/dl (headroomU x isfTail), gleiche Klippung
         * und gleiche Nulldurchgangs-Semantik wie der Guard-Abstand.
         *
         * NACHGEZOGEN 15.08.: die Messung des Tages hat gezeigt, dass der
         * Schwanz OEFTER bindet als der Guard (88x gegen 70x im 4-Tage-Trail;
         * im ersten Produktiv-Fall war tailHeadroom die tatsaechliche Kante).
         * Ein Guard-Abstand allein zeigte "offen", waehrend FUSE vom Schwanz
         * gesperrt war - genau irrefuehrend fuer die Nachvollziehung.
         */
        val tailMarginMgdl: Double? = null,
    )

    fun fuseGraphPoints(fromTime: Long, endTime: Long): List<Point>

    /** Aktuelle Rampenkanten (unten, oben) [mg/dl/min] fuer die Referenz-
     *  linien im Antriebs-Untergraphen - dynamisch aus den Preferences. */
    fun fuseRampLevels(): Pair<Double, Double>

    /** Marker-Druck-Zeitpunkte im Fenster - je einer wird als senkrechte
     *  Linie in die FUSE-Untergraphen gezeichnet (Essensbeginn sichtbar). */
    fun fuseMealMarkerTimes(fromTime: Long, endTime: Long): List<Long>

    // ---- Der Mahlzeiten-Knopf auf dem Uebersichtsschirm -------------------
    //
    // Der Knopf gehoert dorthin, weil man ihn beim ESSEN drueckt und nicht in
    // einem Unter-Tab. Durchgereicht wird genau das, was das
    // Overview-Fragment braucht - keine FUSE-Interna. Insbesondere faellt die
    // Entscheidung, OB gefragt wird, in FUSE (MarkerPrompt) und nicht im
    // Fragment: sonst haetten Uebersichtsknopf und FUSE-Tab zwei
    // Sicherheitsniveaus fuer denselben Knopf.

    /** Die Zahlen, die in der Rueckfrage stehen muessen. */
    data class MarkerPromptFacts(
        /** Was der erste Zyklus hoechstens freigeben kann [U]. */
        val firstStepU: Double,
        /** Die ganze Huelle ueber das Fenster [U]. */
        val envelopeU: Double,
        /** Was diese Episode schon geliefert hat [U]. */
        val alreadyDeliveredU: Double,
        /** Ob der Druck Modell-Einwaende ueberstimmt (Einstellung an). */
        val authorizesAgainstModel: Boolean,
        /** Ob JETZT ein gemessenes Tief vorliegt. */
        val measuredLow: Boolean,
        /**
         * MANUELLES Insulin der letzten Stunde [U], `null` = nicht ermittelbar.
         * Die Huelle wird davon NICHT gekuerzt (Tonis Entscheidung 16.08.) -
         * der Dialog beziffert es, damit die Wahl beim Menschen liegt.
         */
        val foreignBolusU: Double? = null,
        /** Rest der laufenden Evidenz-Episode [min]; `null` = keine, unbekannt
         *  oder noch reichlich Zeit. */
        val episodeRestMin: Int? = null,
        /** Laenge des Freigabe-Fensters [min] aus der EINSTELLUNG; `null` =
         *  unbekannt, dann nennt der Dialog keine Dauer statt einer falschen. */
        val windowMin: Int? = null,
    )

    fun fuseMarkerArmed(now: Long): Boolean

    /** `null` = ohne Rueckfrage ausfuehren (Ruecknahme). */
    fun fuseMarkerPrompt(now: Long): MarkerPromptFacts?

    /** Schaltet um. @return ob der Marker DANACH laeuft. */
    /**
     * @param ohneVorschuss true = Mahlzeit nur ERKLAEREN: Fensterregeln,
     * Rampen und Totband-Oeffnung wie immer, aber die Freigabe-Huelle dieser
     * Episode ist 0 - kein markerfinanziertes Insulin. Die Wahl faellt im
     * Dialog des Knopfdrucks (Toni 15.08.: "es gibt Situationen, wo 3
     * Einheiten jetzt zuviel waeren" - z.B. reichlich aktiver Bolus vor der
     * Mahlzeit), nicht in den Einstellungen.
     */
    fun fuseMarkerToggle(now: Long, ohneVorschuss: Boolean = false): Boolean
}
