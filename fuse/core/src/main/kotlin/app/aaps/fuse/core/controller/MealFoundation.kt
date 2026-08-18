package app.aaps.fuse.core.controller

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * DAS MAHLZEITENFUNDAMENT - Phase B der zeitlichen Budgetverteilung.
 *
 * Es eroeffnet KEIN neues Insulinbudget. Es verteilt das per Marker bereits
 * autorisierte Budget zeitlich anders: ein Teil sofort (Phase A, die
 * bestehende Huelle/Prime), der Rest ueber ein Fenster verteilt und dabei
 * jederzeit widerrufbar.
 *
 * WOZU (Spezifikation 7.3): zu viel sofortiges Prime erzeugt eine hohe fruehe
 * IOB-Spitze und danach eine SELBST ERZEUGTE Guard-Luecke - die kohlenhydrat-
 * freie Bahn sieht das eigene Insulin als Ueberdeckung und schweigt, waehrend
 * die Absorption erst anlaeuft. Zu langsame Verteilung verliert den
 * Fruehvorteil, auf den FCL angewiesen ist. Phase B nimmt einen Teil aus der
 * Spitze heraus und laesst ihn nachlaufen.
 *
 * TONIS REPLAY-KANDIDAT (18.08.), ausdruecklich KEIN Therapiewert:
 *
 *     Gesamtbudget  3,00 U (unveraendert)
 *     Phase A       2,25 U bis T+15 min   (75 %)
 *     Phase B       0,75 U von T+15 bis T+60 min (25 %)
 *                   pumpenschrittweise, bei 0,05 U etwa ein Schritt je 3 min
 *
 * Begruendung dieser Form, aus Tonis Messungen: die fruehen 3 U haben Peaks
 * nachweislich gut begrenzt, also nicht drastisch reduzieren; zwei Mahlzeiten
 * landeten rund 0,45 U zu tief, eine Budgeterhoehung ist damit nicht
 * begruendet; T+60 statt T+45 laesst mehr Regelreserve fuer den Fall, dass die
 * Absorption stark wird und EvidenceStock zusaetzlich freigeben darf.
 *
 * DIESER BAUSTEIN DOSIERT NICHT. Er rechnet, was nach Plan FAELLIG waere.
 * Jede reale Grenze - gemessenes Tief, Signalgesundheit, iobTH, maxIOB,
 * Transporthaftung, Pumpenschritt, Publikationsgate - liegt danach und bleibt
 * unveraendert wirksam. Er ist ein Vorschlag, kein Freibrief.
 */
object MealFoundation {

    /**
     * Was Phase B zu diesem Zeitpunkt freigeben duerfte.
     *
     * @param dueU der Betrag, den dieser Zyklus vorschlagen darf - schon auf
     *   Pumpenschritte gerastert und auf EINEN Schritt begrenzt. 0.0 heisst
     *   "nichts faellig", nicht "gesperrt".
     * @param plannedTotalU das Soll von Phase B bis JETZT.
     * @param remainingInWindowU was im Fenster noch offen ist.
     * @param binding der Grund, warum nicht mehr freigegeben wird. `null` nur,
     *   wenn der Plan selbst nichts weiter vorsieht.
     */
    data class Plan(
        val dueU: Double,
        val plannedTotalU: Double,
        val remainingInWindowU: Double,
        val binding: Binding?,
    )

    /**
     * WARUM NICHT MEHR - typisiert, nicht als Text.
     *
     * Der Export muss unterscheiden koennen, ob das Fenster zu ist, das Budget
     * aufgebraucht oder nur dieser Zyklus schon bedient wurde. Drei sehr
     * verschiedene Lagen, die als blosse Null identisch aussaehen.
     */
    enum class Binding {

        /** Vor T+15: Phase B hat noch nicht begonnen. */
        BEFORE_WINDOW,

        /** Nach T+60: was jetzt noch offen ist, VERFAELLT (Tonis Auflage). */
        AFTER_WINDOW,

