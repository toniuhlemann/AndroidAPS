package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE BAUVERTRAEGE DES MAHLZEITENFUNDAMENTS (Toni 18.08.).
 *
 * Gerechnet wird durchgehend mit seinem Replay-Kandidaten:
 *
 *     Budget 3,00 U | Phase A 75 % bis T+15 | Phase B 25 % bis T+60
 *     Pumpenschritt 0,05 U  ->  15 Schritte in 45 min, etwa einer je 3 min
 *
 * Das sind Replay-Hypothesen, keine Therapiewerte - die Tests halten die
 * MECHANIK fest, nicht die Zahlen.
 */
class MealFoundationTest {

    private val t0 = 1_700_000_000_000L
    private val BUDGET = 3.0
    private val A_SHARE = 0.75
    private val A_BIS = 15
    private val B_BIS = 60
    private val STEP = 0.05

    /** Phase-B-Budget: 25 % von 3,00 U. */
    private val B_BUDGET = BUDGET * (1.0 - A_SHARE)

    private fun plan(
        minuten: Double,
        geflossenU: Double,
        seitUebergabeU: Double = 0.0,
        step: Double = STEP,
    ) = MealFoundation.plan(
        markerTs = t0,
        nowTs = t0 + (minuten * 60_000).toLong(),
        handoverTs = t0 + A_BIS * 60_000L,
        totalBudgetU = BUDGET,
        phaseAShare = A_SHARE,
        phaseBUntilMin = B_BIS,
        deliveredFromBudgetU = geflossenU,
        deliveredSinceHandoverU = seitUebergabeU,
        bolusStepU = step,
    )

    // ---- Das Fenster -----------------------------------------------------

    @Test
    fun `vor der Uebergabe gibt das Fundament nichts frei`() {
        val p = plan(minuten = 10.0, geflossenU = 2.25)
        assertEquals(0.0, p.dueU, 1e-9)
        assertEquals(MealFoundation.Binding.BEFORE_WINDOW, p.binding)
        assertEquals(B_BUDGET, p.remainingInWindowU, 1e-9, "aber das Budget steht bereit")
    }

    /**
     * NACH FENSTERENDE VERFAELLT DER REST - Tonis Auflage.
     *
     * Kein Nachliefern Stunden spaeter: was bis T+60 nicht gebraucht wurde,
     * war offenbar nicht noetig. Ein spaeter Nachschlag traefe eine Absorption,
     * die es nicht mehr gibt.
     */
    @Test
    fun `nach Fensterende verfaellt der Rest`() {
        val p = plan(minuten = 61.0, geflossenU = 2.25)
        assertEquals(0.0, p.dueU, 1e-9)
        assertEquals(MealFoundation.Binding.AFTER_WINDOW, p.binding)
        assertTrue(p.remainingInWindowU > 0.0, "der Verfall ist sichtbar, nicht stillschweigend")
    }

    // ---- Die Verteilung --------------------------------------------------

    /**
     * DAS SOLL WAECHST LINEAR ueber das Fenster.
     *
     * Bei T+30 ist ein Drittel der 45 Minuten vorbei - also ein Drittel von
     * 0,75 U, das sind 0,25 U.
     */
    @Test
    fun `das Soll waechst linear ueber das Fenster`() {
        assertEquals(0.0, plan(15.0, 2.25).plannedTotalU, 1e-9, "bei T+15 noch nichts")
        assertEquals(0.25, plan(30.0, 2.25).plannedTotalU, 1e-9, "bei T+30 ein Drittel")
        assertEquals(B_BUDGET, plan(60.0, 2.25).plannedTotalU, 1e-9, "bei T+60 alles")
    }

    /** Ein Rueckstand von mindestens einem Pumpenschritt wird freigegeben. */
    @Test
    fun `bei Rueckstand kommt ein Pumpenschritt`() {
        // T+18: Soll = 0,75 * 3/45 = 0,05 U - genau ein Schritt.
        val p = plan(minuten = 18.0, geflossenU = 2.25)
        assertEquals(STEP, p.dueU, 1e-9)
    }

