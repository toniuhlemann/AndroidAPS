package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER UEBERTRAG DER BELEGTEN PHASE-A-LUECKE (Toni 19.08.).
 *
 * DER GEMESSENE ANLASS. Am 19.08. forderte FUSE 20 x 0,15 U an, die
 * Pumpendatenbank zeigte 2,70 U - zwei Schritte hatte AAPS am Intervalltor
 * verworfen. Der Nicht-Sende-Beweis dreht diese 0,30 U inzwischen aus allen
 * Buechern zurueck; damit sind sie nicht mehr FALSCH gebucht, geflossen sind
 * sie aber trotzdem nicht.
 *
 * WAS DER ZURUECKGEZOGENE E2E DAZU GEMESSEN HATTE - und das war der einzige
 * Teil von ihm, der als STATISCHER Codebefund gueltig blieb: Phase B fuellt
 * die Luecke von sich aus NICHT. Ihr Teilbudget deckelt bei 0,75 U, ganz
 * gleich wieviel das Gesamtbudget noch hergaebe. Genau diese Trennung ist die
 * Zusicherung der Phasenteilung - sie darf nicht pauschal fallen.
 *
 * DIE ANTWORT IST DESHALB EIN GEZIELTER UEBERTRAG, kein aufgeweichter Deckel:
 * Phase B darf genau um die BELEGTE Luecke mehr geben. Ungenutztes Budget,
 * unklare Ausgaenge und fremde Episoden erzeugen nichts - das entscheidet
 * aber nicht dieser Baustein, sondern der Ledger (s. `PhaseACarryTest`). Hier
 * steht nur, was aus einem gegebenen Uebertrag folgt.
 *
 * JEDE ZUSICHERUNG HAT HIER EINE MUTATIONSPROBE: derselbe Aufbau mit
 * Uebertrag 0 muss ein ANDERES Ergebnis liefern. Sonst pruefte der Test die
 * Arithmetik des Rigs statt den Regler - der Fehler, an dem der erste E2E
 * gescheitert ist.
 */
class MealFoundationCarryTest {

    private val t0 = 1_786_000_000_000L
    private val BUDGET = 3.0
    private val A_BIS = 15
    private val B_BIS = 60
    private val STEP = 0.05

    /** 25 % von 3,00 U - die Aufteilung aus Tonis Replay-Kandidat. */
    private val B_BUDGET = 0.75

    private fun plan(
        minuten: Int,
        ausBudgetU: Double,
        seitUebergabeU: Double = 0.0,
        uebertragU: Double = 0.0,
        bBudgetU: Double = B_BUDGET,
    ) = MealFoundation.plan(
        markerTs = t0,
        nowTs = t0 + minuten * 60_000L,
        handoverTs = t0 + A_BIS * 60_000L,
        totalBudgetU = BUDGET,
        phaseBBudgetU = bBudgetU,
        confirmedNotSentPhaseAU = uebertragU,
        phaseBUntilMin = B_BIS,
        deliveredFromBudgetU = ausBudgetU,
        deliveredSinceHandoverU = seitUebergabeU,
        bolusStepU = STEP,
    )

    // ---- Die Erlaubnis ----------------------------------------------------

    /**
     * DER GEMESSENE FALL, in seiner kleinsten Form.
     *
     * Prime wollte 2,25 U, 0,30 U hat das Intervalltor verworfen und der
     * Beweis hat sie zurueckgedreht: gebucht sind 1,95 U, der Uebertrag steht
     * auf 0,30 U. Phase B darf jetzt 1,05 U geben statt 0,75 U.
     */
    @Test
    fun `die belegte Luecke hebt die Erlaubnis um genau sie`() {
        val mit = plan(minuten = A_BIS, ausBudgetU = 1.95, uebertragU = 0.30)
        assertEquals(
            1.05, mit.remainingInWindowU, 1e-9,
            "Teilbudget 0,75 plus die belegten 0,30",
        )

        // MUTATIONSPROBE: derselbe Aufbau ohne Uebertrag deckelt bei 0,75 -
        // obwohl das Gesamtbudget 1,05 U hergaebe. Waere das hier gleich,
        // pruefte der Test oben gar nichts.
        val ohne = plan(minuten = A_BIS, ausBudgetU = 1.95, uebertragU = 0.0)
        assertEquals(
            0.75, ohne.remainingInWindowU, 1e-9,
            "ohne Beleg bleibt das Teilbudget die Grenze - das ist der Befund, " +
                "der diesen Umbau ueberhaupt noetig macht",
        )
    }