        /** Das gemeinsame Budget ist ausgeschoepft - Phase A und B zusammen. */
        BUDGET_EXHAUSTED,

        /** Der Plan ist bis hier erfuellt; der naechste Schritt kommt spaeter. */
        ON_SCHEDULE,

        /**
         * ES IST SCHON GENUG GEFLOSSEN, um das Soll zu decken.
         *
         * Das Fundament ist eine Mindestversorgung: es hebt nur eine fehlende
         * Menge an. Ist die Versorgung ohnehin da, schweigt es - eigener
         * Grund, weil das im Export etwas anderes bedeutet als [ON_SCHEDULE].
         * Dort ist die Zeit noch nicht reif, hier ist die Menge schon da.
         *
         * DER NAME SAGT BEWUSST NICHT, WER GELIEFERT HAT (Toni 18.08.). Er
         * hiess zunaechst COVERED_BY_NORMAL_PATH, und das war eine Behauptung,
         * die dieser Baustein gar nicht aufstellen kann:
         * `deliveredSinceHandoverU` enthaelt ausdruecklich ALLE Mengen -
         * normale FUSE-SMBs UND frueher freigegebene Fundamentschritte. Ein
         * eigener Fundamentschritt haette also "der normale Pfad war es"
         * gemeldet. Eine Unterscheidung nach Herkunft gehoert in den Export,
         * und nur dann, wenn es dafuer eine belastbare Provenienz gibt.
         */
        COVERED_BY_DELIVERY,

        /** Ein Pumpenschritt je Zyklus, nicht mehr - kein Aufhol-Burst. */
        ONE_STEP_PER_CYCLE,

        /** Eingaben unbrauchbar. Fail-closed: es wird nichts vorgeschlagen. */
        UNUSABLE_INPUT,
    }