    /** Unter einem Schritt wird nichts freigegeben - der Plan ist erfuellt. */
    @Test
    fun `ohne vollen Schritt bleibt es beim Plan`() {
        // T+16: Soll = 0,75 * 1/45 = 0,0167 U - weniger als ein Schritt.
        val p = plan(minuten = 16.0, geflossenU = 2.25)
        assertEquals(0.0, p.dueU, 1e-9)
        assertEquals(MealFoundation.Binding.ON_SCHEDULE, p.binding)
    }

    /**
     * KEIN AUFHOL-BURST - Tonis Auflage, und der Kern der ganzen Bauform.
     *
     * Ein Rueckstand von mehreren Schritten entsteht, wenn Zyklen ausfielen
     * oder Schritte abgelehnt wurden. Ihn in einem Zug nachzuholen waere genau
     * die IOB-Spitze, die das Fundament vermeiden soll - dann waere die
     * Verteilung sinnlos geworden.
     */
    @Test
    fun `ein grosser Rueckstand kommt trotzdem nur als EIN Schritt`() {
        // T+50: Soll = 0,75 * 35/45 = 0,583 U. Geflossen ist nur Phase A -
        // der Rueckstand betraegt also fast zwoelf Schritte.
        val p = plan(minuten = 50.0, geflossenU = 2.25)
        assertEquals(STEP, p.dueU, 1e-9, "genau EIN Schritt, nicht der ganze Rueckstand")
        assertEquals(MealFoundation.Binding.ONE_STEP_PER_CYCLE, p.binding)
        assertTrue(p.plannedTotalU > 0.5, "obwohl das Soll deutlich hoeher liegt: ${p.plannedTotalU}")
    }

    /** Und der Rueckstand LAEUFT NACH: der naechste Zyklus gibt wieder einen
     *  Schritt, solange das Fenster offen ist. */
    @Test
    fun `der Rueckstand laeuft im Fenster nach`() {
        var geflossen = 2.25
        var ausB = 0.0
        var schritte = 0
        // Von T+18 bis T+60 in Minutenschritten - wie im echten Zyklus. BEIDE
        // Zahlen wachsen mit: der Gesamtverbrauch und der von Phase B.
        for (m in 18..60) {
            val p = plan(minuten = m.toDouble(), geflossenU = geflossen, seitUebergabeU = ausB)
            if (p.dueU > 0.0) {
                geflossen += p.dueU
                ausB += p.dueU
                schritte++
            }
        }
        assertEquals(
            B_BUDGET, geflossen - 2.25, 1e-9,
            "ueber das Fenster wird Phase B vollstaendig ausgeliefert",
        )
        assertEquals(15, schritte, "in 15 Schritten von 0,05 U")
    }

    // ---- Das gemeinsame Budget ------------------------------------------

    /**
     * PHASE A UND B ZUSAMMEN NIEMALS UEBER DAS BUDGET - Tonis Auflage und
     * Spezifikation 3.1.
     *
     * Das Fundament eroeffnet keinen zweiten Topf. Hat Phase A mehr verbraucht
     * als vorgesehen, schrumpft Phase B entsprechend - nicht umgekehrt.
     */
    @Test
    fun `ein ausgeschoepftes Budget sperrt das Fundament`() {
        val p = plan(minuten = 40.0, geflossenU = BUDGET)
        assertEquals(0.0, p.dueU, 1e-9)
        assertEquals(MealFoundation.Binding.BUDGET_EXHAUSTED, p.binding)
        assertEquals(0.0, p.remainingInWindowU, 1e-9)
    }

    /**
     * HAT PHASE A MEHR GENOMMEN, BLEIBT FUER B WENIGER.
     *
     * Der Fall ist real: die Huelle darf im Fruehfenster bis zum Deckel
     * freigeben, und wenn sie 2,60 U statt 2,25 U verbraucht hat, sind das
     * 0,35 U weniger fuer das Fundament. Andernfalls waere das Gesamtbudget
     * ueberschritten.
     */
    @Test
    fun `mehr Verbrauch in Phase A verkleinert Phase B`() {
        val p = plan(minuten = 60.0, geflossenU = 2.60)
        assertEquals(
            BUDGET - 2.60, p.remainingInWindowU, 1e-9,
            "nur noch der Rest bis zum gemeinsamen Budget",
        )
    }