    /**
     * DAS GESAMTBUDGET BLEIBT DIE LETZTE GRENZE.
     *
     * Ein Uebertrag kann nicht groesser werden als das, was Phase A ueberhaupt
     * hatte - trotzdem steht der Deckel hier und nicht nur im Ledger. Zwei
     * Grenzen, die dasselbe behaupten, sind keine Doppelung, wenn die eine
     * eine Buchhaltungsregel ist und die andere eine Reglereigenschaft: der
     * Regler darf sich nicht darauf verlassen, dass die Buchhaltung nie irrt.
     */
    @Test
    fun `ein absurder Uebertrag sprengt das Gesamtbudget nicht`() {
        val p = plan(minuten = A_BIS, ausBudgetU = 2.25, uebertragU = 2.0)
        assertEquals(
            0.75, p.remainingInWindowU, 1e-9,
            "2,25 U sind gebucht - mehr als 0,75 U kann es nicht mehr geben",
        )

        // UND DIE ZWEITE GRENZE EINZELN: ohne jede Buchung bindet nicht mehr
        // der Rest des Gesamtbudgets, sondern der Deckel auf der Erlaubnis
        // selbst. 0,75 + 2,50 waere 3,25 - erlaubt sind 3,00.
        val leer = plan(minuten = A_BIS, ausBudgetU = 0.0, uebertragU = 2.5)
        assertEquals(
            BUDGET, leer.remainingInWindowU, 1e-9,
            "die Erlaubnis selbst ist am Gesamtbudget gedeckelt",
        )
    }

    /**
     * DER UEBERTRAG WIRD NICHT AUSGESCHUETTET, SONDERN VERTEILT.
     *
     * Er hebt die Erlaubnis, nicht die Faelligkeit. Ein Zyklus gibt weiterhin
     * hoechstens EINEN Pumpenschritt - alles andere waere die IOB-Spitze,
     * gegen die die Phasenteilung ueberhaupt gebaut ist.
     */
    @Test
    fun `ein Uebertrag erzeugt keinen Nachhol-Burst`() {
        // Mitten im Fenster, nichts geflossen: der Rueckstand ist gross.
        val p = plan(minuten = 40, ausBudgetU = 1.95, uebertragU = 0.30)
        assertTrue(p.backlogU > 3 * STEP, "der Rueckstand MUSS mehrere Schritte betragen: ${p.backlogU}")
        assertEquals(STEP, p.dueU, 1e-9, "trotzdem hoechstens ein Schritt")
        assertEquals(MealFoundation.Binding.ONE_STEP_PER_CYCLE, p.binding)
    }