    /**
     * @param markerTs wann der Marker gedrueckt wurde. Der Nullpunkt T+0.
     * @param nowTs jetzt.
     * @param totalBudgetU das GEMEINSAME autorisierte Budget (Phase A + B).
     * @param phaseAShare Anteil von Phase A am Budget, z.B. 0.75.
     * @param handoverTs der GEMEINSAME Uebergabeanker aus [handoverTs] - der
     *   Zeitpunkt, an dem Phase A endet und Phase B beginnt. Er wird nicht
     *   hier gerechnet, weil Prime ihn verschieben kann (CLEARANCE) und beide
     *   dieselbe Zahl brauchen.
     * @param phaseBUntilMin bis wann Phase B laeuft, gerechnet AB MARKER.
     *
     *   Das Ende bleibt am Marker verankert, nicht am Uebergabeanker: sonst
     *   koennte eine Kette von CLEARANCE-Freigaben das Fenster immer weiter
     *   nach hinten schieben, und aus "bis T+60" wuerde eine Versorgung ohne
     *   Ende. Verschiebt sich die Uebergabe, wird das Fenster KUERZER - das
     *   ist die konservative Richtung.
     * @param deliveredFromBudgetU was aus DIESEM Budget schon geflossen ist -
     *   Phase A UND Phase B zusammen. Ohne diese Zahl entstuende genau die
     *   Doppelfinanzierung, die Spezifikation 3.1 verbietet.
     * @param deliveredSinceHandoverU was seit der Uebergabe INSGESAMT geflossen
     *   ist - Fundamentschritte UND normale FUSE-Mengen.
     *
     *   DAS FUNDAMENT IST EINE MINDESTVERSORGUNG, KEIN ADDITIVER BOLUS (Toni
     *   18.08., Punkt 5). Gibt der normale Pfad schon genug ab, gilt das
     *   zeitliche Soll als bedient - das Fundament hebt nur eine FEHLENDE
     *   Menge an. Es darf niemals zu jedem normalen SMB einen zusaetzlichen
     *   Schritt addieren; genau das waere die IOB-Spitze, die es vermeiden
     *   soll, nur zeitlich verschoben.
     *
     *   Deshalb wird das Soll gegen ALLES gerechnet, was seit der Uebergabe
     *   geflossen ist, nicht nur gegen die eigenen Schritte. Ein eigener
     *   Fundamentzaehler waere hier die falsche Groesse: er saehe eine Luecke,
     *   wo die Versorgung laengst laeuft.
     *
     *   Der erste Wurf leitete den Verbrauch als `geflossen - phaseABudget`
     *   ab. Das stimmt nur, wenn Phase A ihr Budget auch ausschoepft; hat die
     *   Huelle weniger genommen, lieferte das Fundament fast das ganze Budget
     *   nach (gemessen 2,15 U statt 0,75 U).
     * @param bolusStepU die Rasterung der Pumpe.
     */
    fun plan(
        markerTs: Long,
        nowTs: Long,
        handoverTs: Long,
        totalBudgetU: Double,
        phaseAShare: Double,
        phaseBUntilMin: Int,
        deliveredFromBudgetU: Double,
        deliveredSinceHandoverU: Double,
        bolusStepU: Double,
    ): Plan {
        // FAIL-CLOSED BEI JEDER UNBRAUCHBAREN EINGABE. Ein Fundament, das auf
        // NaN oder einer unsinnigen Fensterreihenfolge etwas vorschlaegt, waere
        // gefaehrlicher als eines, das schweigt.
        if (markerTs <= 0L || nowTs < markerTs) return unusable()
        if (handoverTs < markerTs) return unusable()
        if (!totalBudgetU.isFinite() || totalBudgetU <= 0.0) return unusable()
        if (!phaseAShare.isFinite() || phaseAShare < 0.0 || phaseAShare > 1.0) return unusable()
        // DAS FENSTER MUSS NOCH EXISTIEREN. Hat eine CLEARANCE die Uebergabe
        // hinter das Ende geschoben, gibt es kein Phase-B-Fenster mehr - kein
        // Fehler, aber auch keine Versorgung.
        val fensterEndeTs = markerTs + phaseBUntilMin * 60_000L
        if (phaseBUntilMin < 0 || fensterEndeTs <= handoverTs) return unusable()
        if (!deliveredFromBudgetU.isFinite() || deliveredFromBudgetU < 0.0) return unusable()
        if (!deliveredSinceHandoverU.isFinite() || deliveredSinceHandoverU < 0.0) return unusable()
        if (!bolusStepU.isFinite() || bolusStepU <= 0.0) return unusable()

        val phaseBBudgetU = totalBudgetU * (1.0 - phaseAShare)

        // VOR DEM FENSTER: Phase A ist zustaendig, nicht das Fundament.
        if (nowTs < handoverTs) return Plan(0.0, 0.0, phaseBBudgetU, Binding.BEFORE_WINDOW)

        // DAS SOLL BIS JETZT - linear ueber das Fenster.
        //
        // Linear und nicht kurvig: eine Form, die niemand messen kann, ist
        // eine Annahme mehr, die spaeter als Erklaerung fuer alles herhaelt.
        // Die Verteilung ist ohnehin eine Replay-Hypothese; sie soll einfach
        // genug sein, um sie zu widerlegen.
        val fensterMs = (fensterEndeTs - handoverTs).toDouble()
        val fortschritt = min(1.0, (nowTs - handoverTs) / fensterMs)
        val sollU = phaseBBudgetU * fortschritt

        // ZWEI GRENZEN, DIE BEIDE GELTEN:
        //   1. Phase B liefert nie mehr als ihren Anteil,
        //   2. Phase A und B zusammen nie mehr als das gemeinsame Budget.
        //
        // Die zweite bindet, wenn Phase A mehr genommen hat als vorgesehen;
        // die erste, wenn sie weniger genommen hat. Nur eine von beiden zu
        // pruefen laesst je eine Luecke offen - die zweite hat der Test als
        // 2,15 U statt 0,75 U gezeigt.
        val ausPhaseBGeflossen = deliveredSinceHandoverU
        val offenImFenster = max(
            0.0,
            min(
                phaseBBudgetU - ausPhaseBGeflossen,
                totalBudgetU - deliveredFromBudgetU,
            ),
        )

        // NACH DEM FENSTER VERFAELLT DER REST (Tonis Auflage). Kein Nachliefern
        // Stunden spaeter - was dann noch offen ist, war offenbar nicht noetig.
        if (nowTs > fensterEndeTs)
            return Plan(0.0, phaseBBudgetU, offenImFenster, Binding.AFTER_WINDOW)

        // DAS GEMEINSAME BUDGET IST DIE HARTE GRENZE.
        if (deliveredFromBudgetU >= totalBudgetU - 1e-9)
            return Plan(0.0, sollU, 0.0, Binding.BUDGET_EXHAUSTED)

        val rueckstandU = min(sollU - ausPhaseBGeflossen, offenImFenster)
        if (rueckstandU < bolusStepU - 1e-9) return Plan(
            0.0, sollU, offenImFenster,
            // ZWEI SEHR VERSCHIEDENE GRUENDE FUER DIESELBE NULL. Ist schon
            // mehr geflossen als das Soll, hat der normale Pfad geliefert;
            // liegt es nur an der Zeit, ist der Plan erfuellt.
            if (ausPhaseBGeflossen >= sollU - 1e-9 && ausPhaseBGeflossen > 0.0)
                Binding.COVERED_BY_DELIVERY else Binding.ON_SCHEDULE,
        )

        // EIN SCHRITT JE ZYKLUS - kein Aufhol-Burst (Tonis Auflage).
        //
        // Ein Rueckstand von mehreren Schritten kann entstehen, wenn Zyklen
        // ausfielen oder Schritte abgelehnt wurden. Ihn in einem Zug
        // nachzuholen waere genau die IOB-Spitze, die das Fundament vermeiden
        // soll. Der Rueckstand laeuft stattdessen nach - solange das Fenster
        // offen ist.
        val schritte = floor(rueckstandU / bolusStepU + 1e-9)
        val einSchrittU = min(bolusStepU, offenImFenster)
        return Plan(
            dueU = einSchrittU,
            plannedTotalU = sollU,
            remainingInWindowU = offenImFenster,
            binding = if (schritte > 1.0) Binding.ONE_STEP_PER_CYCLE else null,
        )
    }