    /**
     * DAS OFFENE BUDGET IST DAS VON PHASE B, nicht das des Gesamtbudgets.
     *
     * Bei unverbrauchtem Phase-A-Budget waeren `totalBudget - geflossen` volle
     * 3,00 U - Phase B darf davon aber nur ihren Anteil sehen. Die Begrenzung
     * wirkt zwar auch ueber das Soll, aber `remainingInWindowU` geht in den
     * Export und in die Liveness-Pruefung: eine dort gemeldete Restmenge, die
     * das Fundament nie ausliefern darf, waere eine falsche Aussage ueber die
     * verbleibende Versorgung.
     *
     * Eine Mutationsprobe, die diese Grenze durch die Gesamtgrenze ersetzt,
     * blieb ohne diesen Test gruen.
     */
    @Test
    fun `das offene Budget ist auf den Phase-B-Anteil begrenzt`() {
        val p = plan(minuten = 30.0, geflossenU = 0.0, seitUebergabeU = 0.0)
        assertEquals(
            B_BUDGET, p.remainingInWindowU, 1e-9,
            "0,75 U - nicht die 3,00 U des Gesamtbudgets",
        )
    }

    /** Die Summe kann das Budget in keinem Verlauf ueberschreiten. */
    @Test
    fun `die Summe bleibt in jedem Verlauf unter dem Budget`() {
        for (startA in listOf(0.0, 1.0, 2.25, 2.9, 3.0)) {
            var geflossen = startA
            var ausB = 0.0
            for (m in 15..70) {
                val p = plan(minuten = m.toDouble(), geflossenU = geflossen, seitUebergabeU = ausB)
                geflossen += p.dueU
                ausB += p.dueU
            }
            assertTrue(
                geflossen <= BUDGET + 1e-9,
                "Start $startA endete bei $geflossen - das Budget ist $BUDGET",
            )
        }
    }

    // ---- Fail-closed -----------------------------------------------------

    /**
     * JEDE UNBRAUCHBARE EINGABE ERGIBT NICHTS.
     *
     * Ein Fundament, das auf NaN oder einer unsinnigen Fensterreihenfolge
     * dosiert, waere gefaehrlicher als eines, das schweigt. Kein Default, der
     * "wahrscheinlich passt".
     */
    @Test
    fun `unbrauchbare Eingaben ergeben keinen Vorschlag`() {
        val H = t0 + A_BIS * 60_000L
        fun p(
            marker: Long = t0, jetzt: Long = t0 + 30 * 60_000L, uebergabe: Long = H,
            budget: Double = BUDGET, anteil: Double = A_SHARE, ende: Int = B_BIS,
            geflossen: Double = 0.0, seitUebergabe: Double = 0.0, step: Double = STEP,
        ) = MealFoundation.plan(marker, jetzt, uebergabe, budget, anteil, ende, geflossen, seitUebergabe, step)

        val faelle = listOf(
            "kein Marker" to p(marker = 0L),
            "jetzt vor Marker" to p(jetzt = t0 - 1000L),
            "Uebergabe vor Marker" to p(uebergabe = t0 - 1000L),
            "Budget NaN" to p(budget = Double.NaN),
            "Budget 0" to p(budget = 0.0),
            "Anteil ueber 1" to p(anteil = 1.5),
            "Anteil NaN" to p(anteil = Double.NaN),
            "Schritt 0" to p(step = 0.0),
            "Schritt NaN" to p(step = Double.NaN),
            "geflossen negativ" to p(geflossen = -1.0),
            "seit Uebergabe negativ" to p(seitUebergabe = -1.0),
        )
        for ((name, p) in faelle) {
            assertEquals(0.0, p.dueU, 1e-9, name)
            assertEquals(MealFoundation.Binding.UNUSABLE_INPUT, p.binding, name)
        }
    }

