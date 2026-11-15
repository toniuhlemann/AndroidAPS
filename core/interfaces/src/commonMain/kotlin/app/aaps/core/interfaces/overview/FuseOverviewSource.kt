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
        /** minLower - guardFloor [mg/dl], an der Quelle auf [MARGIN_LOWER_MGDL]..[MARGIN_UPPER_MGDL] geklippt:
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

    companion object {

        /**
         * The two stops of the guard and the tail margin [mg/dl]. The source holds both margins inside them: the message
         * is the crossing of zero, not the depth of a path that cannot happen. A value at a stop means "at least this far".
         */
        const val MARGIN_LOWER_MGDL = -150.0
        const val MARGIN_UPPER_MGDL = 150.0
    }

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
         * Die Wahl "Mahlzeit" (bis 24.10.2026 "Nur Erwartung"): das Erwartungskonto traegt die Mahlzeit - bis zu
         * dieser Menge [U] nach Kurve. `null` = nicht angeboten (keine gueltige Kurve, kein Profil oder Insulinmodell);
         * dann bleibt nur "Nur Marker - 0 U".
         */
        val expectationOnlyMaxU: Double? = null,
        /** Davon hoechstens sofort am Druck (die Anfangsportion bei stabilem Start) [U] - steht am Knopf (Toni). */
        val expectationOnlyInitialU: Double? = null,
        /**
         * Die Wahl "Snack" (Toni 24.10.2026): dasselbe Konto mit dem kleineren Snack-Skalierer - bis zu dieser Menge
         * [U]. `null` = nicht angeboten (wie bei "Mahlzeit").
         */
        val snackMaxU: Double? = null,
        /** Davon hoechstens sofort am Druck [U] - steht am Knopf. */
        val snackInitialU: Double? = null,
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

    // ---- The FUSE chip on the main screen (Toni 15.11.2026) ---------------
    //
    // One chip under the sensitivity chip: closed it shows the state of the last cycle and the pinned values, open it
    // shows nine tiles in the order of the dosing chain. It took the place of the insulinReq chip beside IOB and COB.
    // Display only: no cycle reads any of this, and nothing here is calculated a second time - every value is read
    // from the cycle that produced it.

    /** The tiles of the FUSE chip in the order they are shown: the dosing chain first, then protection and meal. */
    enum class ChipTile { DRIVE, DEMAND, RATIO, SMB, LIMIT, CHANNEL, GUARD, TAIL, MEAL }

    /** Which rule gave the SMB share of a cycle: the rise ramp, the correction value, or the cap after a low. */
    enum class RatioMode { RAMP, CORRECTION, REBOUND }

    /** An SMB that a cycle handed to AAPS. */
    data class ChipSmb(
        val timestamp: Long,
        val units: Double,
        val source: SmbSource,
    )

    /** The meal of a cycle for the tile "Mahlzeit": the expectation account, or the balance of a bolus calculator meal. */
    data class ChipMeal(
        /** The account state, as in the strip of F.GRD; `null` = no account in this cycle. */
        val status: AccountStatus?,
        /**
         * What the account may still give [U], after the booking of this cycle: budget plus top-up minus what is booked.
         * `null` = no account.
         */
        val restU: Double?,
        /** The account runs without a budget (the choice "Nur Marker"). */
        val withoutBudget: Boolean,
        /** End of the meal window [ms]; 0 = none. */
        val windowEndTs: Long,
        /** The scaler booked at the marker; `null` = no account, no budget, or a bolus calculator meal. */
        val scale: Double?,
        /** The meal comes from the bolus calculator. */
        val calculator: Boolean,
        /** The balance G of a bolus calculator meal [U]; `null` = no such meal, or not calculable in this cycle. */
        val balanceU: Double?,
    )

    /** The values of one FUSE cycle for the chip on the main screen. */
    data class Chip(
        /** When FUSE calculated this cycle [ms]. The chip is greyed out when this is old. */
        val timestamp: Long,
        /** What FUSE did in this cycle: the colour and the word of the closed chip. */
        val cycleState: CycleState,
        /** The insulin adjusted disturbance r [mg/dl/min]; `null` = not estimable. */
        val driveMgdlPerMin: Double?,
        /** The meal effect the account expects now [mg/dl/min]; `null` = none. */
        val expectedMealDriveMgdlPerMin: Double?,
        /** insulinReq of the decision [U]; `null` = not calculated in this cycle (shown as a dash, never as 0). */
        val insulinReqU: Double?,
        /** The share of the demand that may go out as SMB in this cycle (0..1); `null` = the cycle did not get that far. */
        val smbRatio: Double?,
        /** Which rule gave [smbRatio]; `null` together with it. */
        val ratioMode: RatioMode?,
        /** Where r stands on the rise ramp, 0..100 percent; `null` = r is unknown or there is no ramp. */
        val rampPercent: Int?,
        /** The SMB handed to AAPS in this cycle; `null` = none. */
        val smb: ChipSmb?,
        /**
         * What FUSE wanted before the last checks [U], when they cut the SMB of this cycle or let nothing out;
         * `null` = nothing was cut.
         */
        val smbWantedU: Double?,
        /** The newest SMB up to and including this cycle; `null` = none in the data of this process. */
        val lastSmb: ChipSmb?,
        /** The limit or lock that holds the SMB back, as FUSE names it; `null` = nothing holds it back ("frei"). */
        val limit: String?,
        /** Room up to the tightest amount limit (maxIOB, iobTH, exposure limit) [U]; `null` = not known. */
        val roomU: Double?,
        /** The slow correction channel; `null` = off. */
        val channel: ChannelState?,
        /** Guard and tail margin [mg/dl], without and with the announced meal, as in F.GRD (held at the two stops). */
        val guardMarginMgdl: Double?,
        val guardWithMealMarginMgdl: Double?,
        val tailMarginMgdl: Double?,
        val tailWithMealMarginMgdl: Double?,
        /** The meal of this cycle; `null` = none. */
        val meal: ChipMeal?,
    )

    /** How the user set the chip up: the tiles pinned to the closed chip, in the order they were pinned, and open or closed. */
    data class ChipLayout(
        val pinned: List<ChipTile> = DEFAULT_PINS,
        val expanded: Boolean = false,
    ) {

        companion object {

            /** At most this many tiles show on the closed chip. */
            const val MAX_PINS = 4

            /** Pinned from the start (Toni): demand, guard margin and the meal. */
            val DEFAULT_PINS: List<ChipTile> = listOf(ChipTile.DEMAND, ChipTile.GUARD, ChipTile.MEAL)
        }
    }

    /** The last FUSE cycle for the chip on the main screen; `null` = no cycle in this process yet. */
    fun fuseChip(): Chip? = null

    /** The stored layout of the chip. */
    fun fuseChipLayout(): ChipLayout = ChipLayout()

    /** Stores the layout of the chip. It is kept across restarts and goes into the settings export. */
    fun fuseStoreChipLayout(layout: ChipLayout) {}

    /** `null` = ohne Rueckfrage ausfuehren (Ruecknahme). */
    suspend fun fuseMarkerPrompt(now: Long): MarkerPromptFacts?

    /** Schaltet um. @return ob der Marker DANACH laeuft. */
    /**
     * @param ohneVorschuss true = "Nur Marker - 0 U": die Mahlzeit ist angekuendigt (Fensterregeln, Rampen und
     * Totband-Oeffnung wie immer), aber ohne Erwartungsinsulin - das Konto laeuft ohne Budget, nur der gemessene
     * Anteil, und der Liveness-Kanal bleibt an (Toni 26.10.2026). Die Wahl faellt im Dialog des Knopfdrucks, nicht
     * in den Einstellungen.
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
        /** Wahl "Mahlzeit" (bis 24.10.2026 "Nur Erwartung"): das Erwartungskonto traegt die Mahlzeit. */
        nurErwartung: Boolean = false,
        /** Wahl "Snack" (Toni 24.10.2026): wie "Mahlzeit", aber das Budget mit dem Snack-Skalierer. */
        snack: Boolean = false,
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

    /**
     * HCL, part 1 (Toni 25.10.2026): a bolus from the AAPS bolus calculator with carbs, reported ONCE by the shared
     * bolus core after the pump answered, with what was really delivered. FUSE only records it for the trail and the
     * viewer; it does not dose from it. Default: ignored.
     */
    fun fuseWizardBolus(bolus: WizardBolus) {}

    /** One bolus calculation with carbs and what the pump delivered for it. */
    data class WizardBolus(
        /** Time of the calculation [ms] - one per calculation, the key. */
        val calcTs: Long,
        /** Planned meal time [ms]: the carbs time of the calculation, with its offset. */
        val mealTs: Long,
        val carbsG: Double,
        val icGPerU: Double,
        val isfMgdlPerU: Double,
        /** Carbs / IC before the percentage [U]. */
        val carbsInsulinU: Double,
        /** The other parts of the calculation [U]; `null` = not used in this calculation. */
        val glucoseInsulinU: Double?,
        val trendInsulinU: Double?,
        val cobInsulinU: Double?,
        /** Bolus and basal IOB the calculator took off [U, positive]; `null` = not used. */
        val iobU: Double?,
        val superbolusU: Double?,
        /** Direct correction typed in by the user [U]. */
        val directCorrectionU: Double,
        /** Percentage applied to the suggestion parts (BG, trend, carbs, COB). */
        val percentage: Int,
        /** The calculated total [U], after percentage and IOB. */
        val calcTotalU: Double,
        /** What was sent to the pump [U], after the constraints. */
        val requestedU: Double,
        /** What the pump reports as delivered [U]; 0 when it failed. */
        val deliveredU: Double,
        val success: Boolean,
    )
}