    /**
     * SOLL UND RATE FOLGEN DERSELBEN ERLAUBNIS.
     *
     * Die Groesse darf nicht an einer Stelle das Teilbudget und an einer
     * anderen die angehobene Erlaubnis meinen. Genau diese zweite Wahrheit
     * hat das Fundament schon einmal 3,75 U aus einer 3-U-Autorisierung sehen
     * lassen - deshalb rechnen [MealFoundation.plan] und
     * [MealFoundation.snapshot] beide ueber dieselbe private Funktion.
     */
    @Test
    fun `die Sollbahn folgt der angehobenen Erlaubnis`() {
        val fensterMin = B_BIS - A_BIS
        // 22 von 45 Minuten - NICHT die Haelfte. Das Fenster ist ungerade
        // lang, und eine gerundete Halbzeit haette hier eine falsche Erwartung
        // erzeugt statt einen Fehler zu finden (in der ersten Fassung genau
        // passiert).
        val nachMin = 22
        val mit = plan(minuten = A_BIS + nachMin, ausBudgetU = 1.95, uebertragU = 0.30)
        assertEquals(1.05 / fensterMin, mit.effectiveRateUPerMin, 1e-9, "die Rate steigt mit")
        assertEquals(
            1.05 * nachMin / fensterMin, mit.plannedTotalU, 1e-9,
            "und das Soll ist der Fortschritt auf der angehobenen Bahn",
        )

        val ohne = plan(minuten = A_BIS + nachMin, ausBudgetU = 1.95, uebertragU = 0.0)
        assertEquals(0.75 / fensterMin, ohne.effectiveRateUPerMin, 1e-9)
        assertTrue(mit.plannedTotalU > ohne.plannedTotalU + 1e-9, "sonst misst der Test nichts")
    }

    /**
     * VOR DEM FENSTER zeigt die Vorschau schon die angehobene Erlaubnis - der
     * Uebertrag entsteht ja waehrend Phase A und ist dort bereits bekannt.
     * Geliefert wird deshalb trotzdem nichts.
     */
    @Test
    fun `vor der Uebergabe schweigt das Fundament auch mit Uebertrag`() {
        val p = plan(minuten = 5, ausBudgetU = 1.20, uebertragU = 0.30)
        assertEquals(0.0, p.dueU, 1e-9)
        assertEquals(MealFoundation.Binding.BEFORE_WINDOW, p.binding)
        assertEquals(1.05, p.remainingInWindowU, 1e-9, "die Vorschau nennt aber schon die Erlaubnis")
    }

    // ---- Der Uebertrag ist eine Mengen-ZEIT-Groesse (Codex 19.08.) --------

    /**
     * DER BEFUND, DEN DIESE VIER TESTS ABSICHERN.
     *
     * `confirmedNotSentPhaseAU` zaehlt HISTORISCH: "war in Phase A gebucht,
     * bewiesen nie gesendet". Holt Prime die Menge danach INNERHALB SEINES
     * EIGENEN FENSTERS nach - und dafuer hat es oft Zeit, bei 75/25 braucht es
     * nur 15 der 20 Fensterminuten -, ist die Luecke geschlossen, der Zaehler
     * steht aber weiter.
     *
     * DER GESAMTDECKEL FAENGT DAS NICHT AUF, und das war mein Denkfehler: er
     * begrenzt die SUMME, nicht die RAMPE. Phase B rechnete ihre Sollbahn
     * weiter aus 1,05 statt 0,75 U und lieferte die tatsaechlich
     * verbleibenden 0,75 U dadurch ein Drittel schneller - das Insulin wandert
     * nach vorn, also genau die IOB-Spitze, gegen die die Phasenteilung gebaut
     * ist. Ein Deckel, der die Menge haelt und die Zeit freigibt, ist kein
     * Schutz.
     *
     * Massgeblich ist deshalb der noch OFFENE Rueckstand:
     *
     *     phaseADelivered = deliveredFromBudget - deliveredSinceHandover
     *     phaseAShortfall = max(0, phaseABudget - phaseADelivered)
     *     effectiveCarry  = min(confirmedNotSentPhaseAU, phaseAShortfall)
     *
     * Alle vier Faelle stehen bewusst am UEBERGABEZEITPUNKT: dort ist
     * `deliveredSinceHandover` noch 0, der Phase-A-Verbrauch also unmittelbar
     * ablesbar und die Erwartung nicht durch Phase-B-Arithmetik verwaschen.
     */
    @Test
    fun `eine nicht nachgeholte Luecke gilt voll`() {
        // 2,25 U wollte Prime, 1,95 U sind angekommen - 0,30 U fehlen.
        val p = plan(minuten = A_BIS, ausBudgetU = 1.95, uebertragU = 0.30)
        assertEquals(1.05, p.remainingInWindowU, 1e-9, "Teilbudget plus die volle offene Luecke")
    }

