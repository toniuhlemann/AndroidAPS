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
        /**
         * SOFORTANTEIL von Phase A (iLet-Prinzip, Bauauftrag Toni 24.08.):
         * dieser Anteil des Phase-A-Budgets soll im ersten berechtigten
         * Zyklus nach dem Markerdruck SOFORT angefordert werden statt
         * linear ueber das Prime-Fenster. 0 = heutiges Verhalten
         * (bitgleich). GEPINNT wie die Geschwister: eine Preference-
         * Aenderung wirkt erst beim naechsten frischen Marker. Die
         * Sofort-MENGE ist abgeleitet ([phaseAUpfrontU]), nie gespeichert.
         */
        val phaseAUpfrontShare: Double,
        /** Das beim Armen gueltige Prime-Fenster [min] - gepinnt, damit eine
         *  spaetere Aenderung die laufende Uebergabe nicht verschiebt. */
        val pinnedPrimeWindowMin: Int,
        /** Die beim Armen gueltige Wanduhr-Decke [min]. */
        val pinnedWallCeilingMin: Int,
        /** Das festgeschriebene Fensterende. */
        val endTs: Long,
        /**
         * OB DER MARKERDRUCK INSULIN AUTORISIERT HAT - beim Armen
         * FESTGESCHRIEBEN (Toni 18.08.).
         *
         * WARUM NICHT DEN AKTUELLEN WERT LESEN. Das waere derselbe Fehler wie
         * bei Budget und Anteil, nur folgenreicher: legte jemand den Schalter
         * `MarkerAuthorisesRelease` waehrend einer laufenden Mahlzeit um,
         * bekaeme oder verloere die Episode nachtraeglich das Recht,
         * Modellriegel zu ueberstimmen. Eine Preference-Aenderung ist aber
         * keine Autorisierung fuer eine Mahlzeit, die vor ihr begann.
         *
         * Konfigurierbar heisst auch hier: BEIM NAECHSTEN MARKERDRUCK
         * waehlbar.
         *
         * DIE RUECKNAHME BLEIBT DAVON UNBERUEHRT. Sie ist ein ausdruecklicher
         * Widerruf durch den Menschen und beendet die Autorisierung ganz -
         * nicht ueber dieses Feld, sondern indem die Episode selbst endet.
         * Gepinnt ist die EINSTELLUNG, nicht die Absicht.
         */
        val pinnedMarkerAuthorized: Boolean,
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

        /** Die SOFORT-Menge - abgeleitet, dieselbe Ein-Wahrheits-Disziplin
         *  wie die Teilbudgets. */
        val phaseAUpfrontU: Double get() = phaseABudgetU * phaseAUpfrontShare

        /** Der lineare Phase-A-Planrest - exakt komplementaer zur
         *  Sofort-Menge, kein zweites Produkt. */
        val phaseARemainderU: Double get() = phaseABudgetU - phaseAUpfrontU

        val valid: Boolean
            get() = armedTs > 0L && totalBudgetU.isFinite() && totalBudgetU > 0.0 &&
                phaseAShare.isFinite() && phaseAShare in 0.0..1.0 &&
                phaseAUpfrontShare.isFinite() && phaseAUpfrontShare in 0.0..1.0 &&
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
                armedTs, totalBudgetU, phaseAShare, phaseAUpfrontShare, pinnedPrimeWindowMin,
                pinnedWallCeilingMin, endTs, pinnedMarkerAuthorized, faellig,
            )
        }

        companion object {

            /** Keine laufende Autorisierung - das Fundament ist nicht armiert. */
            fun none() = Authorization(0L, 0.0, 0.0, 0.0, 0, 0, 0L, false, 0L)

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
                phaseAUpfrontShare: Double,
                pinnedPrimeWindowMin: Int,
                pinnedWallCeilingMin: Int,
                endTs: Long,
                pinnedMarkerAuthorized: Boolean,
                latchedHandoverTs: Long,
            ): Authorization {
                val a = Authorization(
                    armedTs, totalBudgetU, phaseAShare, phaseAUpfrontShare, pinnedPrimeWindowMin,
                    pinnedWallCeilingMin, endTs, pinnedMarkerAuthorized, latchedHandoverTs,
                )
                return if (a.valid) a else none()
            }

            internal fun create(
                armedTs: Long,
                totalBudgetU: Double,
                phaseAShare: Double,
                phaseAUpfrontShare: Double,
                pinnedPrimeWindowMin: Int,
                pinnedWallCeilingMin: Int,
                endTs: Long,
                pinnedMarkerAuthorized: Boolean,
            ) = Authorization(
                armedTs, totalBudgetU, phaseAShare, phaseAUpfrontShare, pinnedPrimeWindowMin,
                pinnedWallCeilingMin, endTs, pinnedMarkerAuthorized, 0L,
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
        /**
         * SOFORTANTEIL von Phase A (iLet-Prinzip, Bauauftrag Toni 24.08.):
         * `upfrontU = phaseABudgetU x UpfrontShare` wird im ersten
         * berechtigten Zyklus sofort angefordert, der Rest laeuft weiter
         * linear ueber das Prime-Fenster. 0 = heutiges Verhalten.
         *
         * KEIN DEFAULT, aus demselben Grund wie [markerAuthorized]: jede
         * Aufrufstelle muss sich erklaeren, sonst vergisst eine das Pinning
         * und liest still den Live-Wert.
         */
        phaseAUpfrontShare: Double,
        primeWindowMin: Int,
        wallCeilingMin: Int,
        phaseBUntilMin: Int,
        /**
         * Ob der Markerdruck Insulin autorisiert (Einstellung
         * `MarkerAuthorisesRelease`). Wird GEPINNT - eine spaetere Aenderung
         * erreicht diese Mahlzeit nicht mehr.
         *
         * KEIN DEFAULT (Toni 19.08.). Mit einem koennte ein Aufrufer das
         * Pinning schlicht vergessen, und der Code uebersetzte trotzdem - die
         * Episode liefe dann dauerhaft ohne Autorisierung, ohne dass es
         * jemandem auffiele. Ein Pflichtparameter zwingt jede Aufrufstelle,
         * sich zu erklaeren.
         */
        markerAuthorized: Boolean,
        /**
         * HAT DIESER PROZESS DEN DRUCK SELBST GESEHEN? (Toni 19.08.)
         *
         * Der Markerzeitpunkt liegt in einer Preference und ueberlebt jeden
         * Neustart. Er allein sagt also NICHT, ob der Druck neu ist oder beim
         * Warmstart nur VORGEFUNDEN wurde. Genau dafuer fuehrt das Plugin
         * `markerPressObservedTs` als bewusst NICHT persistentes Prozessfeld -
         * es ist der Beweis, dass der Druck nach dem letzten Prozessstart lag.
         *
         * WARUM DAS FUER DAS FUNDAMENT ZAEHLT. Ein beim Warmstart
         * vorgefundener Marker duerfte kein RUECKWIRKENDES Fundament
         * erzeugen: dessen Phase A waere laengst vorbei, und Phase B faende
         * ein Budget vor, aus dem in Wirklichkeit schon geliefert wurde -
         * ohne dass dieser Prozess davon weiss. Die Menge waere doppelt.
         *
         * Der Runner reicht dieselbe Groesse bereits an
         * `MarkerEpisodeGate.decide` weiter; hier ist sie derselbe Beweis fuer
         * dieselbe Frage.
         */
        pressObservedInThisProcess: Boolean,
        /**
         * HAT DER NUTZER "OHNE VORSCHUSS" GEWAEHLT? (Toni 19.08.)
         *
         * Die Bedienoberflaeche sagt dabei zu: "die Freigabe-Huelle dieser
         * Episode ist 0 - KEIN markerfinanziertes Insulin". Phase B ist per
         * Definition markerfinanziertes Insulin, nur zeitlich verteilt - ein
         * Fundament, das nach abgewaehltem Vorschuss trotzdem liefert, waere
         * ein Seiteneingang durch genau die Tuer, die der Nutzer geschlossen
         * hat.
         *
         * Es steht als eigener Parameter da und nicht als `totalBudgetU = 0`:
         * so ist im Code lesbar, WARUM nichts entsteht, und eine spaetere
         * Aenderung an der Budgetrechnung kann diese Sperre nicht versehentlich
         * aushebeln.
         */
        primeDeclinedByUser: Boolean,
    ): Authorization {
        if (!foundationEnabled || markerTs <= 0L) return Authorization.none()
        // Ein nur VORGEFUNDENER Marker armiert nicht - s. Parameterdoku.
        if (!pressObservedInThisProcess) return Authorization.none()
        // "Ohne Vorschuss" schliesst auch das Fundament aus.
        if (primeDeclinedByUser) return Authorization.none()
        if (!totalBudgetU.isFinite() || totalBudgetU <= 0.0) return Authorization.none()
        if (!phaseAShare.isFinite() || phaseAShare !in 0.0..1.0) return Authorization.none()
        if (!phaseAUpfrontShare.isFinite() || phaseAUpfrontShare !in 0.0..1.0) return Authorization.none()
        if (phaseBUntilMin <= 0 || primeWindowMin < 0 || wallCeilingMin < 0) return Authorization.none()
        return Authorization.create(
            armedTs = markerTs,
            totalBudgetU = totalBudgetU,
            phaseAShare = phaseAShare,
            phaseAUpfrontShare = phaseAUpfrontShare,
            pinnedPrimeWindowMin = primeWindowMin,
            pinnedWallCeilingMin = wallCeilingMin,
            endTs = markerTs + phaseBUntilMin * 60_000L,
            pinnedMarkerAuthorized = markerAuthorized,
        )
    }

    /**
     * DER VERBLEIBENDE SOFORT-BATCH - die eine Wahrheit des
     * Phase-A-Sofortanteils (iLet-Prinzip; Bauauftrag Toni 24.08.,
     * korrigiert nach dem Feldbefund vom 25.08. mittags).
     *
     * KEIN EIGENER EINMAL-ZUSTAND, sondern eine BILANZ auf den bereits
     * persistierten, gate- und beweiskorrigierten Zaehlern:
     *
     *     verbleibend = plannedUpfrontU
     *                   - deliveredPhaseAU
     *                   - deliveredSinceHandoverU
     *                   - postFoundationDeliveredU
     *                   - manuellU
     *                   - transferredToDeferredU
     *                   - lapsedU
     *
     * ALLE Lieferungen nach dem Marker zaehlen (Review 25.08. abends,
     * Punkt 3): sonst koennten Phase-B- oder Korrekturabgaben flieszen
     * und danach trotzdem der nahezu vollstaendige Batch. Der
     * Uebertrag-Posten ist keine Lieferung, sondern ein BUCHWECHSEL - er
     * steht hier, damit eine einmal in den schrittweisen Pfad ueberfuehrte
     * Menge nicht zweimal offen erscheint.
     *
     * WAS HIER FRUEHER STAND UND WARUM ES FALSCH WAR: die Bilanz zog
     * zusaetzlich `deferredPrime.openU` ab, weil der Sicherheitsaufschub
     * den zurueckgehaltenen Sofortanteil dort SAMMELTE. Damit gab es zwei
     * Buecher fuer dieselbe Menge, und sie liefen auseinander - gemessen
     * am 25.08.: Plan 3,20 U, in Phase A geliefert 0,60 U, also hoechstens
     * 2,60 U offen; der Aufschub meldete 3,10 U, weil er nur seine EIGENEN
     * 0,05er-Freigaben abzog, nicht die normalen Phase-A-Lieferungen.
     * Der Aufschub sammelt den Sofortanteil deshalb nicht mehr; der
     * Rueckstand ergibt sich allein aus dieser Bilanz.
     *
     * Daraus folgen die exactly-once-Zusagen ohne neuen Zustandsapparat:
     *  - LIEFERUNG erhoeht `deliveredPhaseAU` (Ledger-Buchung) -> der Rest
     *    faellt sofort, kein zweiter Versand. Ein NEUSTART aendert daran
     *    nichts, der Zaehler ist persistiert.
     *  - TEILKAPPUNG durch iobTH/maxIOB/Transport/Pumpenraster bucht nur
     *    den geflossenen Teil -> der echte Rest bleibt sichtbar offen.
     *  - UNKLARER PUMPENAUSGANG laesst die Buchung stehen (Adapter-
     *    Vertrag) -> nichts wird blind wiederholt.
     *  - Ein sicherer NICHT-SENDE-BEWEIS dreht exakt die bewiesene Menge
     *    zurueck -> der Rest lebt exakt einmal je Beweis wieder auf.
     *
     * Normale SMBs in Phase A zaehlen GEGEN den Sofortanteil (sie stehen in
     * `deliveredPhaseAU`) - das ist die max-statt-Addition-Zusage des
     * Bauauftrags in Bilanzform, und zugleich Punkt 6 des Nachtrags:
     * gewoehnliche Phase-A-Lieferungen verkleinern den Batch sofort.
     *
     * @param manualAfterMarkerU manuelles Insulin seit dem Markerdruck.
     *   `null` = Behandlungssicht unlesbar; dann ist die Bilanz NICHT
     *   bestimmbar und der Batch faellt fail-closed auf 0 (nie raten).
     */
    fun remainingUpfrontU(
        auth: Authorization,
        deliveredPhaseAU: Double,
        manualAfterMarkerU: Double?,
        deliveredSinceHandoverU: Double = 0.0,
        postFoundationDeliveredU: Double = 0.0,
        transferredToDeferredU: Double = 0.0,
        /**
         * Am Phasenuebergang VERFALLENE Menge [U] - der Teil, den die
         * Huelle beim Uebertrag nicht mehr aufnehmen konnte. Weder
         * geliefert noch uebertragen, aber erledigt (Review-P1.2).
         */
        lapsedU: Double = 0.0,
    ): Double? {
        if (!auth.valid) return 0.0
        // UNBESTIMMBAR ist NICHT "gedeckt" (Review 25.08. abends, Punkt 6):
        // eine unlesbare Behandlungssicht darf nicht als COVERED erscheinen.
        // `null` heisst "keine Aussage moeglich"; der Aufrufer liefert dann
        // nichts aus (fail-closed) und benennt den Zustand als solchen.
        if (manualAfterMarkerU == null || !manualAfterMarkerU.isFinite() ||
            manualAfterMarkerU < 0.0
        ) return null
        val abzuege = listOf(
            deliveredPhaseAU, manualAfterMarkerU,
            deliveredSinceHandoverU, postFoundationDeliveredU,
            transferredToDeferredU, lapsedU,
        )
        if (abzuege.any { !it.isFinite() || it < 0.0 }) return null
        return max(0.0, auth.phaseAUpfrontU - abzuege.sum())
    }

    /**
     * DER PHASE-A-SOFORT-LIFT (iLet-Prinzip, Bauauftrag Toni 24.08.).
     *
     * NUR in Phase A - danach regeln Uebergabe, Uebertrag und Aufschub. Der
     * Betrag kommt aus [remainingUpfrontU] (Bilanz statt Einmal-Zustand),
     * das Restbudget ist die Phase-A-Huelle minus Geliefertem (Verteidigung
     * in der Tiefe - der Betrag ist per Bilanz schon kleiner), und
     * Guard/Tail folgen der BESTEHENDEN MarkerAuthorization-Politik ueber
     * die GEPINNTE Autorisierung. Die maxSMB-Ausnahme traegt die Quelle
     * [AuthorizedLift.Source.MEAL_UPFRONT] in [AuthorizedLift.lift];
     * iobTH, maxIOB, Transporthaftung und Pumpenraster bleiben hart.
     *
     * ALS GANZER BATCH, nie zerteilt (Nachtrag Toni 25.08. mittags): der
     * ganze verbleibende Betrag wird in EINEM Zug angefordert. Kappt eine
     * Mengengrenze, flieszt der erlaubte Teil und der echte Rest bleibt
     * ueber die Bilanz offen - aber es gibt keine absichtliche Zerlegung
     * in Pumpenschritte mehr.
     */
    @Suppress("LongParameterList")
    fun liftUpfront(
        base: FuseController.Decision,
        auth: Authorization,
        phase: Phase,
        deliveredPhaseAU: Double,
        manualAfterMarkerU: Double?,
        state: FuseController.State,
        tailHeadroomU: Double? = null,
        transportCommitmentU: Double = 0.0,
        tickEps: Double = 1e-9,
    ): FuseController.Decision {
        if (phase != Phase.PHASE_A) return base
        val floorU = remainingUpfrontU(auth, deliveredPhaseAU, manualAfterMarkerU) ?: return base
        if (floorU <= 0.0) return base
        return AuthorizedLift.lift(
            base = base,
            source = AuthorizedLift.Source.MEAL_UPFRONT,
            floorU = floorU,
            remainingU = max(0.0, auth.phaseABudgetU - deliveredPhaseAU),
            state = state,
            authorized = auth.pinnedMarkerAuthorized,
            tailHeadroomU = tailHeadroomU,
            extraCapU = null,
            transportCommitmentU = transportCommitmentU,
            tickEps = tickEps,
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
        /**
         * DER RUECKSTAND: Soll minus bereits Geflossenes, gekappt am noch
         * Offenen. NEGATIV heisst Vorsprung - es ist mehr geflossen als zu
         * diesem Zeitpunkt planmaessig waere, typisch weil eine gewoehnliche
         * Korrektur die Mindestversorgung schon uebererfuellt hat.
         *
         * ER STEHT HIER, WEIL [dueU] IHN NICHT ZEIGT. `dueU` ist gerastert
         * (ein Pumpenschritt je Zyklus) und gedeckelt; aus ihm laesst sich
         * nicht ablesen, ob das Fundament knapp daneben oder weit hinterher
         * liegt. Fuers Replay ist genau das die interessante Groesse: eine
         * Aufteilung, die dauerhaft 0,04 U Rueckstand traegt, verhaelt sich
         * voellig anders als eine, die 0,40 U aufholt - `dueU` ist in beiden
         * Faellen 0 bzw. ein Schritt.
         */
        val backlogU: Double = 0.0,
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
     * @param confirmedNotSentPhaseAU die BEWIESENE Phase-A-Luecke [U] - s. den
     *   Blockkommentar unten. 0 heisst "keine belegte Luecke" und ergibt exakt
     *   das Verhalten vor diesem Umbau.
     * @param bolusStepU die Rasterung der Pumpe.
     */
    fun plan(
        markerTs: Long,
        nowTs: Long,
        handoverTs: Long,
        totalBudgetU: Double,
        phaseBBudgetU: Double,
        /**
         * DIE BELEGTE PHASE-A-LUECKE, die Phase B nachholen darf (Toni 19.08.).
         *
         * DER GEMESSENE ANLASS. Am 19.08. forderte FUSE 20 x 0,15 U an, die
         * Pumpendatenbank zeigte 2,70 U: zwei Schritte hat AAPS am Intervalltor
         * verworfen. Die Buchhaltung dreht das seit dem Nicht-Sende-Beweis
         * korrekt zurueck - aber damit ist die Menge nur nicht mehr FALSCH
         * gebucht, geflossen ist sie trotzdem nicht. Bemerkt Prime den Verlust
         * erst, wenn sein eigenes Fenster zu ist, faellt sie ersatzlos aus.
         *
         * WARUM DAS NICHT SCHON DAS OFFENE GESAMTBUDGET LEISTET. Die zweite
         * Grenze in [plan] laesst nach dem Zurueckdrehen zwar wieder Luft
         * (`total - deliveredFromBudget`), die ERSTE aber deckelt Phase B auf
         * ihr Teilbudget - gemessen im E2E: 0,75 U mit und ohne Verwerfen. Die
         * getrennten Teilbudgets sind genau die Zusicherung, die sie geben
         * sollen; ohne diesen Betrag hier bliebe die Luecke also bestehen.
         *
         * WAS ER NICHT IST: kein Nachholen von UNGENUTZTEM Budget. Er waechst
         * ausschliesslich um Mengen, fuer die ein Nicht-Sende-BEWEIS vorliegt
         * und die in Phase A gebucht waren - ein `NO_DEMAND`, ein unklarer
         * Pumpenausgang oder eine schlicht nicht abgerufene Huelle erzeugen
         * nichts. Das Gesamtbudget bleibt die letzte Grenze: die Summe kann
         * [totalBudgetU] nicht ueberschreiten.
         *
         * UND ER WIRD NICHT SOFORT AUSGESCHUETTET, sondern hebt die
         * Phase-B-ERLAUBNIS. Die Menge laeuft damit auf derselben linearen
         * Bahn und mit derselben Ein-Schritt-Regel nach wie das uebrige
         * Fundament - ein Nachhol-Burst waere genau die IOB-Spitze, gegen die
         * die Phasenteilung gebaut ist.
         *
         * ER WIRKT NUR, SOWEIT DIE LUECKE NOCH OFFEN IST (Codex 19.08.).
         * Dieser Wert ist der HISTORISCHE Zaehler; wieviel davon jetzt noch
         * gilt, rechnet [effectiveCarryU] aus dem tatsaechlichen
         * Phase-A-Rueckstand. Holt Prime die Menge in seinem eigenen Fenster
         * nach, faellt die Erlaubnis auf das Teilbudget zurueck - sonst
         * lieferte Phase B ihre unveraenderten 0,75 U auf einer 1,05-U-Rampe,
         * also ein Drittel zu schnell.
         */
        confirmedNotSentPhaseAU: Double,
        descentDeferredPhaseAU: Double,
        descentCarryEligibility: DescentDeferredCarry.Eligibility,
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
        // DER UEBERTRAG WIRD GEPRUEFT, NICHT GERADEGEBOGEN. Ein negativer oder
        // unendlicher Wert kann nur aus einem Fehler stammen; ihn auf 0 zu
        // klemmen hiesse, mit einem kaputten Zustand weiterzurechnen.
        if (!confirmedNotSentPhaseAU.isFinite() || confirmedNotSentPhaseAU < 0.0) return unusable()
        if (!descentDeferredPhaseAU.isFinite() || descentDeferredPhaseAU < 0.0) return unusable()
        if (descentDeferredPhaseAU > totalBudgetU + 1e-9) return unusable()
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
        // MEHR SEIT DER UEBERGABE ALS INSGESAMT IST EIN WIDERSPRUCH
        // (Codex 19.08.). Der Teil kann nicht groesser sein als das Ganze.
        //
        // ER IST JETZT AUCH EINER. Solange der Runner `evidenceCommittedU` als
        // Gesamtmenge lieferte, war die Ungleichung im NORMALBETRIEB verletzt -
        // die beiden Zaehler haben verschiedene Lebensdauern, und beim zweiten
        // Markerdruck im 360-Minuten-Deckel waechst nur der eine. Ein Riegel
        // haette dort eine gesunde Mahlzeit stillgelegt.
        //
        // Seit die Gesamtmenge aus den EIGENEN Zaehlern des Fundaments kommt
        // (`deliveredPhaseAU + deliveredSinceHandoverU`), kann er im Betrieb
        // nicht mehr ausloesen - und genau deshalb darf er hier stehen: er
        // faengt kuenftige Fehlverdrahtungen, statt heutige Zustaende zu
        // verbieten. Fail-closed, weil aus der Differenz sonst ein zu grosser
        // Phase-A-Rueckstand und damit MEHR Freigabe entstuende.
        if (deliveredSinceHandoverU > deliveredFromBudgetU + 1e-9) return unusable()
        if (!bolusStepU.isFinite() || bolusStepU <= 0.0) return unusable()


        val fensterMin = ((fensterEndeTs - handoverTs) / 60_000L).toInt()
        // DIE ERLAUBNIS VON PHASE B - Teilbudget PLUS belegte Phase-A-Luecke,
        // gedeckelt am gemeinsamen Gesamtbudget.
        //
        // AB HIER GIBT ES NUR NOCH DIESE GROESSE. `phaseBBudgetU` einzeln
        // weiterzuverwenden waere die zweite Wahrheit, an der dieser Baustein
        // schon zweimal gescheitert ist: Soll, Rate, offener Rest und
        // Rueckstand muessen alle dieselbe Obergrenze meinen, sonst holt eine
        // Zeile die Luecke nach, waehrend eine andere sie noch deckelt.
        //
        // Der Deckel ist NICHT bloss Vorsicht: der Uebertrag stammt aus
        // `deliveredFromBudgetU`, das beim Zurueckdrehen mitgesunken ist. Ohne
        // ihn koennte eine Luecke, die groesser ist als das Phase-A-Budget,
        // eine Erlaubnis oberhalb des Autorisierten erzeugen.
        val erlaubnisU = allowanceU(
            totalBudgetU, phaseBBudgetU, confirmedNotSentPhaseAU,
            descentDeferredPhaseAU, descentCarryEligibility,
            deliveredFromBudgetU, deliveredSinceHandoverU,
        )
        val rateUProMin = if (fensterMin > 0) erlaubnisU / fensterMin else 0.0

        // VOR DEM FENSTER: Phase A ist zustaendig, nicht das Fundament.
        if (nowTs < handoverTs)
            return Plan(0.0, 0.0, erlaubnisU, Binding.BEFORE_WINDOW, fensterMin, rateUProMin, backlogU = 0.0)

        // DAS SOLL BIS JETZT - linear ueber das Fenster.
        //
        // Linear und nicht kurvig: eine Form, die niemand messen kann, ist
        // eine Annahme mehr, die spaeter als Erklaerung fuer alles herhaelt.
        // Die Verteilung ist ohnehin eine Replay-Hypothese; sie soll einfach
        // genug sein, um sie zu widerlegen.
        val fortschritt = min(1.0, (nowTs - handoverTs).toDouble() / (fensterEndeTs - handoverTs))
        val sollU = erlaubnisU * fortschritt

        // ZWEI GRENZEN, DIE BEIDE GELTEN:
        //   1. Phase B liefert nie mehr als ihre Erlaubnis,
        //   2. Phase A und B zusammen nie mehr als das gemeinsame Budget.
        //
        // Die zweite bindet, wenn Phase A mehr genommen hat als vorgesehen;
        // die erste, wenn sie weniger genommen hat. Nur eine von beiden zu
        // pruefen laesst je eine Luecke offen - die zweite hat der Test als
        // 2,15 U statt 0,75 U gezeigt.
        //
        // UND SIE BLEIBEN BEIDE STEHEN, auch mit Uebertrag: die erste ist
        // dann keine reine Teilbudgetgrenze mehr, sondern die um die BELEGTE
        // Luecke angehobene - die zweite deckelt weiter am Gesamtbudget und
        // ist damit die letzte Grenze, genau wie ohne Uebertrag.
        val ausPhaseBGeflossen = deliveredSinceHandoverU
        val offenImFenster = max(
            0.0,
            min(
                erlaubnisU - ausPhaseBGeflossen,
                totalBudgetU - deliveredFromBudgetU,
            ),
        )

        // NACH DEM FENSTER VERFAELLT DER REST (Tonis Auflage). Kein Nachliefern
        // Stunden spaeter - was dann noch offen ist, war offenbar nicht noetig.
        if (nowTs > fensterEndeTs)
            return Plan(
                0.0, erlaubnisU, offenImFenster, Binding.AFTER_WINDOW, fensterMin, rateUProMin,
                // Nach dem Fenster ist der Rueckstand das, was NIE geflossen
                // ist - die Groesse, die im Replay zeigt, ob eine Aufteilung
                // ihr Teilbudget ueberhaupt abrufen konnte.
                backlogU = max(0.0, erlaubnisU - ausPhaseBGeflossen),
            )

        // DAS GEMEINSAME BUDGET IST DIE HARTE GRENZE.
        if (deliveredFromBudgetU >= totalBudgetU - 1e-9)
            return Plan(0.0, sollU, 0.0, Binding.BUDGET_EXHAUSTED, fensterMin, rateUProMin, backlogU = 0.0)

        val rueckstandU = min(sollU - ausPhaseBGeflossen, offenImFenster)
        if (rueckstandU < bolusStepU - 1e-9) return Plan(
            0.0, sollU, offenImFenster,
            // ZWEI SEHR VERSCHIEDENE GRUENDE FUER DIESELBE NULL. Ist schon
            // mehr geflossen als das Soll, hat der normale Pfad geliefert;
            // liegt es nur an der Zeit, ist der Plan erfuellt.
            if (ausPhaseBGeflossen >= sollU - 1e-9 && ausPhaseBGeflossen > 0.0)
                Binding.COVERED_BY_DELIVERY else Binding.ON_SCHEDULE,
            fensterMin, rateUProMin, backlogU = rueckstandU,
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
            backlogU = rueckstandU,
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
     * @param confirmedNotSentPhaseAU die belegte Phase-A-Luecke - s. [plan].
     *
     *   SIE IST KEIN TEIL DER AUTORISIERUNG und wird deshalb durchgereicht
     *   statt aus `auth` gelesen. Gepinnt sind die REGELPARAMETER; dieser
     *   Betrag ist eine LAUFENDE Messung derselben Episode, die sich mit jedem
     *   bewiesenen Nicht-Senden aendert. In die Autorisierung gelegt muesste
     *   sie bei jedem Beweis neu geschrieben werden - und ein Objekt, das
     *   "eingefroren" heisst und sich staendig aendert, waere die
     *   Selbsttaeuschung, gegen die das Pinning gebaut ist.
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
        confirmedNotSentPhaseAU: Double,
        descentDeferredPhaseAU: Double,
        descentCarryEligibility: DescentDeferredCarry.Eligibility,
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
            confirmedNotSentPhaseAU = confirmedNotSentPhaseAU,
            descentDeferredPhaseAU = descentDeferredPhaseAU,
            descentCarryEligibility = descentCarryEligibility,
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
     * WAS PHASE B GEBEN DARF - die EINE Stelle, an der der Uebertrag wirkt.
     *
     * [plan] und [snapshot] rufen beide hierher. Zwei getrennte Rechnungen
     * waeren genau die zweite Wahrheit, die dieses Fundament bei den
     * Teilbudgets schon einmal 3,75 U aus einer 3-U-Autorisierung sehen liess:
     * der Export zeigte dann eine andere Erlaubnis, als der Regler benutzt.
     *
     * EIN UNBRAUCHBARER UEBERTRAG ERGIBT HIER SCHLICHT KEINEN - `plan` weist
     * ihn ohnehin vorher als [Binding.UNUSABLE_INPUT] ab, `snapshot` darf aber
     * kein NaN in den Export tragen.
     */
    private fun allowanceU(
        totalBudgetU: Double,
        phaseBBudgetU: Double,
        confirmedNotSentPhaseAU: Double,
        descentDeferredPhaseAU: Double,
        descentCarryEligibility: DescentDeferredCarry.Eligibility,
        deliveredFromBudgetU: Double,
        deliveredSinceHandoverU: Double,
    ): Double = min(
        phaseBBudgetU + carryAllocation(
            totalBudgetU, phaseBBudgetU, confirmedNotSentPhaseAU,
            descentDeferredPhaseAU, descentCarryEligibility,
            deliveredFromBudgetU, deliveredSinceHandoverU,
        ).totalEffectiveCarryU,
        totalBudgetU,
    )

    private fun carryAllocation(
        totalBudgetU: Double,
        phaseBBudgetU: Double,
        confirmedNotSentPhaseAU: Double,
        descentDeferredPhaseAU: Double,
        descentCarryEligibility: DescentDeferredCarry.Eligibility,
        deliveredFromBudgetU: Double,
        deliveredSinceHandoverU: Double,
    ): DescentDeferredCarry.Allocation {
        if (!deliveredFromBudgetU.isFinite() || !deliveredSinceHandoverU.isFinite())
            return DescentDeferredCarry.Allocation.none()
        if (deliveredSinceHandoverU > deliveredFromBudgetU + 1e-9)
            return DescentDeferredCarry.Allocation.none()
        val phaseADeliveredU = max(0.0, deliveredFromBudgetU - deliveredSinceHandoverU)
        return DescentDeferredCarry.allocate(
            phaseABudgetU = max(0.0, totalBudgetU - phaseBBudgetU),
            deliveredPhaseAU = phaseADeliveredU,
            confirmedNotSentPhaseAU = confirmedNotSentPhaseAU,
            descentDeferredPhaseAU = descentDeferredPhaseAU,
            descentEligibility = descentCarryEligibility,
        )
    }

    /**
     * DER NOCH OFFENE UEBERTRAG - eine Mengen-ZEIT-Groesse, kein Konto
     * (Codex-Befund 19.08.).
     *
     * DER FEHLER, DEN DAS BEHEBT. `confirmedNotSentPhaseAU` zaehlt HISTORISCH:
     * "diese Menge war in Phase A gebucht und ist bewiesen nie gesendet
     * worden". Holt Prime sie danach INNERHALB SEINES EIGENEN FENSTERS nach -
     * und dafuer hat es oft Zeit, bei 75/25 braucht es nur 15 der 20
     * Fensterminuten -, dann ist die Luecke geschlossen, der Zaehler steht
     * aber weiter.
     *
     * Der Gesamtdeckel faengt das NICHT auf, und das war meine falsche
     * Annahme: er begrenzt die SUMME, nicht die RAMPE. Phase B rechnete ihre
     * Sollbahn weiter aus 1,05 statt 0,75 U und lieferte die tatsaechlich
     * verbleibenden 0,75 U dadurch um ein Drittel schneller. Das Insulin
     * wandert nach vorn - also genau die IOB-Spitze, gegen die die 75/25-
     * Teilung gebaut ist. Ein Deckel, der die Menge haelt und die Zeit
     * freigibt, ist kein Schutz.
     *
     * DIE ABLEITUNG, in den Groessen, die der Zyklus ohnehin fuehrt:
     *
     *     phaseADelivered = deliveredFromBudget - deliveredSinceHandover
     *     phaseAShortfall = max(0, phaseABudget - phaseADelivered)
     *     effectiveCarry  = min(confirmedNotSentPhaseAU, phaseAShortfall)
     *
     * Der Uebertrag ist damit eine OBERGRENZE fuer das, was Phase B nachholen
     * darf, und der tatsaechliche Rueckstand von Phase A die andere. Es gilt
     * die kleinere - beide Richtungen sind noetig: ohne den Zaehler wuerde
     * jede nicht abgerufene Huelle nachgeholt (ungenutztes Budget ist eine
     * ENTSCHEIDUNG, kein Verlust), ohne den Rueckstand bliebe eine laengst
     * geschlossene Luecke ewig offen.
     *
     * `phaseABudget` wird aus `total - phaseB` abgeleitet und nicht zusaetzlich
     * hereingereicht: die beiden sind in [Authorization] exakt komplementaer
     * definiert, ein dritter Parameter waere eine zweite Wahrheit.
     */
    private fun effectiveCarryU(
        totalBudgetU: Double,
        phaseBBudgetU: Double,
        confirmedNotSentPhaseAU: Double,
        deliveredFromBudgetU: Double,
        deliveredSinceHandoverU: Double,
    ): Double {
        return carryAllocation(
            totalBudgetU, phaseBBudgetU, confirmedNotSentPhaseAU,
            descentDeferredPhaseAU = 0.0,
            descentCarryEligibility = DescentDeferredCarry.Eligibility.NO_DEFERRED,
            deliveredFromBudgetU = deliveredFromBudgetU,
            deliveredSinceHandoverU = deliveredSinceHandoverU,
        ).effectiveTransportCarryU
    }

    /**
     * WIE DAS FUNDAMENT MIT DEM NORMALEN VORSCHLAG ZUSAMMENGEHT
     * (Toni 18.08., P0 aus dem Replay).
     *
     * DER FEHLER, DEN DIESE FUNKTION VERHINDERT. Das erste Replay hat
     * `normal + dueU` gerechnet - also genau den ADDITIVEN BOLUS, den die
     * Mindestversorgungs-Semantik verbietet. Verlangen im selben Zyklus der
     * normale Pfad 0,05 U und das Fundament 0,05 U, ist das Ergebnis NICHT
     * 0,10 U.
     *
     * Der Vertrag ist ein BODEN, kein Aufschlag:
     *
     *     finaler Kandidat = max(normaler Kandidat, Fundament-Soll)
     *     Fundamentbeitrag = max(0, Fundament-Soll - normaler Kandidat)
     *
     * Danach laufen die gemeinsamen Gates GENAU EINMAL ueber den finalen
     * Kandidaten, und gebucht wird nur, was tatsaechlich publiziert wurde.
     * Zwei getrennte Mengen durch zwei Gate-Laeufe zu schicken waere ein
     * zweiter Kanal - und der duerfte doppelt so viel wie erlaubt.
     *
     * SIE STEHT IM KERN UND NICHT IM REPLAY, obwohl heute nur das Replay sie
     * ruft. Eine Semantik, die nur im Test existiert, wird bei der
     * Verdrahtung neu erfunden - und dann womoeglich anders.
     */
    data class Contribution(
        /** Was in die gemeinsamen Gates geht - EINE Menge, nicht zwei. */
        val finalCandidateU: Double,
        /**
         * DER ANGEFORDERTE Fundamentbeitrag - VOR den Gates (Toni 18.08.).
         *
         * DER NAME IST DIE ZUSICHERUNG. Er hiess vorher `foundationU`, und
         * das war eine Einladung zum Fehler: die Zahl sieht aus wie "das hat
         * das Fundament geliefert", ist aber nur "das hat es verlangt".
         * Zwischen ihr und der Pumpe liegen Guard, Schwanz, iobTH, maxIOB,
         * Transport, Ledger und Pumpengate.
         *
         * NIEMALS DIREKT BUCHEN. Die Bilanz darf ausschliesslich die nach dem
         * PUBLIKATIONSGATE tatsaechlich veroeffentlichte Gesamtmenge
         * verwenden - alles andere behauptet Insulin, das nie geflossen ist,
         * und der Zaehler waere in genau der Richtung falsch, die spaeter zu
         * WENIG nachliefert.
         */
        val requestedFoundationU: Double,
        /**
         * War der normale Kandidat ueberhaupt berechenbar?
         *
         * NICHT BERECHENBAR IST NICHT NULL (Toni 18.08., P0). Die erste
         * Fassung deutete ein `NaN` still zu 0 um - und weil das Fundament
         * gegen 0 rechnet, ERZEUGTE ein kaputter Kandidat dadurch eine
         * positive Dosis. Genau die falsche Richtung: aus "ich weiss es
         * nicht" wurde "gib etwas".
         *
         * Die 0 bleibt der sauberen Aussage vorbehalten, die sie schon
         * traegt: `NO_DEMAND`, also kein Bedarf.
         */
        val usable: Boolean,
    )

    fun contribute(normalCandidateU: Double, foundationDueU: Double): Contribution {
        // FAIL-CLOSED BEI UNBERECHENBAREM KANDIDATEN. Ist der normale
        // Vorschlag NaN oder negativ, weiss dieser Zyklus nicht, wo er steht -
        // dann darf das Fundament NICHTS beisteuern. Die fruehere Fassung
        // setzte ihn auf 0 und liess das Fundament dagegen rechnen; aus einem
        // kaputten Eingang wurde so eine positive Dosis.
        if (!normalCandidateU.isFinite() || normalCandidateU < 0.0)
            return Contribution(finalCandidateU = 0.0, requestedFoundationU = 0.0, usable = false)
        // Ein unbrauchbares SOLL laesst den normalen Vorschlag dagegen
        // unangetastet - hier ist die Fehlerrichtung umgekehrt: das Fundament
        // schweigt, der bestehende Pfad laeuft weiter wie ohne Fundament.
        val soll = if (foundationDueU.isFinite() && foundationDueU > 0.0) foundationDueU else 0.0
        val final = max(normalCandidateU, soll)
        return Contribution(
            finalCandidateU = final,
            requestedFoundationU = max(0.0, final - normalCandidateU),
            usable = true,
        )
    }

    /**
     * DER PHASE-B-LIFT (Toni 18.08.).
     *
     * Er hebt die Basisentscheidung auf das Fundament-Soll an - hoechstens.
     * Die Mengenlogik dahinter ist DIESELBE wie bei Prime und steht in
     * [AuthorizedLift]; hier stehen nur die drei Groessen, die Phase B
     * ausmachen:
     *
     *   das SOLL              [Snapshot.dueU]
     *   das RESTBUDGET        [Snapshot.remainingInWindowU]
     *   das ZEITFENSTER       implizit - ausserhalb ist `dueU` bereits 0
     *
     * KEINE ONSET-HUELLE, und das ist ausdruecklich (Tonis Auflage): sie
     * wuerde Phase B mitdeckeln, obwohl normale Onset- und Evidenzabgaben
     * bereits ueber die max-Semantik in [contribute] und ueber die
     * tatsaechlich gelieferte Gesamtmenge auf das Soll angerechnet sind. Sie
     * ein zweites Mal als Kappe zu fuehren, zoege denselben Betrag zweimal
     * ab.
     *
     * DIE PHASE WIRD GEPRUEFT, obwohl `dueU` ausserhalb ohnehin 0 waere. Ein
     * Riegel, der sich auf eine andere Rechnung verlaesst, ist einer, der
     * beim naechsten Umbau still verschwindet.
     */
    @Suppress("LongParameterList")
    fun lift(
        base: FuseController.Decision,
        snapshot: Snapshot,
        state: FuseController.State,
        tailHeadroomU: Double? = null,
        transportCommitmentU: Double = 0.0,
        tickEps: Double = 1e-9,
    ): FuseController.Decision {
        if (!snapshot.armed) return base
        if (snapshot.phase != Phase.PHASE_B) return base
        return AuthorizedLift.lift(
            base = base,
            source = AuthorizedLift.Source.FOUNDATION,
            floorU = snapshot.dueU,
            remainingU = snapshot.remainingInWindowU,
            state = state,
            // DIE GEPINNTE Autorisierung, NICHT der aktuelle Preference-Wert
            // (Toni 18.08.): eine Aenderung waehrend der laufenden Mahlzeit
            // darf ihr das Recht, Modellriegel zu ueberstimmen, weder geben
            // noch nehmen.
            authorized = snapshot.markerAuthorized,
            tailHeadroomU = tailHeadroomU,
            // KEINE Onset-Huelle - s. Blockkommentar.
            extraCapU = null,
            transportCommitmentU = transportCommitmentU,
            tickEps = tickEps,
        )
    }

    /**
     * DIE VOLLSTAENDIGE SICHT AUF DAS FUNDAMENT ZU EINEM ZEITPUNKT
     * (Punkt 12, Toni 18.08.).
     *
     * WARUM EINE STRUKTUR UND NICHT ZWANZIG EXPORTZEILEN. Der Export im
     * Plugin und das Offline-Replay muessen DASSELBE sehen - sonst wertet
     * das Replay etwas anderes aus, als spaeter im Feld exportiert wird, und
     * jede Schlussfolgerung daraus haengt an einer Annahme statt an einer
     * Messung. Beide rufen deshalb [snapshot]; das Plugin serialisiert nur
     * noch, was hier drinsteht.
     *
     * SIE RECHNET NICHTS ENTSCHEIDENDES SELBST: Phase kommt aus [phaseOf],
     * Mengen aus [planFrom]. Eine eigene Rechnung waere eine zweite Wahrheit
     * - genau das, was dieser Baustein an mehreren Stellen schon einmal
     * teuer bezahlt hat.
     */
    data class Snapshot(
        /** Laeuft ueberhaupt eine Autorisierung? Ist das false, sind alle
         *  Mengen 0 und die Phase [Phase.NONE] - das ist der heutige
         *  Normalzustand, solange das Fundament nicht armiert wird. */
        val armed: Boolean,
        val armedTs: Long,
        /**
         * Die beim Armen gepinnte Marker-Autorisierung. Sie steht im
         * Snapshot, damit der Lift sie NICHT aus einer aktuellen Preference
         * lesen muss - s. [Authorization.pinnedMarkerAuthorized].
         */
        val markerAuthorized: Boolean,
        val totalBudgetU: Double,
        val phaseABudgetU: Double,
        val phaseBBudgetU: Double,
        /** Der gepinnte Sofortanteil (iLet-Prinzip) samt abgeleiteter
         *  Sofort-Menge und linearem Planrest - fuer Export und Replay,
         *  dieselben Getter wie im Regler, keine zweite Rechnung. */
        val phaseAUpfrontShare: Double,
        val phaseAUpfrontPlannedU: Double,
        val phaseARemainderU: Double,
        /** Der AKTUELL geltende Uebergang - folgt vor dem Latch noch der
         *  Prime-Laufzeit. */
        val effectiveHandoverTs: Long,
        /** Der FESTGESCHRIEBENE Uebergang, 0 = noch nicht gelatcht. Beide
         *  Zeitpunkte stehen im Export, weil ihre DIFFERENZ die Frage
         *  beantwortet, ob eine Clearance den Anker noch verschieben kann. */
        val latchedHandoverTs: Long,
        val endTs: Long,
        val phase: Phase,
        /**
         * WAS IN PHASE A DIESER AUTORISIERUNG FLOSS [U] - der AUTORITATIVE
         * Zaehler aus dem Ledger, nicht die Differenz, mit der [plan]
         * gerechnet hat (Codex 19.08.).
         *
         * DASS BEIDE HIER STEHEN, IST DER ZWECK. Der Regler bekommt
         * `deliveredFromBudget` und zieht `deliveredSinceHandover` ab; stimmt
         * das Ergebnis nicht mit diesem Feld ueberein, hat der Runner die
         * falsche Gesamtmenge gereicht - genau der Fehler, der bis zum
         * 19.08. drinsteckte. Waere nur die Differenz im Trail, liesse sich
         * das nachtraeglich nicht mehr sehen.
         */
        val deliveredPhaseAU: Double,
        val deliveredSinceHandoverU: Double,
        /**
         * DIE BELEGTE PHASE-A-LUECKE dieser Episode [U] - s. [plan].
         *
         * SIE STEHT NEBEN [phaseBBudgetU] IM EXPORT, nicht darin verrechnet.
         * Sonst waere im Replay nicht mehr zu sehen, ob Phase B ihr eigenes
         * Teilbudget lieferte oder eine nachgeholte Luecke - zwei Ablaeufe mit
         * gleicher Summe und voellig verschiedener Bedeutung.
         */
        val confirmedNotSentPhaseAU: Double,
        /**
         * Der davon NOCH OFFENE Teil - gekappt am tatsaechlichen
         * Phase-A-Rueckstand. Holt Prime die Luecke in seinem eigenen Fenster
         * nach, faellt diese Groesse auf 0, waehrend
         * [confirmedNotSentPhaseAU] stehen bleibt.
         */
        val effectiveCarryU: Double,
        /** Wegen des gemessenen Abwaertsriegels in Phase A nicht mehr vor der
         *  Uebergabe erreichbare Menge. Historischer, restartfester Zaehler. */
        val descentDeferredPhaseAU: Double,
        /** Warum der Sicherheitsuebertrag in DIESEM Zyklus wirken darf oder
         *  schweigt. Das Urteil wird exportiert, nicht aus der Menge geraten. */
        val descentCarryEligibility: DescentDeferredCarry.Eligibility,
        /** Vom Sicherheitsuebertrag aktuell noch wirksamer Anteil, nach dem
         *  staerkeren Transportbeweis und dem realen Phase-A-Rueckstand. */
        val effectiveDescentCarryU: Double,
        /** Was Phase B insgesamt geben DARF: Teilbudget plus dem noch offenen
         *  Uebertrag, gedeckelt am Gesamtbudget. Die Groesse, gegen die [plan]
         *  rechnet. */
        val phaseBAllowanceU: Double,
        val plannedTotalU: Double,
        val backlogU: Double,
        val dueU: Double,
        val remainingInWindowU: Double,
        val binding: Binding?,
        val effectiveWindowMin: Int,
        val effectiveRateUPerMin: Double,
    ) {

        companion object {

            /** Kein Fundament - der Zustand ohne Autorisierung. */
            fun none() = Snapshot(
                armed = false, armedTs = 0L, markerAuthorized = false,
                totalBudgetU = 0.0, phaseABudgetU = 0.0,
                phaseBBudgetU = 0.0,
                phaseAUpfrontShare = 0.0, phaseAUpfrontPlannedU = 0.0, phaseARemainderU = 0.0,
                effectiveHandoverTs = 0L, latchedHandoverTs = 0L,
                endTs = 0L, phase = Phase.NONE, deliveredPhaseAU = 0.0, deliveredSinceHandoverU = 0.0,
                confirmedNotSentPhaseAU = 0.0, effectiveCarryU = 0.0,
                descentDeferredPhaseAU = 0.0,
                descentCarryEligibility = DescentDeferredCarry.Eligibility.NO_DEFERRED,
                effectiveDescentCarryU = 0.0, phaseBAllowanceU = 0.0,
                plannedTotalU = 0.0, backlogU = 0.0, dueU = 0.0, remainingInWindowU = 0.0,
                binding = null, effectiveWindowMin = 0, effectiveRateUPerMin = 0.0,
            )
        }
    }

    /**
     * Die Sicht dieses Zyklus - fuer Export UND Replay.
     *
     * @param deliveredFromBudgetU was insgesamt aus dem gemeinsamen Budget
     *   floss (beide Phasen), @param deliveredSinceHandoverU davon der Teil
     *   ab der Uebergabe. Die zweite Groesse ist NICHT aus der ersten
     *   ableitbar - der Versuch hat schon einmal 2,15 U statt 0,75 U
     *   gerechnet, weil er den nicht abgerufenen Rest von Phase A dem
     *   Fundament als Schuld anlastete.
     */
    fun snapshot(
        auth: Authorization,
        nowTs: Long,
        primeWindowStartTs: Long,
        deliveredFromBudgetU: Double,
        deliveredSinceHandoverU: Double,
        /** Der autoritative Ledger-Zaehler - nur fuer den Trail, s.
         *  [Snapshot.deliveredPhaseAU]. Er geht NICHT in die Rechnung ein. */
        deliveredPhaseAU: Double,
        confirmedNotSentPhaseAU: Double,
        descentDeferredPhaseAU: Double,
        descentCarryEligibility: DescentDeferredCarry.Eligibility,
        bolusStepU: Double,
    ): Snapshot {
        if (!auth.valid) return Snapshot.none()
        val plan = planFrom(
            auth, nowTs, primeWindowStartTs, deliveredFromBudgetU, deliveredSinceHandoverU,
            confirmedNotSentPhaseAU, descentDeferredPhaseAU, descentCarryEligibility, bolusStepU,
        )
        val carry = carryAllocation(
            auth.totalBudgetU, auth.phaseBBudgetU, confirmedNotSentPhaseAU,
            descentDeferredPhaseAU, descentCarryEligibility,
            deliveredFromBudgetU, deliveredSinceHandoverU,
        )
        return Snapshot(
            armed = true,
            armedTs = auth.armedTs,
            markerAuthorized = auth.pinnedMarkerAuthorized,
            totalBudgetU = auth.totalBudgetU,
            phaseABudgetU = auth.phaseABudgetU,
            phaseBBudgetU = auth.phaseBBudgetU,
            phaseAUpfrontShare = auth.phaseAUpfrontShare,
            phaseAUpfrontPlannedU = auth.phaseAUpfrontU,
            phaseARemainderU = auth.phaseARemainderU,
            effectiveHandoverTs = auth.effectiveHandoverTs(primeWindowStartTs),
            latchedHandoverTs = auth.latchedHandoverTs,
            endTs = auth.endTs,
            phase = phaseOf(auth, nowTs, primeWindowStartTs),
            deliveredPhaseAU = deliveredPhaseAU,
            deliveredSinceHandoverU = deliveredSinceHandoverU,
            confirmedNotSentPhaseAU = confirmedNotSentPhaseAU,
            // BEIDE Groessen, und der Unterschied ist die ganze Aussage: der
            // ROHE Zaehler sagt, was je bewiesen wurde, der EFFEKTIVE, was
            // davon jetzt noch offen ist. Stehen sie im Trail auseinander, hat
            // Prime seine Luecke selbst geschlossen - ohne beide waere im
            // Replay nicht zu sehen, ob Phase B nachholte oder Prime.
            effectiveCarryU = effectiveCarryU(
                auth.totalBudgetU, auth.phaseBBudgetU, confirmedNotSentPhaseAU,
                deliveredFromBudgetU, deliveredSinceHandoverU,
            ),
            descentDeferredPhaseAU = descentDeferredPhaseAU,
            descentCarryEligibility = descentCarryEligibility,
            effectiveDescentCarryU = carry.effectiveDescentCarryU,
            // DIESELBE Rechnung wie in [plan], nicht eine zweite: der Deckel am
            // Gesamtbudget gehoert dazu, sonst zeigte der Export eine
            // Erlaubnis, die der Regler gar nicht hat.
            phaseBAllowanceU = allowanceU(
                auth.totalBudgetU, auth.phaseBBudgetU, confirmedNotSentPhaseAU,
                descentDeferredPhaseAU, descentCarryEligibility,
                deliveredFromBudgetU, deliveredSinceHandoverU,
            ),
            plannedTotalU = plan.plannedTotalU,
            backlogU = plan.backlogU,
            dueU = plan.dueU,
            remainingInWindowU = plan.remainingInWindowU,
            binding = plan.binding,
            effectiveWindowMin = plan.effectiveWindowMin,
            effectiveRateUPerMin = plan.effectiveRateUPerMin,
        )
    }

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

        /** Ab der Uebergabe EINSCHLIESSLICH bis zum Fensterende
         *  EINSCHLIESSLICH - die einzige Phase, die den Zaehler bewegt. */
        PHASE_B,

        /**
         * HINTER DEM FENSTERENDE - das Fundament ist fertig (Toni 18.08., P0).
         *
         * OHNE DIESE PHASE BLIEB ES UNBEGRENZT BEI [PHASE_B]. `phaseOf`
         * kannte das Fensterende nicht, also galt jeder Zyklus nach der
         * Uebergabe als Phase B - auch Stunden und Tage spaeter. Zwei Folgen,
         * beide echt:
         *
         *   der EXPORT log: jeder Korrektur-SMB der naechsten Nacht lief in
         *   `deliveredSinceHandoverU` und sah nach Mahlzeitenbezahlung aus;
         *
         *   der PERSISTIERTE BETRAG wuchs ohne Obergrenze weiter, bis er
         *   irgendwann an der Codec-Schranke haengt - und dann ist die
         *   Generation unlesbar, nicht nur ungenau.
         *
         * Diese Phase belastet und entlastet den Zaehler NICHT. Sie ist
         * ausdruecklich nicht [NONE]: die Autorisierung existiert noch (ihr
         * Ende steht ja gerade fest), sie hat nur nichts mehr zu verteilen.
         * Der Unterschied ist fuer den Export das, was er fuer [PHASE_A] auch
         * ist - "lief und war fertig" ist keine Aussage ueber "lief nie".
         */
        AFTER_WINDOW,
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
     *
     * DAS FENSTERENDE BEGRENZT SIE NACH HINTEN (Toni 18.08., P0). Ohne diese
     * Grenze blieb die Phase nach der Uebergabe unbegrenzt `PHASE_B`: der
     * Zaehler sammelte jeden spaeteren Korrektur-SMB ein und wuchs auf einem
     * persistierten Feld ohne Obergrenze weiter. `nowTs == endTs` gehoert
     * noch dazu, `nowTs > endTs` nicht mehr.
     */
    fun phaseOf(auth: Authorization, nowTs: Long, primeWindowStartTs: Long): Phase {
        if (!auth.valid) return Phase.NONE
        val uebergabe = auth.effectiveHandoverTs(primeWindowStartTs)
        // Ein unbestimmbarer Anker ist keine Phase-B-Lage: fail-closed heisst
        // hier "das Fundament schweigt", nicht "es zahlt".
        if (uebergabe <= 0L) return Phase.NONE
        if (nowTs < uebergabe) return Phase.PHASE_A
        // BEIDE Grenzen einschliessend: der Uebergabezeitpunkt ist der erste
        // Moment von Phase B, das Fensterende der letzte. Mit einem strikten
        // Vergleich an einer der beiden Kanten faende der Zyklus, der exakt
        // darauf faellt, in keiner Phase statt - unter Minutentakt ist das je
        // ein Zyklus pro Mahlzeit, kein Randfall.
        if (nowTs > auth.endTs) return Phase.AFTER_WINDOW
        return Phase.PHASE_B
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
