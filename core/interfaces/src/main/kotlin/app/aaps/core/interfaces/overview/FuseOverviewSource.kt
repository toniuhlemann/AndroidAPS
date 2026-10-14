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
        /** Ob JETZT ein gemessenes Tief vorliegt. */
        val measuredLow: Boolean,
        /**
         * MANUELLES Insulin der letzten Stunde [U], `null` = nicht ermittelbar.
         * Nichts wird still verrechnet (Tonis Entscheidung) - der Dialog beziffert es, damit die Wahl beim Menschen
         * liegt.
         */
        val foreignBolusU: Double? = null,
        /** Rest der laufenden Evidenz-Episode [min]; `null` = keine, unbekannt
         *  oder noch reichlich Zeit. */
        val episodeRestMin: Int? = null,
        /**
         * Die Wahl "Nur Erwartung" (Toni): das Erwartungskonto traegt die Mahlzeit - bis zu dieser Menge [U] nach
         * Kurve. `null` = nicht angeboten (keine gueltige Kurve, kein Profil oder Insulinmodell); dann bleibt nur
         * "Nur Marker - 0 U".
         */
        val expectationOnlyMaxU: Double? = null,
    )

    fun fuseMarkerArmed(now: Long): Boolean

    /**
     * Die Mahlzeit ist angekuendigt, aber es gilt kein Erwartungskonto - FUSE dosiert im Rueckfall wie ohne Marker
     * (Toni: den Ausfall deutlich melden). Am Knopf sichtbar, solange der letzte Zyklus ihn ausweist.
     */
    fun fuseMarkerOhneKonto(now: Long): Boolean = false

    /** `null` = ohne Rueckfrage ausfuehren (Ruecknahme). */
    fun fuseMarkerPrompt(now: Long): MarkerPromptFacts?

    /** Schaltet um. @return ob der Marker DANACH laeuft. */
    /**
     * @param ohneVorschuss true = "Nur Marker - 0 U": die Mahlzeit ist angekuendigt (Fensterregeln, Rampen und
     * Totband-Oeffnung wie immer), aber ohne Erwartungsinsulin - das Konto laeuft ohne Budget, nur der gemessene
     * Anteil. Die Wahl faellt im Dialog des Knopfdrucks, nicht in den Einstellungen.
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
        /** Wahl "Nur Erwartung" (Toni): das Erwartungskonto traegt die Mahlzeit. */
        nurErwartung: Boolean = false,
    ): Boolean

    /**
     * Eine Ereignis-Kennung ausgeben - beim ANZEIGEN der Rueckfrage, nicht
     * beim Bestaetigen. Wird sie erst beim Bestaetigen vergeben, traegt ein
     * spaet bestaetigter alter Dialog eine zu neue Ordnung und wirkt doch.
     */
    fun fuseMarkerEreignis(): String

    /**
     * RUECKNAHME MIT RUECKGAENGIG-FRIST (Toni 04.10.): statt den laufenden
     * Marker sofort zurueckzunehmen, wird die Ruecknahme vorgemerkt und erst
     * nach der Frist ausgefuehrt; bis dahin bleibt der Marker wirksam.
     *
     * @return Faelligkeit [ms] oder `null` - dann gibt es keinen laufenden
     *         Marker (oder die Quelle kennt keine Frist) und der Aufrufer
     *         schaltet wie bisher direkt um.
     */
    fun fuseMarkerRuecknahmeVormerken(now: Long, ereignisId: String?): Long? = null

    /** Die vorgemerkte Ruecknahme aufheben. @return ob eine aufgehoben wurde. */
    fun fuseMarkerRuecknahmeRueckgaengig(now: Long): Boolean = false
}