    /**
     * DER HEUTIGE STAND BLEIBT ERREICHBAR: Anteil 1,0 heisst kein Fundament.
     *
     * Das ist der Default und der Vergleichsfall im Replay (100/0). Bliebe
     * hier ein Rest uebrig, waere das Einschalten des Schalters allein schon
     * eine Verhaltensaenderung.
     */
    @Test
    fun `bei Anteil eins gibt es keine Phase B`() {
        for (m in listOf(0.0, 15.0, 30.0, 60.0, 90.0)) {
            val p = MealFoundation.plan(
                t0, t0 + (m * 60_000).toLong(), t0 + A_BIS * 60_000L, BUDGET,
                phaseAShare = 1.0, phaseBUntilMin = B_BIS,
                deliveredFromBudgetU = 3.0, deliveredSinceHandoverU = 0.0, bolusStepU = STEP,
            )
            assertEquals(0.0, p.dueU, 1e-9, "bei T+$m")
        }
    }

    /** Und ein Verlauf ohne jeden Verbrauch liefert am Ende genau das
     *  Phase-B-Budget - nicht mehr, nicht weniger. */
    @Test
    fun `ohne Phase-A-Verbrauch bleibt Phase B trotzdem bei seinem Anteil`() {
        var geflossen = 0.0
        var ausB = 0.0
        for (m in 15..60) {
            val d = plan(m.toDouble(), geflossen, ausB).dueU
            geflossen += d
            ausB += d
        }
        assertEquals(
            B_BUDGET, geflossen, 1e-9,
            "das Fundament nimmt sich NICHT das ungenutzte Phase-A-Budget",
        )
        assertNull(plan(60.0, geflossen).binding.takeIf { it == MealFoundation.Binding.UNUSABLE_INPUT })
    }

    // ---- Mindestversorgung statt additiver Bolus (Punkt 5) ---------------

    /**
     * DER NORMALE PFAD BEDIENT DAS SOLL MIT.
     *
     * Das Fundament ist eine MINDESTversorgung. Gibt FUSE ohnehin genug ab,
     * schweigt es - sonst addierte es zu jedem normalen SMB einen weiteren
     * Schritt und erzeugte genau die IOB-Spitze, die es vermeiden soll, nur
     * zeitlich verschoben.
     */
    @Test
    fun `eine bereits geflossene Menge bedient das Soll`() {
        // T+50: Soll = 0,583 U. Der normale Pfad hat schon 0,60 U geliefert.
        val p = plan(minuten = 50.0, geflossenU = 2.85, seitUebergabeU = 0.60)
        assertEquals(0.0, p.dueU, 1e-9, "kein zusaetzlicher Schritt")
        assertEquals(MealFoundation.Binding.COVERED_BY_DELIVERY, p.binding)
    }

    /** Und die Gegenprobe: liefert der normale Pfad zu wenig, hebt das
     *  Fundament auf das Soll an. */
    @Test
    fun `bei Unterdeckung hebt das Fundament an`() {
        val p = plan(minuten = 50.0, geflossenU = 2.35, seitUebergabeU = 0.10)
        assertEquals(STEP, p.dueU, 1e-9)
    }

    /**
     * DIE UNTERSCHEIDUNG IM GRUND ist keine Kosmetik: "die Zeit ist noch nicht
     * reif" und "jemand anders hat geliefert" fuehren zu voellig verschiedenen
     * Schluessen beim Auswerten des Replays.
     */
    @Test
    fun `ON_SCHEDULE und COVERED_BY_DELIVERY sind unterscheidbar`() {
        assertEquals(
            MealFoundation.Binding.ON_SCHEDULE,
            plan(minuten = 16.0, geflossenU = 2.25, seitUebergabeU = 0.0).binding,
            "kurz nach der Uebergabe ist einfach noch nichts faellig",
        )
        assertEquals(
            MealFoundation.Binding.COVERED_BY_DELIVERY,
            plan(minuten = 30.0, geflossenU = 2.65, seitUebergabeU = 0.40).binding,
            "hier laeuft die Versorgung ohnehin",
        )
    }

