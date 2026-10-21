package app.aaps.core.interfaces.overview

/**
 * Fork (FUSE): liefert die FUSE-Zeitreihen fuer die Overview-Untergraphen.
 *
 * v4: die Bedienfunktionen sind suspend - sie lesen die Datenbank oder warten auf den laufenden Zyklus,
 * und die Compose-Oberflaeche ruft sie aus einer Coroutine.
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
         * NACHGEZOGEN: eine Messung hat gezeigt, dass der Schwanz OEFTER
         * bindet als der Guard (im ersten Produktiv-Fall war tailHeadroom die
         * tatsaechliche Kante).
         * Ein Guard-Abstand allein zeigte "offen", waehrend FUSE vom Schwanz
         * gesperrt war - genau irrefuehrend fuer die Nachvollziehung.
         */
        val tailMarginMgdl: Double? = null,
        /** Im Fenster der Mahlzeit-Vollmacht (Konto oder Rueckfall): positives r wird dort gelb gezeichnet. */
        val mealWindow: Boolean = false,
        /** Die vom Konto jetzt erwartete Mahlzeitwirkung [mg/dl/min] (Kurve mal angepasster Skalierer); `null` = keine. */
        val expectedMealDriveMgdlPerMin: Double? = null,
        /**
         * Die Untergrenze MIT der angekuendigten Mahlzeit minus guardFloor [mg/dl] - die Pruefung der weiteren
         * Portionen (G3) -, wie der Guard-Abstand geklippt; `null` = in diesem Zyklus keine.
         */
        val guardWithMealMarginMgdl: Double? = null,
        /**
         * Der Schwanz-Spielraum MIT der angekuendigten Mahlzeit [mg/dl] (G4, samt beobachteter Fortsetzung), wie
         * [tailMarginMgdl] umgerechnet und geklippt; `null` = in diesem Zyklus keiner. Oft die Bremse einer laufenden
         * Mahlzeit - ohne die Linie saehe man nur den Guard mit Mahlzeit offen.
         */
        val tailWithMealMarginMgdl: Double? = null,
        /** Zustand des Erwartungskontos fuer den Statusstreifen; `null` = kein Konto in diesem Zyklus. */
        val accountStatus: AccountStatus? = null,
        /** Main graph (Toni 21.10.2026): what FUSE did in this cycle - the colour of the BG point. `null` = no data. */
        val cycleState: CycleState? = null,
        /** The SMB amount handed to AAPS in this cycle [U]; `null` = none. */
        val smbU: Double? = null,
        /** Where that SMB came from (`exposure.source`); `null` = no SMB. */
        val smbSource: SmbSource? = null,
        /** The slow correction channel in this cycle; `null` = channel off. */
        val channel: ChannelState? = null,
        /** The binding limit of the decision as FUSE writes it (`decision.bindingLimit`), for the press-and-hold pill. */
        val bindingLimit: String? = null,
        /** insulinReq of the decision [U], for the press-and-hold pill. */
        val insulinReqU: Double? = null,
    )

    /** Zustand des Erwartungskontos je Zyklus - der Streifen am oberen Rand des Guard-Untergraphen. */
    enum class AccountStatus { AKTIV, WARTET, PAUSIERT, EINGEFROREN, GESPERRT, OHNE_KONTO, BEENDET }

    /**
     * What FUSE did in one cycle, the colour of the BG point in the main graph. Order of precedence: a protective
     * zero basal before the correction channel, before the guard, before another SMB stop, before free.
     */
    enum class CycleState { FREE, GUARD, BLOCKED, ZERO, CHANNEL, UNKNOWN }

    /** Where an SMB came from (`exposure.source`): the expectation account, the normal path, or anything else. */
    enum class SmbSource { EXPECTATION, NORMAL, OTHER }

    /** The slow correction channel in one cycle, as in the trail block `slowChannel`. */
    data class ChannelState(
        /** NONE, PENDING, RUNNING, ENDING, ... */
        val phase: String,
        val reason: String? = null,
        val detail: String? = null,
        val rateUPerH: Double? = null,
        /** The part above the profile basal [U/h]; rate / (rate - extra) is the active factor. */
        val extraUPerH: Double? = null,
        val deliveredU: Double? = null,
    ) {

        /** A channel TBR is wanted, pending or ending. */
        val active: Boolean get() = phase == "PENDING" || phase == "RUNNING" || phase == "ENDING"

        /** The active factor: rate divided by the profile basal; `null` without an extra part. */
        val factor: Double?
            get() {
                val rate = rateUPerH ?: return null
                val extra = extraUPerH ?: return null
                val basal = rate - extra
                return if (extra > 0.0 && basal > 0.0) rate / basal else null
            }
    }

    /** Whether the main graph shows the FUSE layers (a FUSE setting). Other sources show none. */
    fun fuseMainGraphLayers(): Boolean = false

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

    /**
     * v4-Port: das Mahlzeitfenster des laufenden Kontos ist noch offen (FUSE 7656fc1cb9). Laeuft dabei kein Marker
     * mehr - nach den ersten 90 min -, startet ein Druck eine NEUE Mahlzeit; der Knopf sagt das ("Neue Mahlzeit").
     */
    fun fuseMahlzeitFensterLaeuft(now: Long): Boolean = false

    /**
     * v4-Port (Toni): insulinReq des letzten FUSE-Zyklus fuer den Chip auf dem Hauptbildschirm - aus der
     * Entscheidung selbst, nicht aus dem RT: der RT traegt ein nicht gerechnetes insulinReq als 0,0 ein.
     * `null` = noch kein Zyklus in diesem Prozess.
     */
    fun fuseInsulinReq(): InsulinReq? = null

    /** insulinReq eines Zyklus mit den Zeilen "Grenze" und "Block" aus dem Abschnitt "Menge" des FUSE-Reiters. */
    data class InsulinReq(
        /** Zeitpunkt der Entscheidung [ms]. */
        val timestamp: Long,
        /** [U]; `null` = in diesem Zyklus nicht gerechnet (angezeigt als "-", nie als 0). */
        val units: Double?,
        /** Die bindende Grenze, wie im Reiter. */
        val grenze: String,
        /** Der Block, wie im Reiter. */
        val block: String,
    )

    /** `null` = ohne Rueckfrage ausfuehren (Ruecknahme). */
    suspend fun fuseMarkerPrompt(now: Long): MarkerPromptFacts?

    /** Schaltet um. @return ob der Marker DANACH laeuft. */
    /**
     * @param ohneVorschuss true = "Nur Marker - 0 U": die Mahlzeit ist angekuendigt (Fensterregeln, Rampen und
     * Totband-Oeffnung wie immer), aber ohne Erwartungsinsulin - das Konto laeuft ohne Budget, nur der gemessene
     * Anteil. Die Wahl faellt im Dialog des Knopfdrucks, nicht in den Einstellungen.
     */
    suspend fun fuseMarkerToggle(
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
     * RUECKNAHME MIT RUECKGAENGIG-FRIST (Toni): statt den laufenden
     * Marker sofort zurueckzunehmen, wird die Ruecknahme vorgemerkt und erst
     * nach der Frist ausgefuehrt; bis dahin bleibt der Marker wirksam.
     *
     * @return Faelligkeit [ms] oder `null` - dann gibt es keinen laufenden
     *         Marker (oder die Quelle kennt keine Frist) und der Aufrufer
     *         schaltet wie bisher direkt um.
     */
    suspend fun fuseMarkerRuecknahmeVormerken(now: Long, ereignisId: String?): Long? = null

    /** Die vorgemerkte Ruecknahme aufheben. @return ob eine aufgehoben wurde. */
    suspend fun fuseMarkerRuecknahmeRueckgaengig(now: Long): Boolean = false
}
