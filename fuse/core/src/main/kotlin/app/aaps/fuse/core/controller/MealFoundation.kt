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
     * DIE BEIM ARMEN EINGEFRORENE AUTORISIERUNG (Toni 18.08.).
     *
     * WARUM NICHT JEDEN ZYKLUS NEU LESEN. Alle Werte sind einstellbar - und
     * genau deshalb duerfen sie eine LAUFENDE Mahlzeit nicht mehr veraendern.
     * Wuerde `PrimeEnvelopeU` bei T+40 erhoeht, entstuende zusaetzliches
     * "bereits autorisiertes" Insulin, das niemand autorisiert hat; wuerde es
     * gesenkt, oeffnete sich verbrauchtes Budget wieder. Beides waere eine
     * nachtraegliche Aenderung an einer Insulinfreigabe, die der Nutzer
     * einmal und bewusst erteilt hat.
     *
     * Konfigurierbar heisst: BEIM NAECHSTEN MARKERDRUCK waehlbar. Nicht:
     * waehrend der Wirkung nachjustierbar.
     *
     * DIE TEILBUDGETS SIND MOMENTAUFNAHMEN, keine abgeleiteten Groessen mehr.
     * Sie stehen hier ausgerechnet, weil sie sonst aus dem gepinnten Gesamt
     * und einem SPAETER gelesenen Anteil entstuenden - wieder zwei Wahrheiten.
     *
     * SOLANGE DAS FUNDAMENT AKTIV IST, MUSS AUCH PRIME HIERAUS LESEN. Sonst
     * rechnete die Huelle live mit einem geaenderten Budget, waehrend Phase B
     * den alten Gesamtbetrag verwendet - zwei Wahrheiten ueber dieselbe
     * Autorisierung, und die Summe waere weder das eine noch das andere.
     */
    data class Authorization(
        /** Wann diese Autorisierung entstand - der Markerdruck. */
        val armedTs: Long,
        val totalBudgetU: Double,
        val phaseAShare: Double,
        /** Momentaufnahme: totalBudgetU * phaseAShare. */
        val phaseABudgetU: Double,
        /** Momentaufnahme: totalBudgetU * (1 - phaseAShare). */
        val phaseBBudgetU: Double,
        /** Der festgeschriebene Uebergabeanker - restartfest. */
        val handoverTs: Long,
        /** Das festgeschriebene Fensterende - eine spaetere Aenderung der
         *  Endzeit verlaengert die laufende Phase nicht. */
        val endTs: Long,
    ) {

        /** Traegt sie eine brauchbare Autorisierung? */
        val valid: Boolean
            get() = armedTs > 0L && totalBudgetU.isFinite() && totalBudgetU > 0.0 &&
                phaseAShare.isFinite() && phaseAShare in 0.0..1.0 &&
                phaseABudgetU.isFinite() && phaseBBudgetU.isFinite() &&
                phaseABudgetU >= 0.0 && phaseBBudgetU >= 0.0 &&
                handoverTs >= armedTs && endTs > armedTs

        companion object {

            /** Keine laufende Autorisierung - das Fundament ist nicht armiert. */
            fun none() = Authorization(0L, 0.0, 0.0, 0.0, 0.0, 0L, 0L)
        }
    }

    /**
     * EINE NEUE AUTORISIERUNG ARMIEREN - nur beim bewussten Markerdruck.
     *
     * @param foundationEnabled ist das Fundament eingeschaltet? Wenn nicht,
     *   entsteht KEINE Momentaufnahme (Toni 18.08.): das heutige
     *   Prime-Verhalten bleibt unveraendert, und es gibt nichts, was spaeter
     *   ein Fundament rechtfertigen koennte.
     *
     *   Damit ist auch der Fall abgedeckt, dass jemand den Schalter MITTEN in
     *   einer laufenden Episode umlegt: es gibt dann keine Autorisierung, also
     *   kein rueckwirkendes Soll und keinen Aufholstrom. Armiert wird erst das
     *   naechste bewusst eroeffnete Markerbudget.
     */
    fun arm(
        markerTs: Long,
        foundationEnabled: Boolean,
        totalBudgetU: Double,
        phaseAShare: Double,
        primeWindowStartTs: Long,
        primeWindowMin: Int,
        wallCeilingMin: Int,
        phaseBUntilMin: Int,
    ): Authorization {
        if (!foundationEnabled || markerTs <= 0L) return Authorization.none()
        if (!totalBudgetU.isFinite() || totalBudgetU <= 0.0) return Authorization.none()
        if (!phaseAShare.isFinite() || phaseAShare !in 0.0..1.0) return Authorization.none()
        if (phaseBUntilMin <= 0) return Authorization.none()
        val uebergabe = handoverTs(markerTs, primeWindowStartTs, primeWindowMin, wallCeilingMin)
        if (uebergabe <= 0L) return Authorization.none()
        return Authorization(
            armedTs = markerTs,
            totalBudgetU = totalBudgetU,
            phaseAShare = phaseAShare,
            // AUSGERECHNET UND EINGEFROREN, nicht spaeter abgeleitet.
            phaseABudgetU = totalBudgetU * phaseAShare,
            phaseBBudgetU = totalBudgetU * (1.0 - phaseAShare),
            handoverTs = uebergabe,
            endTs = markerTs + phaseBUntilMin * 60_000L,
        )
    }

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
        /**
         * WIE LANG DAS FENSTER TATSAECHLICH IST [min].
         *
         * Eine verschobene Uebergabe komprimiert das GANZE Phase-B-Budget in
         * die verbleibende Zeit (Toni 18.08.): aus 0,05 U etwa alle drei
         * Minuten koennen 0,05 U je Minute werden. Das ist heute bewusst so
         * gelassen - aber es MUSS sichtbar sein, sonst faellt im Replay eine
         * Verdreifachung der Rate niemandem auf.
         */
        val effectiveWindowMin: Int = 0,
        /** Die daraus folgende Sollrate [U/min] - dieselbe Groesse, in der
         *  Form, in der sie im Replay verglichen wird. */
        val effectiveRateUPerMin: Double = 0.0,
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

        /**
         * DIE UEBERGABE LIEGT AUF ODER HINTER DEM FENSTERENDE.
         *
         * Eine GUELTIGE Lage, kein Eingabefehler (Toni 18.08.): eine Kette von
         * Clearances hat das Prime-Fenster so weit geschoben, dass fuer Phase B
         * nichts mehr uebrig bleibt. Das ist eine Aussage ueber diese
         * Mahlzeit, keine ueber die Eingaben - und im Export etwas anderes
         * wert als "unbrauchbar".
         */
        NO_WINDOW_AFTER_HANDOVER,

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
        if (phaseBUntilMin < 0) return unusable()
        val fensterEndeTs = markerTs + phaseBUntilMin * 60_000L
        // KEIN EINGABEFEHLER, sondern eine gueltige Lage ohne Fenster.
        if (fensterEndeTs <= handoverTs)
            return Plan(0.0, 0.0, 0.0, Binding.NO_WINDOW_AFTER_HANDOVER, effectiveWindowMin = 0)
        if (!deliveredFromBudgetU.isFinite() || deliveredFromBudgetU < 0.0) return unusable()
        if (!deliveredSinceHandoverU.isFinite() || deliveredSinceHandoverU < 0.0) return unusable()
        if (!bolusStepU.isFinite() || bolusStepU <= 0.0) return unusable()

        val phaseBBudgetU = totalBudgetU * (1.0 - phaseAShare)

        val fensterMin = ((fensterEndeTs - handoverTs) / 60_000L).toInt()
        val rateUProMin = if (fensterMin > 0) phaseBBudgetU / fensterMin else 0.0

        // VOR DEM FENSTER: Phase A ist zustaendig, nicht das Fundament.
        if (nowTs < handoverTs)
            return Plan(0.0, 0.0, phaseBBudgetU, Binding.BEFORE_WINDOW, fensterMin, rateUProMin)

        // DAS SOLL BIS JETZT - linear ueber das Fenster.
        //
        // Linear und nicht kurvig: eine Form, die niemand messen kann, ist
        // eine Annahme mehr, die spaeter als Erklaerung fuer alles herhaelt.
        // Die Verteilung ist ohnehin eine Replay-Hypothese; sie soll einfach
        // genug sein, um sie zu widerlegen.
        val fortschritt = min(1.0, (nowTs - handoverTs).toDouble() / (fensterEndeTs - handoverTs))
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
            return Plan(0.0, phaseBBudgetU, offenImFenster, Binding.AFTER_WINDOW, fensterMin, rateUProMin)

        // DAS GEMEINSAME BUDGET IST DIE HARTE GRENZE.
        if (deliveredFromBudgetU >= totalBudgetU - 1e-9)
            return Plan(0.0, sollU, 0.0, Binding.BUDGET_EXHAUSTED, fensterMin, rateUProMin)

        val rueckstandU = min(sollU - ausPhaseBGeflossen, offenImFenster)
        if (rueckstandU < bolusStepU - 1e-9) return Plan(
            0.0, sollU, offenImFenster,
            // ZWEI SEHR VERSCHIEDENE GRUENDE FUER DIESELBE NULL. Ist schon
            // mehr geflossen als das Soll, hat der normale Pfad geliefert;
            // liegt es nur an der Zeit, ist der Plan erfuellt.
            if (ausPhaseBGeflossen >= sollU - 1e-9 && ausPhaseBGeflossen > 0.0)
                Binding.COVERED_BY_DELIVERY else Binding.ON_SCHEDULE,
            fensterMin, rateUProMin,
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
            effectiveWindowMin = fensterMin,
            effectiveRateUPerMin = rateUProMin,
        )
    }

    /**
     * DER PLAN AUS DER GEPINNTEN AUTORISIERUNG - der Weg, den der Zyklus geht.
     *
     * Er nimmt KEINE Live-Einstellungen entgegen. Das ist der ganze Zweck:
     * eine Aenderung an PrimeEnvelopeU, Anteil oder Endzeit kann diesen Aufruf
     * gar nicht mehr erreichen. Wer die Rohfassung mit Einzelwerten benutzt,
     * muss sich erklaeren - sie bleibt fuer Tests und Replay.
     *
     * @param deliveredFromBudgetU alles, was aus DIESEM Budget geflossen ist.
     * @param deliveredSinceHandoverU alles seit der Uebergabe - die
     *   Mindestversorgung zaehlt jede publizierte Menge, gleich welcher
     *   Herkunft.
     */
    fun planFrom(
        auth: Authorization,
        nowTs: Long,
        deliveredFromBudgetU: Double,
        deliveredSinceHandoverU: Double,
        bolusStepU: Double,
    ): Plan {
        if (!auth.valid) return unusable()
        return plan(
            markerTs = auth.armedTs,
            nowTs = nowTs,
            handoverTs = auth.handoverTs,
            totalBudgetU = auth.totalBudgetU,
            phaseAShare = auth.phaseAShare,
            // Aus der Momentaufnahme zurueckgerechnet, damit die Rohfassung
            // eine Signatur behaelt: endTs ist gepinnt, die Minuten sind es
            // damit auch.
            phaseBUntilMin = ((auth.endTs - auth.armedTs) / 60_000L).toInt(),
            deliveredFromBudgetU = deliveredFromBudgetU,
            deliveredSinceHandoverU = deliveredSinceHandoverU,
            bolusStepU = bolusStepU,
        )
    }

    /**
     * WAS PRIME FREIGEBEN DARF - aus derselben Quelle wie Phase B.
     *
     * Solange eine Autorisierung laeuft, liest Prime ihr gepinntes
     * Phase-A-Budget. Sonst rechnete die Huelle live mit einem geaenderten
     * PrimeEnvelopeU, waehrend Phase B den alten Gesamtbetrag verwendet - zwei
     * Wahrheiten ueber dieselbe Autorisierung.
     *
     * Ohne laufende Autorisierung (Fundament aus oder nicht armiert) gilt
     * unveraendert das Live-Budget: das ist der heutige Stand, und er muss
     * bitgleich bleiben.
     */
    fun primeBudgetU(auth: Authorization, liveTotalBudgetU: Double): Double =
        if (auth.valid) auth.phaseABudgetU else liveTotalBudgetU

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