    // ---- Schalter aus heisst Verhaltensparitaet (Punkte 3 und 4) ---------

    /**
     * SCHALTER AUS: DIE HUELLE BEKOMMT DAS GANZE BUDGET.
     *
     * Auch dann, wenn ein Anteil von 0,75 gespeichert ist. Ein hinterlegter
     * Wert darf die Versorgung nicht unbemerkt kuerzen, nur weil jemand ihn
     * einmal eingestellt und das Fundament dann abgeschaltet hat.
     */
    @Test
    fun `bei ausgeschaltetem Fundament bekommt Phase A das ganze Budget`() {
        assertEquals(
            BUDGET, MealFoundation.phaseABudgetU(BUDGET, 0.75, foundationEnabled = false), 1e-9,
            "der gespeicherte Anteil ist ohne Fundament bedeutungslos",
        )
        assertEquals(BUDGET, MealFoundation.phaseABudgetU(BUDGET, 0.0, false), 1e-9, "auch bei 0.0")
    }

    /**
     * SCHALTER AN: DIE HUELLE BEKOMMT NUR IHREN ANTEIL.
     *
     * Sonst entstuenden 3,0 U Prime PLUS 0,75 U Fundament - mehr, als
     * autorisiert wurde.
     */
    @Test
    fun `bei eingeschaltetem Fundament wird Phase A begrenzt`() {
        assertEquals(2.25, MealFoundation.phaseABudgetU(BUDGET, 0.75, foundationEnabled = true), 1e-9)
        assertEquals(BUDGET, MealFoundation.phaseABudgetU(BUDGET, 1.0, true), 1e-9, "Anteil 1.0 = wie aus")
    }

    /** Und Summe A + B bleibt in jeder Stellung das Gesamtbudget. */
    @Test
    fun `Phase A plus Phase B ergibt immer genau das Budget`() {
        for (share in listOf(0.5, 0.67, 0.75, 0.8, 1.0)) {
            val a = MealFoundation.phaseABudgetU(BUDGET, share, foundationEnabled = true)
            val b = BUDGET * (1.0 - share)
            assertEquals(BUDGET, a + b, 1e-9, "Anteil $share")
        }
    }

    /** Unbrauchbare Eingaben ergeben das GANZE Budget, nicht null - fehlende
     *  Versorgung waere hier die gefaehrliche Richtung, nicht zu viel. */
    @Test
    fun `ein unbrauchbarer Anteil laesst Phase A unangetastet`() {
        assertEquals(BUDGET, MealFoundation.phaseABudgetU(BUDGET, Double.NaN, true), 1e-9)
        assertEquals(BUDGET, MealFoundation.phaseABudgetU(BUDGET, 1.5, true), 1e-9)
        assertEquals(BUDGET, MealFoundation.phaseABudgetU(BUDGET, -0.1, true), 1e-9)
    }

    // ---- Der gemeinsame Uebergabeanker (Toni 18.08.) ---------------------

    /** Ohne Verschiebung ist der Anker schlicht Marker plus Prime-Fenster. */
    @Test
    fun `ohne Verschiebung liegt die Uebergabe bei Marker plus Prime-Fenster`() {
        assertEquals(
            t0 + 15 * 60_000L,
            MealFoundation.handoverTs(t0, primeWindowStartTs = 0L, primeWindowMin = 15, wallCeilingMin = 45),
        )
    }

    /**
     * EINE CLEARANCE VERSCHIEBT DIE UEBERGABE MIT.
     *
     * Prime rechnet ab `maxOf(markerTs, primeWindowStartTs)` und setzt
     * `primeWindowStartTs` bei einer CLEARANCE auf den aktuellen Zyklus. Ohne
     * denselben Anker liefe das Fundament ab T+15 weiter, waehrend die Huelle
     * noch bis T+25 freigibt - beide zugleich, aus einem Budget.
     */
    @Test
    fun `eine Clearance verschiebt die Uebergabe fuer beide`() {
        val clearanceBei = t0 + 10 * 60_000L
        assertEquals(
            clearanceBei + 15 * 60_000L,
            MealFoundation.handoverTs(t0, clearanceBei, primeWindowMin = 15, wallCeilingMin = 45),
            "die Uebergabe wandert mit dem Prime-Fenster",
        )
    }

