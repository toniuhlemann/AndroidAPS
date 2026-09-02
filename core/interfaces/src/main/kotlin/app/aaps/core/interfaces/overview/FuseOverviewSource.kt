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
        /**
         * DIE MENGENZEILEN, fertig ausgewaehlt - Nullteile sind bereits
         * entfallen. Die Auswahl faellt in FUSE (`MarkerPrompt.lines`),
         * damit sie geprueft ist; die Bedienoberflaeche uebersetzt nur
         * noch in Text. ALLE Mengen sind ANFORDERUNGEN - Riegel,
         * IOB-Spielraum, Aufschub und Pumpen-Gates koennen sie kuerzen.
         */
        val lines: List<Line>,
        /** Sofort angeforderter Phase-A-Anteil [U] (0 = kein Sofortanteil). */
        val upfrontPlannedU: Double,
        /** Ueber das Prime-Fenster verteilter Rest von Phase A [U]. */
        val phaseARemainderU: Double,
        /** Fundament-Budget [U] bis [foundationEndMin]. */
        val phaseBBudgetU: Double,
        /** Ende des Fundament-Fensters [min ab Druck], null = kein Fundament. */
        val foundationEndMin: Int?,
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
    ) {

        /** Eine Mengenzeile der Rueckfrage. */
        sealed interface Line {
            data class Upfront(val amountU: Double) : Line
            data class Spread(val amountU: Double, val windowMin: Int?) : Line
            data class Foundation(val amountU: Double, val untilMin: Int) : Line
            data class Total(val amountU: Double) : Line
            /** Aktueller Zustand: der Sofortanteil ist aufgeschoben. */
            data class Deferred(val reason: String) : Line
        }
    }

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
    fun fuseMarkerToggle(
        now: Long,
        ohneVorschuss: Boolean = false,
        /**
         * Kennung des BEDIENEREIGNISSES, ausgegeben von
         * [fuseMarkerEreignis] beim ANZEIGEN der Rueckfrage. Ein
         * wiederholter oder verspaeteter Rueckruf traegt eine aeltere
         * Ordnung und wirkt dann nicht mehr - ohne sie bliebe dieser Pfad
         * ungeschuetzt.
         */
        ereignisId: String? = null,
    ): Boolean

    /**
     * Eine Ereignis-Kennung ausgeben - beim ANZEIGEN der Rueckfrage, nicht
     * beim Bestaetigen. Wird sie erst beim Bestaetigen vergeben, traegt ein
     * spaet bestaetigter alter Dialog eine zu neue Ordnung und wirkt doch.
     */
    fun fuseMarkerEreignis(): String
}