    /**
     * VOLLSTAENDIG VON PRIME NACHGEHOLT - der Uebertrag verfaellt.
     *
     * Prime hat die verworfenen 0,30 U in den freien Minuten seines eigenen
     * Fensters doch noch geliefert; gebucht sind wieder 2,25 U. Der Zaehler
     * steht unveraendert auf 0,30 - wirken darf er nicht mehr.
     */
    @Test
    fun `eine von Prime nachgeholte Luecke gibt die Erlaubnis zurueck`() {
        val p = plan(minuten = A_BIS, ausBudgetU = 2.25, uebertragU = 0.30)
        assertEquals(
            0.75, p.remainingInWindowU, 1e-9,
            "kein Rueckstand mehr - Phase B rechnet wieder mit ihrem Teilbudget",
        )
        // DIE RAMPE IST DIE EIGENTLICHE ZUSICHERUNG, und das ist gemessen,
        // nicht behauptet: die Mutationsprobe auf den rohen Zaehler laesst die
        // MENGE oben unveraendert bei 0,75 U durchgehen - der Gesamtdeckel
        // (3,00 - 2,25) kappt sie ja ohnehin dort. Nur die Rate faellt auf.
        // Ein Test ohne diese Zeile waere gruen geblieben.
        assertEquals(
            0.75 / (B_BIS - A_BIS), p.effectiveRateUPerMin, 1e-9,
            "die Sollrate MUSS auf das Teilbudget zurueckfallen",
        )
    }

    /** TEILWEISE nachgeholt - es gilt genau der Rest. */
    @Test
    fun `eine halb nachgeholte Luecke gilt nur zur Haelfte`() {
        // 2,10 U gebucht: 0,15 der 0,30 U hat Prime nachgeholt.
        val p = plan(minuten = A_BIS, ausBudgetU = 2.10, uebertragU = 0.30)
        assertEquals(0.90, p.remainingInWindowU, 1e-9, "0,75 + die verbliebenen 0,15")
        // Auch hier traegt die MENGE die Aussage nicht allein: mit dem rohen
        // Zaehler waere die Erlaubnis 1,05 U, gekappt am Restbudget von
        // 0,90 U - dieselbe Zahl. Die Rate unterscheidet die beiden Lagen.
        assertEquals(
            0.90 / (B_BIS - A_BIS), p.effectiveRateUPerMin, 1e-9,
            "die Sollrate folgt dem OFFENEN Rueckstand, nicht dem Zaehler",
        )
    }

    /**
     * UND DIE GEGENRICHTUNG: ungenutztes Budget wird NICHT nachgeholt.
     *
     * Ohne den Zaehler waere die Ableitung allein gefaehrlich - ein
     * Phase-A-Rueckstand entsteht auch, wenn Prime schlicht nichts wollte
     * (`NO_DEMAND`, Guard, kein Bedarf). Das ist eine ENTSCHEIDUNG, kein
     * Verlust, und sie spaeter zu kassieren waere ein Nachholbolus fuer eine
     * Zurueckhaltung.
     *
     * Beide Grenzen sind also noetig, und dieser Test ist die Probe auf die
     * zweite.
     */
    @Test
    fun `ein Rueckstand ohne Beweis holt nichts nach`() {
        // Prime hat nur 1,20 von 2,25 U abgerufen - Rueckstand 1,05 U, aber
        // nichts davon ist als nicht gesendet BEWIESEN.
        val p = plan(minuten = A_BIS, ausBudgetU = 1.20, uebertragU = 0.0)
        assertEquals(0.75, p.remainingInWindowU, 1e-9, "ungenutztes Budget ist kein Uebertrag")
    }