    /**
     * DIE WANDUHR-DECKE BEGRENZT DIE VERSCHIEBUNG.
     *
     * Ohne sie koennte eine Kette von Freigaben das Fundament bis hinter sein
     * eigenes Fensterende schieben - es kaeme nie zum Zug, und niemand saehe
     * warum.
     */
    @Test
    fun `die Wanduhr-Decke begrenzt die Verschiebung`() {
        val spaeteClearance = t0 + 120 * 60_000L
        assertEquals(
            t0 + 45 * 60_000L,
            MealFoundation.handoverTs(t0, spaeteClearance, primeWindowMin = 15, wallCeilingMin = 45),
            "hoechstens Marker plus Decke",
        )
    }

    /** Ohne Marker gibt es keinen Anker - und damit kein Fundament. */
    @Test
    fun `ohne Marker gibt es keinen Uebergabeanker`() {
        assertEquals(0L, MealFoundation.handoverTs(0L, 0L, 15, 45))
        assertEquals(0L, MealFoundation.handoverTs(t0, 0L, -1, 45), "unbrauchbare Minuten ebenso")
    }

    /**
     * DAS FENSTERENDE BLEIBT AM MARKER - die Verschiebung KUERZT das Fenster,
     * sie verlaengert es nicht.
     *
     * Sonst wuerde aus "bis T+60" eine Versorgung ohne Ende, sobald genug
     * Clearances aufeinanderfolgen.
     */
    @Test
    fun `eine verschobene Uebergabe verkuerzt das Fenster`() {
        val spaet = t0 + 30 * 60_000L
        // Uebergabe bei T+30, Ende weiterhin bei T+60: halbes Fenster.
        val p = MealFoundation.plan(
            markerTs = t0, nowTs = t0 + 45 * 60_000L, handoverTs = spaet,
            totalBudgetU = BUDGET, phaseAShare = A_SHARE, phaseBUntilMin = B_BIS,
            deliveredFromBudgetU = 2.25, deliveredSinceHandoverU = 0.0, bolusStepU = STEP,
        )
        assertEquals(
            B_BUDGET / 2.0, p.plannedTotalU, 1e-9,
            "bei T+45 ist die Haelfte des verkuerzten Fensters vorbei",
        )
    }


    /**
     * DER GRUND HEISST NICHT MEHR "NORMALER PFAD".
     *
     * `deliveredSinceHandoverU` enthaelt ALLE Mengen, auch frueher freigegebene
     * Fundamentschritte. Der Kern kann also gar nicht wissen, wer geliefert
     * hat - und darf es deshalb nicht behaupten.
     */
    @Test
    fun `auch ein frueherer Fundamentschritt deckt das Soll`() {
        // Die 0,40 U koennen ebenso gut aus dem Fundament stammen.
        val p = plan(minuten = 30.0, geflossenU = 2.65, seitUebergabeU = 0.40)
        assertEquals(
            MealFoundation.Binding.COVERED_BY_DELIVERY, p.binding,
            "der Grund benennt die MENGE, nicht ihre Herkunft",
        )
    }

    // ---- Pinning der autorisierten Konfiguration (Toni 18.08.) -----------

    private fun armiere(
        budget: Double = BUDGET,
        anteil: Double = A_SHARE,
        an: Boolean = true,
        ende: Int = B_BIS,
        primeStart: Long = 0L,
    ) = MealFoundation.arm(
        markerTs = t0, foundationEnabled = an, totalBudgetU = budget, phaseAShare = anteil,
        primeWindowStartTs = primeStart, primeWindowMin = A_BIS, wallCeilingMin = 45,
        phaseBUntilMin = ende,
    )