    private fun unusable() = Plan(0.0, 0.0, 0.0, Binding.UNUSABLE_INPUT)

    /**
     * DER GEMEINSAME UEBERGABEANKER - eine Funktion fuer Prime UND Fundament
     * (Toni 18.08.).
     *
     * WARUM EIN SETTING NICHT GENUEGT HAT. `MealFoundation` rechnete starr ab
     * `markerTs + PrimeWindowMin`. Prime selbst rechnet aber ab
     * `maxOf(markerTs, episodes.primeWindowStartTs)` - und verschiebt
     * `primeWindowStartTs` bei einer CLEARANCE auf den aktuellen Zyklus
     * (FuseCycleRunner: `if (primePlan.reason == "CLEARANCE")
     * episodes.primeWindowStartTs = computeTs`). Trotz nur EINES Settings
     * konnten Phase A und B damit ueberlappen: die Huelle laeuft ab dem
     * verschobenen Start weiter, waehrend das Fundament laengst zaehlt.
     *
     * Erschwerend steht die Prime-Rechnung ZWEIMAL im Runner - im Hauptpfad
     * und im markerFallback. Genau deshalb ist das hier eine Funktion und
     * keine Formel an drei Stellen.
     *
     * DIE WANDUHR-DECKE begrenzt, wie weit eine CLEARANCE die Uebergabe
     * schieben darf. Ohne sie koennte eine Kette von Freigaben das Fundament
     * bis hinter sein eigenes Fensterende verschieben - es kaeme dann nie zum
     * Zug, und niemand saehe warum.
     *
     * @param primeWindowStartTs der von Prime gefuehrte Fensterstart. 0 heisst
     *   "unverschoben", dann gilt der Marker.
     * @param wallCeilingMin die Decke ab MARKER, nicht ab dem verschobenen
     *   Start. Ohne Default: der Aufrufer muss sich erklaeren.
     * @return der Zeitpunkt, an dem Phase A endet und Phase B beginnt. 0L,
     *   wenn kein Marker vorliegt - dann gibt es kein Fundament.
     */
    fun handoverTs(
        markerTs: Long,
        primeWindowStartTs: Long,
        primeWindowMin: Int,
        wallCeilingMin: Int,
    ): Long {
        if (markerTs <= 0L) return 0L
        if (primeWindowMin < 0 || wallCeilingMin < 0) return 0L
        val ausPrime = max(markerTs, primeWindowStartTs) + primeWindowMin * 60_000L
        val decke = markerTs + wallCeilingMin * 60_000L
        return min(ausPrime, decke)
    }