    // ---- Fail-closed ------------------------------------------------------

    /**
     * EIN UNBRAUCHBARER UEBERTRAG SPERRT, statt auf 0 geklemmt zu werden.
     *
     * Er kann nur aus einem Fehler stammen - der Ledger schreibt ihn gedeckelt
     * und der Codec weist beim Lesen alles andere ab. Ihn hier still
     * geradezubiegen hiesse, mit einem kaputten Zustand weiterzurechnen und
     * den Fehler unsichtbar zu machen.
     */
    @Test
    fun `ein unbrauchbarer Uebertrag ist ein Eingabefehler`() {
        for (u in listOf(-0.05, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(
                MealFoundation.Binding.UNUSABLE_INPUT,
                plan(minuten = 30, ausBudgetU = 1.95, uebertragU = u).binding,
                "Uebertrag $u",
            )
        }
    }

    // ---- Der ganze Ablauf --------------------------------------------------

    /**
     * WIRD DIE LUECKE UEBER DAS FENSTER TATSAECHLICH NACHGEHOLT?
     *
     * Die Frage, die der zurueckgezogene E2E beantworten wollte und nicht
     * konnte. Hier laeuft sie auf der Ebene, auf der sie ENTSCHIEDEN wird -
     * der Plan-Bahn, Minute fuer Minute, mit derselben Ein-Schritt-Regel und
     * derselben Buchfuehrung wie im Runner.
     *
     * WAS DIESER TEST NICHT BEHAUPTET, ausdruecklich: dass die Menge auch
     * FLIESST. Zwischen `dueU` und der Pumpe liegen unveraendert alle Gates -
     * Signalqualitaet, gemessenes Tief, iobTH, maxIOB, Transport, Ledger,
     * Publikationsgate. Diese Frage beantwortet erst der echte E2E ueber den
     * Runner-Harnisch; er steht noch aus.
     */
    @Test
    fun `die belegte Luecke wird ueber das Fenster nachgeholt`() {
        fun fahre(uebertragU: Double, ausBudgetStartU: Double): Double {
            var ausBudget = ausBudgetStartU
            var seitUebergabe = 0.0
            for (min in A_BIS..B_BIS) {
                val p = plan(min, ausBudget, seitUebergabe, uebertragU)
                if (p.dueU > 0.0) {
                    seitUebergabe += p.dueU
                    ausBudget += p.dueU
                }
            }
            return seitUebergabe
        }

        // OHNE Verlust: Phase A hat ihre 2,25 U bekommen, Phase B gibt ihr
        // Teilbudget.
        val ohne = fahre(uebertragU = 0.0, ausBudgetStartU = 2.25)
        assertEquals(0.75, ohne, 1e-9, "das unveraenderte Teilbudget")

        // MIT Verlust: 0,30 U sind am Intervalltor gestorben und belegt
        // zurueckgedreht - gebucht sind 1,95 U.
        val mit = fahre(uebertragU = 0.30, ausBudgetStartU = 1.95)
        assertEquals(1.05, mit, 1e-9, "Teilbudget plus die nachgeholte Luecke")

        // DIE VERGLEICHSZEILE, DIE ETWAS FINDET. Der zurueckgezogene E2E hatte
        // hier `mit < ohne + 1e-9` stehen - eine Zeile, die auch bei
        // Gleichheit besteht und deshalb nie etwas gefunden haette.
        assertTrue(mit > ohne + 1e-9, "die Luecke MUSS einen Unterschied machen: $ohne -> $mit")

        // UND DAS GEMEINSAME BUDGET STEHT AM ENDE TROTZDEM: 1,95 + 1,05 = 3,00.
        assertEquals(BUDGET, 1.95 + mit, 1e-9, "nicht mehr als autorisiert wurde")
    }
}