    @Test
    fun `das Armen friert Budget, Anteil und Zeiten ein`() {
        val a = armiere()
        assertTrue(a.valid)
        assertEquals(BUDGET, a.totalBudgetU, 1e-9)
        assertEquals(2.25, a.phaseABudgetU, 1e-9)
        assertEquals(0.75, a.phaseBBudgetU, 1e-9)
        assertEquals(t0 + A_BIS * 60_000L, a.handoverTs)
        assertEquals(t0 + B_BIS * 60_000L, a.endTs)
    }

    /**
     * EINE SPAETERE AENDERUNG ERREICHT DIE LAUFENDE MAHLZEIT NICHT.
     *
     * Das ist der Kern des Pinnings (Toni 18.08.): "konfigurierbar heisst
     * beim naechsten Markerdruck waehlbar, nicht eine laufende
     * Insulinautorisierung nachtraeglich veraenderbar". Wuerde
     * PrimeEnvelopeU bei T+40 erhoeht, entstuende zusaetzliches "bereits
     * autorisiertes" Insulin, das niemand autorisiert hat.
     */
    @Test
    fun `eine spaetere Budgetaenderung veraendert die laufende Autorisierung nicht`() {
        val a = armiere(budget = 3.0)
        // Der Nutzer stellt bei T+40 auf 4,0 U - der Plan liest weiter 3,0 U.
        val p = MealFoundation.planFrom(a, t0 + 40 * 60_000L, 2.25, 0.0, STEP)
        assertEquals(
            0.75 - 0.0, p.remainingInWindowU, 1e-9,
            "das Phase-B-Budget bleibt die Momentaufnahme von 3,0 U",
        )
        assertTrue(p.remainingInWindowU < 1.0, "und nicht das aus 4,0 U abgeleitete")
    }

    /** Auch eine Anteilsaenderung oeffnet kein neues Phase-B-Budget. */
    @Test
    fun `eine spaetere Anteilsaenderung oeffnet kein neues Budget`() {
        val a = armiere(anteil = 0.75)
        val p = MealFoundation.planFrom(a, t0 + 60 * 60_000L, 2.25, 0.75, STEP)
        assertEquals(0.0, p.remainingInWindowU, 1e-9, "0,75 U vergeben, nichts offen")
        assertEquals(0.0, p.dueU, 1e-9)
    }

    /** Und eine spaetere Endzeit verlaengert die laufende Phase nicht. */
    @Test
    fun `eine spaetere Endzeit verlaengert die laufende Phase nicht`() {
        val a = armiere(ende = 60)
        // Selbst bei T+80 ist das gepinnte Ende T+60 massgeblich.
        val p = MealFoundation.planFrom(a, t0 + 80 * 60_000L, 2.25, 0.0, STEP)
        assertEquals(MealFoundation.Binding.AFTER_WINDOW, p.binding)
        assertEquals(0.0, p.dueU, 1e-9)
    }

    /**
     * DER SCHALTER MITTEN IN EINER EPISODE ARMIERT NICHTS.
     *
     * Ohne diesen Riegel saehe ein Schalterdruck bei T+40 sofort ein Soll von
     * zwei Dritteln des Phase-B-Budgets - der Ein-Schritt-Riegel wuerde es
     * ueber Minuten nachliefern statt in einem Zug, aber liefern wuerde er es.
     * Armiert wird erst das naechste bewusst eroeffnete Markerbudget.
     */
    @Test
    fun `bei ausgeschaltetem Fundament entsteht keine Momentaufnahme`() {
        val a = armiere(an = false)
        assertFalse(a.valid, "keine Autorisierung")
        assertEquals(
            MealFoundation.Binding.UNUSABLE_INPUT,
            MealFoundation.planFrom(a, t0 + 40 * 60_000L, 2.25, 0.0, STEP).binding,
            "und damit auch kein rueckwirkender Rueckstand",
        )
    }