    /**
     * WAS PHASE A - also die bestehende Huelle - freigeben darf.
     *
     * EINE FUNKTION FUER BEIDE FAELLE (Toni 18.08., Punkte 3 und 4), weil sie
     * genau zusammengehoeren:
     *
     *  - SCHALTER AUS: die Huelle bekommt das GANZE Budget, unabhaengig von
     *    einem gespeicherten Anteil. Ein hinterlegtes 0,75 darf die Versorgung
     *    nicht unbemerkt kuerzen, nur weil jemand den Wert einmal eingestellt
     *    und das Fundament dann abgeschaltet hat. Verhaltensparitaet heisst
     *    bitgleich, nicht "fast wie vorher".
     *
     *  - SCHALTER AN: die Huelle bekommt NUR ihren Anteil. Sonst entstuenden
     *    3,0 U Prime PLUS 0,75 U Fundament - also mehr, als autorisiert wurde.
     *
     * Zwei getrennte Rechnungen an zwei Aufrufstellen wuerden genau hier
     * auseinanderlaufen, und die Richtung waere im einen Fall zu wenig
     * Insulin, im anderen zu viel.
     */
    fun phaseABudgetU(totalBudgetU: Double, phaseAShare: Double, foundationEnabled: Boolean): Double {
        if (!totalBudgetU.isFinite() || totalBudgetU <= 0.0) return 0.0
        if (!foundationEnabled) return totalBudgetU
        if (!phaseAShare.isFinite() || phaseAShare < 0.0 || phaseAShare > 1.0) return totalBudgetU
        return totalBudgetU * phaseAShare
    }

    /**
     * KEINE KONSTANTEN FUER DIE PARAMETER - sie sind Preferences.
     *
     * Der erste Wurf hatte REPLAY_PHASE_A_SHARE und die beiden Zeitgrenzen
     * hier als `const val`. Toni 18.08.: "alles sollte aber konfigurierbar
     * sein auch was das fundament angeht" - und er hat recht, aus einem Grund,
     * der ueber Bequemlichkeit hinausgeht: eine Konstante hier und eine
     * Preference dort waeren zwei Wahrheiten ueber denselben Wert, und beim
     * ersten Verstellen am Geraet waere nicht mehr erkennbar, welche gilt.
     *
     * Die Werte stehen jetzt in FuseDoubleKey.MealFoundationPhaseAShare und
     * FuseIntKey.MealFoundationEndMin; die Uebergabe ist FuseIntKey.
     * PrimeWindowMin, also DIESELBE Grenze, an der Phase A endet. Tonis
     * Replay-Kandidat (0,75 / 15 / 60) steht in den Kommentaren dort, aber der
     * DEFAULT des Anteils ist 1.0: ein Flash darf das Verhalten nicht aendern.
     *
     * ABGELEITET, NICHT EINSTELLBAR bleiben die absoluten Mengen. Sie ergeben
     * sich aus dem gemeinsamen Budget (PrimeEnvelopeU) und dem Anteil; ein
     * eigener Knopf dafuer waere genau die zweite Wahrheit, die Spezifikation
     * 13.7 ausschliesst.
     */
}
