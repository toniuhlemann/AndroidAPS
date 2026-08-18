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
     * DIE BEIM ARMEN EINGEFRORENEN REGELPARAMETER (Toni 18.08.).
     *
     * WARUM NICHT JEDEN ZYKLUS NEU LESEN. Alle Werte sind einstellbar - und
     * genau deshalb duerfen sie eine LAUFENDE Mahlzeit nicht mehr veraendern.
     * Wuerde das Budget bei T+40 erhoeht, entstuende zusaetzliches "bereits
     * autorisiertes" Insulin, das niemand autorisiert hat; wuerde es gesenkt,
     * oeffnete sich verbrauchtes Budget wieder.
     *
     * Konfigurierbar heisst: BEIM NAECHSTEN MARKERDRUCK waehlbar. Nicht:
     * waehrend der Wirkung nachjustierbar.
     *
     * KEINE data class UND KEIN OEFFENTLICHER KONSTRUKTOR (Toni 18.08., P0-1).
     * Der erste Wurf war eine data class mit GESPEICHERTEN Teilbudgets - copy()
     * konnte damit einen Zustand erzeugen, in dem das A-Budget 3,00 und das
     * B-Budget 0,75 betrug, obwohl das Gesamtbudget 3,00 war. Er galt sogar als
     * gueltig, weil nur auf Endlichkeit geprueft wurde. Da primeBudgetU() das
     * gespeicherte A las, planFrom() das B aber neu rechnete, haetten Prime und
     * Fundament zusammen 3,75 U aus einer 3-U-Autorisierung gesehen.
     *
     * Die Teilbudgets sind deshalb ABGELEITET, nicht gespeichert. Meine
     * Begruendung dagegen war falsch: sind Gesamt UND Anteil gepinnt, wird
     * nirgends ein spaeterer Live-Anteil gelesen - die Ableitung IST die eine
     * Wahrheit.
     *
     * DIE UEBERGABE GEHOERT NICHT HIERHER (P0-2). Sie steht beim Markerdruck
     * noch gar nicht fest: eine spaetere CLEARANCE verschiebt das
     * Prime-Fenster, und ein zu frueh eingefrorener Anker braechte genau die
     * Ueberlappung zurueck, die er verhindern soll. Gepinnt werden die
     * REGELPARAMETER; der Zeitpunkt folgt der Laufzeit, bis er beim
     * tatsaechlichen Uebergang gelatcht wird.
     */
    class Authorization private constructor(
        /** Wann diese Autorisierung entstand - der Markerdruck. */
        val armedTs: Long,
        val totalBudgetU: Double,
        val phaseAShare: Double,
        /** Das beim Armen gueltige Prime-Fenster [min] - gepinnt, damit eine
         *  spaetere Aenderung die laufende Uebergabe nicht verschiebt. */
        val pinnedPrimeWindowMin: Int,
        /** Die beim Armen gueltige Wanduhr-Decke [min]. */
        val pinnedWallCeilingMin: Int,
        /** Das festgeschriebene Fensterende. */
        val endTs: Long,
        /**
         * DER ENDGUELTIG GELATCHTE UEBERGABEANKER. 0 heisst "noch nicht
         * uebergeben" - dann folgt der Anker weiter der Prime-Laufzeit.
         */
        val latchedHandoverTs: Long,
    ) {

        /** ABGELEITET, nicht gespeichert - es gibt nur eine Wahrheit. */
        val phaseABudgetU: Double get() = totalBudgetU * phaseAShare

        /** Exakt komplementaer, damit A + B ohne Rundungsrest das Gesamt
         *  ergibt. Ein zweites Produkt waere es nicht. */
        val phaseBBudgetU: Double get() = totalBudgetU - phaseABudgetU

        val valid: Boolean
            get() = armedTs > 0L && totalBudgetU.isFinite() && totalBudgetU > 0.0 &&
                phaseAShare.isFinite() && phaseAShare in 0.0..1.0 &&
                pinnedPrimeWindowMin >= 0 && pinnedWallCeilingMin >= 0 &&
                endTs > armedTs && latchedHandoverTs >= 0L &&
                (latchedHandoverTs == 0L || latchedHandoverTs >= armedTs)

        /**
         * DER AKTUELL GELTENDE UEBERGABEZEITPUNKT.
         *
         * Vor dem Latch folgt er der Prime-Laufzeit: eine CLEARANCE verschiebt
         * Prime UND Fundament gemeinsam, sonst begaenne Phase B, waehrend die
         * Huelle noch freigibt. Nach dem Latch steht er fest und wandert nicht
         * mehr.
         */
        fun effectiveHandoverTs(primeWindowStartTs: Long): Long =
            if (latchedHandoverTs > 0L) latchedHandoverTs
            else handoverTs(armedTs, primeWindowStartTs, pinnedPrimeWindowMin, pinnedWallCeilingMin)

        /**
         * DEN UEBERGANG FESTSCHREIBEN - nur wenn er WIRKLICH erreicht ist.
         *
         * KEIN FREIER ZEITSTEMPEL-SETTER (Toni 18.08., P0). Der erste Wurf
         * hiess `latched(handoverTs)` und nahm jeden Zeitpunkt entgegen. Ein
         * Aufrufer konnte damit schon bei T+10 den damals berechneten
         * T+15-Anker festschreiben - eine spaetere Clearance waere wieder
         * ignoriert worden. Der gerade beseitigte Fehler blieb also ueber die
         * API formulierbar, und genau solche Fallen findet spaeter niemand
         * mehr: der Aufruf sieht richtig aus.
         *
         * Jetzt rechnet die Methode den Anker SELBST und latcht nur, wenn er
         * erreicht ist. Ein zu frueher Aufruf tut nichts.
         *
         * Ist er schon gelatcht, bleibt es dabei - sonst haette eine spaete
         * Clearance nachtraeglich doch noch Wirkung.
         */
        fun latchIfDue(nowTs: Long, primeWindowStartTs: Long): Authorization {
            if (latchedHandoverTs > 0L || !valid) return this
            val faellig = effectiveHandoverTs(primeWindowStartTs)
            if (faellig <= 0L || nowTs < faellig) return this
            return Authorization(
                armedTs, totalBudgetU, phaseAShare, pinnedPrimeWindowMin,
                pinnedWallCeilingMin, endTs, faellig,
            )
        }

        companion object {

            /** Keine laufende Autorisierung - das Fundament ist nicht armiert. */
            fun none() = Authorization(0L, 0.0, 0.0, 0, 0, 0L, 0L)

            /**
             * AUS DER PERSISTENZ WIEDERHERSTELLEN - mit Pruefung.
             *
             * Fail-closed: eine widerspruechliche Generation ergibt KEINE
             * Autorisierung, nicht eine halbe. Ein halb gelesenes Budget waere
             * eine Insulinfreigabe, die niemand erteilt hat.
             */
            fun restore(
                armedTs: Long,
                totalBudgetU: Double,
                phaseAShare: Double,
                pinnedPrimeWindowMin: Int,
                pinnedWallCeilingMin: Int,
                endTs: Long,
                latchedHandoverTs: Long,
            ): Authorization {
                val a = Authorization(
                    armedTs, totalBudgetU, phaseAShare, pinnedPrimeWindowMin,
                    pinnedWallCeilingMin, endTs, latchedHandoverTs,
                )
                return if (a.valid) a else none()
            }

            internal fun create(
                armedTs: Long,
                totalBudgetU: Double,
                phaseAShare: Double,
                pinnedPrimeWindowMin: Int,
                pinnedWallCeilingMin: Int,
                endTs: Long,
            ) = Authorization(
                armedTs, totalBudgetU, phaseAShare, pinnedPrimeWindowMin,
                pinnedWallCeilingMin, endTs, 0L,
            )
        }
    }

    /**
     * EINE NEUE AUTORISIERUNG ARMIEREN - nur beim bewussten Markerdruck.
     *
     * Gepinnt werden die REGELPARAMETER, nicht der Uebergabezeitpunkt: der
     * steht hier noch nicht fest (s. [Authorization]).
     *
     * @param foundationEnabled ist das Fundament eingeschaltet? Wenn nicht,
     *   entsteht KEINE Momentaufnahme: das heutige Prime-Verhalten bleibt
     *   unveraendert. Damit ist auch der Fall abgedeckt, dass jemand den
     *   Schalter MITTEN in einer laufenden Episode umlegt - es gibt dann keine
     *   Autorisierung, also kein rueckwirkendes Soll und keinen Aufholstrom.
     */
    fun arm(
        markerTs: Long,
        foundationEnabled: Boolean,
        totalBudgetU: Double,
        phaseAShare: Double,
        primeWindowMin: Int,
        wallCeilingMin: Int,
        phaseBUntilMin: Int,
    ): Authorization {
        if (!foundationEnabled || markerTs <= 0L) return Authorization.none()
        if (!totalBudgetU.isFinite() || totalBudgetU <= 0.0) return Authorization.none()
        if (!phaseAShare.isFinite() || phaseAShare !in 0.0..1.0) return Authorization.none()
        if (phaseBUntilMin <= 0 || primeWindowMin < 0 || wallCeilingMin < 0) return Authorization.none()
        return Authorization.create(
            armedTs = markerTs,
            totalBudgetU = totalBudgetU,
            phaseAShare = phaseAShare,
            pinnedPrimeWindowMin = primeWindowMin,
            pinnedWallCeilingMin = wallCeilingMin,
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
     * @param phaseBBudgetU das Phase-B-Budget - DIREKT, nicht abgeleitet
     *   (Toni 18.08., P1).
     *
     *   Die Rohfassung rechnete es aus `totalBudgetU * (1 - phaseAShare)`
     *   nach, obwohl [Authorization.phaseBBudgetU] es bereits kanonisch
     *   fuehrt. Derselbe Wert ueber einen zweiten Weg ist genau die zweite
     *   Wahrheit, die dieser Baustein sonst vermeidet - und der zweite Weg
     *   ergibt bei ungluecklichen Anteilen sogar einen anderen Rundungsrest
     *   als `total - A`.
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
        phaseBBudgetU: Double,
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
        if (!phaseBBudgetU.isFinite() || phaseBBudgetU < 0.0) return unusable()
        // DAS TEILBUDGET KANN NICHT GROESSER SEIN ALS DAS GANZE. Ein solcher
        // Aufruf koennte nur aus einer widerspruechlichen Quelle stammen -
        // fail-closed statt "wird schon passen".
        if (phaseBBudgetU > totalBudgetU + 1e-9) return unusable()
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
        /**
         * DER LAUFENDE PRIME-FENSTERSTART (Toni 18.08., P0-2).
         *
         * Vor dem Latch folgt die Uebergabe ihm: eine CLEARANCE verschiebt
         * Prime UND Fundament gemeinsam. Nach dem Latch ist er ohne Wirkung -
         * [Authorization.effectiveHandoverTs] gibt dann den festgeschriebenen
         * Anker zurueck.
         */
        primeWindowStartTs: Long,
        deliveredFromBudgetU: Double,
        deliveredSinceHandoverU: Double,
        bolusStepU: Double,
    ): Plan {
        if (!auth.valid) return unusable()
        return plan(
            markerTs = auth.armedTs,
            nowTs = nowTs,
            handoverTs = auth.effectiveHandoverTs(primeWindowStartTs),
            totalBudgetU = auth.totalBudgetU,
            // DIE KANONISCHE GROESSE, nicht noch einmal abgeleitet.
            phaseBBudgetU = auth.phaseBBudgetU,
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
     * IN WELCHER PHASE DES FUNDAMENTS EIN ZYKLUS LIEGT.
     *
     * EIN ENUM UND KEIN BOOLEAN, obwohl nur Phase B einen Zaehler fuehrt: bei
     * `afterHandover = false` waere nicht unterscheidbar, ob der Zyklus in
     * Phase A fiel oder ob gar kein Fundament lief. Fuers Zurueckdrehen ist
     * das egal, fuer den Export nicht - und eine Groesse, die zwei Lagen in
     * denselben Wert wirft, laedt genau die Fehlableitung ein, die dieses
     * Fundament schon einmal 2,15 U statt 0,75 U rechnen liess.
     */
    enum class Phase {
        /** Keine gueltige Autorisierung - das Fundament laeuft nicht. */
        NONE,

        /** Vor der Uebergabe: Prime finanziert, das Fundament schweigt. */
        PHASE_A,

        /** Ab der Uebergabe EINSCHLIESSLICH. */
        PHASE_B,
    }

    /**
     * Die Phase dieses Zyklus - im KERN, damit sie pruefbar ist.
     *
     * Sie stand zuerst in einer privaten Runner-Funktion; genau der Grenzfall
     * `nowTs == handoverTs` war damit von keinem Test erreichbar.
     *
     * DIE GRENZE IST EINSCHLIESSLICH (`>=`): der Uebergabezeitpunkt ist der
     * ERSTE Moment von Phase B, nicht der letzte von Phase A. Mit `>` faende
     * ein Zyklus, der exakt auf den Anker faellt, in keiner der beiden Phasen
     * statt - seine Abgabe zaehlte dann nirgends, und Phase B hielte sich fuer
     * unversorgt.
     */
    fun phaseOf(auth: Authorization, nowTs: Long, primeWindowStartTs: Long): Phase {
        if (!auth.valid) return Phase.NONE
        val uebergabe = auth.effectiveHandoverTs(primeWindowStartTs)
        // Ein unbestimmbarer Anker ist keine Phase-B-Lage: fail-closed heisst
        // hier "das Fundament schweigt", nicht "es zahlt".
        if (uebergabe <= 0L) return Phase.NONE
        return if (nowTs >= uebergabe) Phase.PHASE_B else Phase.PHASE_A
    }

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
     * DASSELBE CLAMPING WIE PRIME, nicht "dieselbe Zahl aus den Prefs".
     * [PrimeRelease.plan] kappt sein Fenster auf `5..WALL_CEILING_MIN`; wer
     * hier ungekappt rechnete, verliesse sich darauf, dass die
     * Einstellgrenzen (heute 5..45) fuer immer deckungsgleich bleiben. Sie
     * tun es heute - aber der Uebergabeanker darf nicht auf einer
     * Preference-Grenze ruhen, die jemand spaeter weitet. Beide Richtungen
     * waeren echte Loecher: ein zu GROSSES Fenster (ohne Kappung 60, Prime
     * schliesst bei 45) laesst 15 Minuten lang keinen der beiden Kanaele
     * liefern; ein zu KLEINES (2 statt 5) laesst beide gleichzeitig liefern.
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
        // Exakt die Kappung aus PrimeRelease.plan - eine Quelle, keine Kopie.
        val gekappt = primeWindowMin.coerceIn(5, PrimeRelease.WALL_CEILING_MIN)
        val ausPrime = max(markerTs, primeWindowStartTs) + gekappt * 60_000L
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