    /**
     * PRIME UND FUNDAMENT LESEN DIESELBE AUTORISIERUNG.
     *
     * Sonst rechnete die Huelle live mit einem geaenderten PrimeEnvelopeU,
     * waehrend Phase B den alten Gesamtbetrag verwendet - und die Summe waere
     * weder das eine noch das andere.
     */
    @Test
    fun `Prime liest bei aktivem Fundament das gepinnte Phase-A-Budget`() {
        val a = armiere(budget = 3.0, anteil = 0.75)
        assertEquals(
            2.25, MealFoundation.primeBudgetU(a, liveTotalBudgetU = 4.0), 1e-9,
            "die spaetere Erhoehung auf 4,0 U erreicht Prime nicht",
        )
        assertEquals(
            a.phaseABudgetU + a.phaseBBudgetU, a.totalBudgetU, 1e-9,
            "und beide Teile ergeben zusammen genau die Autorisierung",
        )
    }

    /** Ohne Autorisierung gilt unveraendert das Live-Budget - der heutige
     *  Stand muss bitgleich bleiben. */
    @Test
    fun `ohne Autorisierung liest Prime unveraendert das Live-Budget`() {
        assertEquals(
            4.0, MealFoundation.primeBudgetU(MealFoundation.Authorization.none(), 4.0), 1e-9,
        )
    }

    /** Die Uebergabe wird beim Armen festgeschrieben - eine spaetere
     *  Clearance verschiebt sie nicht mehr. */
    @Test
    fun `die Uebergabe wird beim Armen festgeschrieben`() {
        val mitClearance = armiere(primeStart = t0 + 10 * 60_000L)
        assertEquals(
            t0 + 25 * 60_000L, mitClearance.handoverTs,
            "beim Armen galt die verschobene Grenze - und sie bleibt",
        )
    }

    /** Liegt die Uebergabe hinter dem Fensterende, ist das eine gueltige Lage
     *  ohne Fenster - kein Eingabefehler. */
    @Test
    fun `keine Zeit nach der Uebergabe ist eine gueltige Lage`() {
        val p = MealFoundation.plan(
            markerTs = t0, nowTs = t0 + 70 * 60_000L, handoverTs = t0 + 90 * 60_000L,
            totalBudgetU = BUDGET, phaseAShare = A_SHARE, phaseBUntilMin = B_BIS,
            deliveredFromBudgetU = 2.25, deliveredSinceHandoverU = 0.0, bolusStepU = STEP,
        )
        assertEquals(MealFoundation.Binding.NO_WINDOW_AFTER_HANDOVER, p.binding)
        assertEquals(0, p.effectiveWindowMin)
    }

    /**
     * DIE KOMPRESSION IST SICHTBAR.
     *
     * Eine verschobene Uebergabe presst das ganze Phase-B-Budget in die
     * Restzeit: aus 0,05 U je drei Minuten koennen 0,05 U je Minute werden.
     * Das bleibt heute so - aber es MUSS im Export stehen, sonst faellt eine
     * Verdreifachung der Rate im Replay niemandem auf.
     */
    @Test
    fun `eine verschobene Uebergabe macht die Kompression sichtbar`() {
        val normal = plan(minuten = 20.0, geflossenU = 2.25)
        assertEquals(45, normal.effectiveWindowMin, "45 min Fenster")
        assertEquals(0.75 / 45.0, normal.effectiveRateUPerMin, 1e-9)

        val spaet = MealFoundation.plan(
            markerTs = t0, nowTs = t0 + 50 * 60_000L, handoverTs = t0 + 45 * 60_000L,
            totalBudgetU = BUDGET, phaseAShare = A_SHARE, phaseBUntilMin = B_BIS,
            deliveredFromBudgetU = 2.25, deliveredSinceHandoverU = 0.0, bolusStepU = STEP,
        )
        assertEquals(15, spaet.effectiveWindowMin, "nur noch 15 min")
        assertEquals(
            0.75 / 15.0, spaet.effectiveRateUPerMin, 1e-9,
            "dreifache Sollrate - sichtbar, nicht versteckt",
        )
    }
}
