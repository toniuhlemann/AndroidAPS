package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
        uebertragU: Double = 0.0,
    ) = MealFoundation.plan(
        markerTs = t0,
        nowTs = t0 + (minuten * 60_000).toLong(),
        handoverTs = t0 + A_BIS * 60_000L,
        totalBudgetU = BUDGET,
        phaseBBudgetU = B_BUDGET,
        confirmedNotSentPhaseAU = uebertragU,
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
            budget: Double = BUDGET, bBudget: Double = B_BUDGET, ende: Int = B_BIS,
            geflossen: Double = 0.0, seitUebergabe: Double = 0.0, step: Double = STEP,
        ) = MealFoundation.plan(
            marker, jetzt, uebergabe, budget, bBudget,
            confirmedNotSentPhaseAU = 0.0,
            phaseBUntilMin = ende, deliveredFromBudgetU = geflossen,
            deliveredSinceHandoverU = seitUebergabe, bolusStepU = step,
        )

        val faelle = listOf(
            "kein Marker" to p(marker = 0L),
            "jetzt vor Marker" to p(jetzt = t0 - 1000L),
            "Uebergabe vor Marker" to p(uebergabe = t0 - 1000L),
            "Budget NaN" to p(budget = Double.NaN),
            "Budget 0" to p(budget = 0.0),
            // Seit plan() das Teilbudget DIREKT bekommt, sind das die
            // Widersprueche, die eine kaputte Quelle liefern koennte.
            "Teilbudget ueber Gesamt" to p(bBudget = BUDGET + 0.5),
            "Teilbudget NaN" to p(bBudget = Double.NaN),
            "Teilbudget negativ" to p(bBudget = -0.1),
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
                phaseBBudgetU = 0.0, confirmedNotSentPhaseAU = 0.0, phaseBUntilMin = B_BIS,
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
            totalBudgetU = BUDGET, phaseBBudgetU = B_BUDGET, confirmedNotSentPhaseAU = 0.0, phaseBUntilMin = B_BIS,
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

    /**
     * DIE UEBERGABE FOLGT DEM GEKAPPTEN PRIME-FENSTER, nicht dem eingestellten.
     *
     * PrimeRelease.plan kappt sein Fenster auf 5..WALL_CEILING_MIN. Waere die
     * Uebergabe ungekappt, entstuende bei einem zu grossen Fenster eine Strecke,
     * auf der WEDER Prime noch das Fundament liefert - Prime hat geschlossen,
     * das Fundament haelt sich noch fuer Phase A. Bei einem zu kleinen liefen
     * beide gleichzeitig.
     *
     * Heute decken sich Kappung und Einstellgrenzen (5..45). Genau darauf soll
     * sich der Anker aber NICHT verlassen.
     */
    @Test
    fun `die Uebergabe folgt der Kappung des Prime-Fensters`() {
        // Zu gross: 60 eingestellt, Prime schliesst trotzdem bei 45.
        assertEquals(
            t0 + 45 * 60_000L,
            MealFoundation.handoverTs(t0, 0L, primeWindowMin = 60, wallCeilingMin = 90),
            "ohne Kappung stuenden hier 60 min - 15 min ohne jeden Kanal",
        )
        // Zu klein: 2 eingestellt, Prime liefert trotzdem bis 5.
        assertEquals(
            t0 + 5 * 60_000L,
            MealFoundation.handoverTs(t0, 0L, primeWindowMin = 2, wallCeilingMin = 45),
            "ohne Kappung stuenden hier 2 min - 3 min mit beiden Kanaelen",
        )
    }

    /** Die Decke bleibt die haertere Grenze, auch nach der Kappung. */
    @Test
    fun `die Wanduhr-Decke schlaegt das gekappte Fenster`() {
        assertEquals(
            t0 + 20 * 60_000L,
            MealFoundation.handoverTs(t0, t0 + 30 * 60_000L, primeWindowMin = 45, wallCeilingMin = 20),
            "eine Clearance bei T+30 darf die Uebergabe nicht ueber die Decke schieben",
        )
    }

    /**
     * DIE GEGENPROBE ZUM TEILBUDGET-RIEGEL: gleich gross ist ZULAESSIG.
     *
     * Die ablehnende Seite steht in der Tabelle oben ("Teilbudget ueber
     * Gesamt"). Ohne diese Gegenprobe bliebe ein ZU STRENGER Riegel
     * unentdeckt: mit `>=` statt `> + 1e-9` waere Anteil 0 - ein ganzes
     * Budget in Phase B - faelschlich unbrauchbar, und das Fundament gaebe
     * bei dieser Einstellung stillschweigend gar nichts.
     *
     * Die heutigen Einstellgrenzen (Anteil 0,5..1,0) lassen den Fall nicht
     * zu. Genau darauf soll sich der Riegel aber nicht verlassen - plan() ist
     * eine oeffentliche Kernfunktion, und arm() nimmt 0,0..1,0 entgegen.
     */
    @Test
    fun `ein Teilbudget in Hoehe des Gesamtbudgets ist zulaessig`() {
        val p = MealFoundation.plan(
            markerTs = t0, nowTs = t0 + 30 * 60_000L, handoverTs = t0 + A_BIS * 60_000L,
            totalBudgetU = BUDGET, phaseBBudgetU = BUDGET, confirmedNotSentPhaseAU = 0.0, phaseBUntilMin = B_BIS,
            deliveredFromBudgetU = 0.0, deliveredSinceHandoverU = 0.0, bolusStepU = STEP,
        )
        assertNotEquals(
            MealFoundation.Binding.UNUSABLE_INPUT, p.binding,
            "Anteil 0 ist eine gueltige Einstellung, auch wenn sie heute niemand waehlt",
        )
        assertEquals(BUDGET, p.remainingInWindowU, 1e-9)
    }

    /** Und die Autorisierung fuehrt denselben Fall widerspruchsfrei. */
    @Test
    fun `Anteil null ergibt ein vollstaendiges Phase-B-Budget`() {
        val a = MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = BUDGET, phaseAShare = 0.0,
            primeWindowMin = A_BIS, wallCeilingMin = 45, pressObservedInThisProcess = true, primeDeclinedByUser = false, markerAuthorized = true, phaseBUntilMin = B_BIS,
        )
        assertTrue(a.valid)
        assertEquals(0.0, a.phaseABudgetU, 1e-9)
        assertEquals(BUDGET, a.phaseBBudgetU, 1e-9)
        assertNotEquals(
            MealFoundation.Binding.UNUSABLE_INPUT,
            MealFoundation.planFrom(a, t0 + 30 * 60_000L, 0L, 0.0, 0.0, 0.0, STEP).binding,
            "der Riegel darf die eigene Autorisierung nicht abweisen",
        )
    }

    // ---- Die Phase eines Zyklus (Punkt 7) --------------------------------

    private fun phase(minuten: Double, clearance: Long = 0L, auth: MealFoundation.Authorization? = null) =
        MealFoundation.phaseOf(
            auth ?: MealFoundation.arm(
                markerTs = t0, foundationEnabled = true, totalBudgetU = BUDGET, phaseAShare = A_SHARE,
                primeWindowMin = A_BIS, wallCeilingMin = 45, pressObservedInThisProcess = true, primeDeclinedByUser = false, markerAuthorized = true, phaseBUntilMin = B_BIS,
            ),
            t0 + (minuten * 60_000).toLong(), clearance,
        )

    /**
     * DER UEBERGABEZEITPUNKT GEHOERT SCHON ZU PHASE B.
     *
     * Mit `>` statt `>=` faende ein Zyklus, der exakt auf den Anker faellt, in
     * KEINER der beiden Phasen statt: Prime hat geschlossen, das Fundament
     * zaehlt ihn noch nicht. Seine Abgabe zaehlte dann nirgends, und Phase B
     * hielte sich fuer unversorgt - sie gaebe genau diese Menge ein zweites
     * Mal. Unter einem Minutentakt ist das kein Randfall, sondern ein Zyklus
     * je Mahlzeit.
     */
    @Test
    fun `genau auf dem Uebergabeanker gilt schon Phase B`() {
        assertEquals(MealFoundation.Phase.PHASE_A, phase(A_BIS - 1.0))
        assertEquals(MealFoundation.Phase.PHASE_B, phase(A_BIS.toDouble()), "die Grenze ist einschliessend")
        assertEquals(MealFoundation.Phase.PHASE_B, phase(A_BIS + 1.0))
    }

    /** Ohne gueltige Autorisierung gibt es keine Phase. */
    @Test
    fun `ohne Autorisierung gibt es keine Phase`() {
        assertEquals(
            MealFoundation.Phase.NONE,
            phase(30.0, auth = MealFoundation.Authorization.none()),
        )
    }

    /** Die Phase folgt der verschobenen Uebergabe, nicht dem Marker. */
    @Test
    fun `eine Clearance verschiebt auch die Phasengrenze`() {
        val clearance = t0 + 10 * 60_000L
        assertEquals(
            MealFoundation.Phase.PHASE_A, phase(20.0, clearance = clearance),
            "die Uebergabe liegt jetzt bei T+25 - bei T+20 ist noch Phase A",
        )
        assertEquals(MealFoundation.Phase.PHASE_B, phase(25.0, clearance = clearance))
    }

    /** Nach dem Latch bleibt die Grenze stehen, auch bei spaeter Clearance. */
    @Test
    fun `nach dem Latch bleibt die Phasengrenze stehen`() {
        val gelatcht = MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = BUDGET, phaseAShare = A_SHARE,
            primeWindowMin = A_BIS, wallCeilingMin = 45, pressObservedInThisProcess = true, primeDeclinedByUser = false, markerAuthorized = true, phaseBUntilMin = B_BIS,
        ).latchIfDue(t0 + A_BIS * 60_000L, 0L)
        assertEquals(
            MealFoundation.Phase.PHASE_B,
            MealFoundation.phaseOf(gelatcht, t0 + 20 * 60_000L, t0 + 30 * 60_000L),
            "eine Clearance bei T+30 darf einen bereits gebuchten Zyklus nicht nach Phase A zurueckholen",
        )
    }

    /**
     * DAS FENSTERENDE BEENDET PHASE B (Toni 18.08., P0).
     *
     * Ohne diese Grenze blieb die Phase nach der Uebergabe unbegrenzt
     * PHASE_B. Der Zaehler `deliveredSinceHandoverU` haette dann jeden
     * spaeteren Korrektur-SMB eingesammelt - Stunden und Tage nach der
     * Mahlzeit -, und zwar auf einem PERSISTIERTEN Feld: er waere ohne
     * Obergrenze weitergewachsen, bis er an der Codec-Schranke haengt. Dann
     * ist die Generation unlesbar, nicht nur ungenau.
     */
    @Test
    fun `das Fensterende beendet Phase B`() {
        assertEquals(MealFoundation.Phase.PHASE_B, phase(B_BIS - 1.0))
        assertEquals(
            MealFoundation.Phase.PHASE_B, phase(B_BIS.toDouble()),
            "das Fensterende gehoert noch dazu - beide Kanten einschliessend",
        )
        assertEquals(MealFoundation.Phase.AFTER_WINDOW, phase(B_BIS + 1.0))
        assertEquals(
            MealFoundation.Phase.AFTER_WINDOW, phase(B_BIS + 24 * 60.0),
            "auch einen Tag spaeter - und NICHT wieder PHASE_B",
        )
    }

    /**
     * FERTIG IST NICHT DASSELBE WIE NIE GELAUFEN.
     *
     * AFTER_WINDOW ist ausdruecklich nicht NONE: die Autorisierung existiert
     * noch, sie hat nur nichts mehr zu verteilen. Fuer den Export ist das
     * derselbe Unterschied wie zwischen PHASE_A und NONE.
     */
    @Test
    fun `nach dem Fenster ist die Autorisierung nicht verschwunden`() {
        assertEquals(
            MealFoundation.Phase.NONE,
            phase(B_BIS + 10.0, auth = MealFoundation.Authorization.none()),
            "OHNE Autorisierung ist es NONE",
        )
        assertEquals(
            MealFoundation.Phase.AFTER_WINDOW, phase(B_BIS + 10.0),
            "MIT abgelaufener Autorisierung ist es AFTER_WINDOW",
        )
    }

    /**
     * DIE UEBERGABE KANN HINTER DEM FENSTERENDE LIEGEN - dann gibt es Phase B
     * nie.
     *
     * Eine Kette von Clearances schiebt den Anker bis an die Wanduhr-Decke.
     * Liegt die hinter dem Fensterende, springt die Lage von PHASE_A direkt
     * auf AFTER_WINDOW. Das ist die richtige Antwort und kein Widerspruch:
     * plan() meldet fuer genau diese Lage NO_WINDOW_AFTER_HANDOVER.
     */
    @Test
    fun `liegt die Uebergabe hinter dem Ende, gibt es kein Phase B`() {
        val kurz = MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = BUDGET, phaseAShare = A_SHARE,
            primeWindowMin = A_BIS, wallCeilingMin = 45, phaseBUntilMin = 20, pressObservedInThisProcess = true, primeDeclinedByUser = false, markerAuthorized = true,
        )
        // Clearance bei T+30 schiebt die Uebergabe auf T+45 (Decke), das
        // Fenster endet aber schon bei T+20.
        val clearance = t0 + 30 * 60_000L
        assertEquals(
            MealFoundation.Phase.PHASE_A,
            MealFoundation.phaseOf(kurz, t0 + 25 * 60_000L, clearance),
            "vor der verschobenen Uebergabe - Prime finanziert noch",
        )
        assertEquals(
            MealFoundation.Phase.AFTER_WINDOW,
            MealFoundation.phaseOf(kurz, t0 + 50 * 60_000L, clearance),
            "danach ist das Fenster laengst vorbei - nie PHASE_B",
        )
    }

    // ---- Pinning der autorisierten Konfiguration (Toni 18.08.) -----------

    private fun armiere(
        budget: Double = BUDGET,
        anteil: Double = A_SHARE,
        an: Boolean = true,
        ende: Int = B_BIS,
    ) = MealFoundation.arm(
        markerTs = t0, foundationEnabled = an, totalBudgetU = budget, phaseAShare = anteil,
        primeWindowMin = A_BIS, wallCeilingMin = 45, phaseBUntilMin = ende, pressObservedInThisProcess = true, primeDeclinedByUser = false, markerAuthorized = true,
    )

    @Test
    fun `das Armen friert Budget, Anteil und Zeiten ein`() {
        val a = armiere()
        assertTrue(a.valid)
        assertEquals(BUDGET, a.totalBudgetU, 1e-9)
        assertEquals(2.25, a.phaseABudgetU, 1e-9)
        assertEquals(0.75, a.phaseBBudgetU, 1e-9)
        assertEquals(t0 + B_BIS * 60_000L, a.endTs)
        assertEquals(0L, a.latchedHandoverTs, "die Uebergabe ist noch NICHT festgeschrieben")
    }

    /**
     * DIE TEILBUDGETS SIND ABGELEITET, NICHT GESPEICHERT (Toni 18.08., P0-1).
     *
     * Der erste Wurf war eine data class mit gespeicherten Teilbudgets. copy()
     * konnte damit ein A von 3,00 neben einem B von 0,75 erzeugen, obwohl das
     * Gesamt 3,00 war - und es galt als gueltig. Da primeBudgetU() das
     * gespeicherte A las und planFrom() das B neu rechnete, haetten Prime und
     * Fundament zusammen 3,75 U aus einer 3-U-Autorisierung gesehen.
     */
    @Test
    fun `Phase A und B ergeben in jeder Stellung exakt das Gesamtbudget`() {
        for (anteil in listOf(0.0, 0.33, 0.5, 0.67, 0.75, 0.8, 1.0)) {
            val a = armiere(anteil = anteil)
            assertEquals(
                a.totalBudgetU, a.phaseABudgetU + a.phaseBBudgetU, 1e-12,
                "Anteil $anteil - die Summe MUSS exakt aufgehen",
            )
        }
    }

    /**
     * EINE SPAETERE AENDERUNG ERREICHT DIE LAUFENDE MAHLZEIT NICHT.
     *
     * "Konfigurierbar heisst beim naechsten Markerdruck waehlbar, nicht eine
     * laufende Insulinautorisierung nachtraeglich veraenderbar."
     */
    @Test
    fun `eine spaetere Budgetaenderung veraendert die laufende Autorisierung nicht`() {
        val a = armiere(budget = 3.0)
        val p = MealFoundation.planFrom(a, t0 + 40 * 60_000L, 0L, 2.25, 0.0, 0.0, STEP)
        assertEquals(
            0.75, p.remainingInWindowU, 1e-9,
            "das Phase-B-Budget bleibt die Momentaufnahme von 3,0 U",
        )
    }

    @Test
    fun `eine spaetere Endzeit verlaengert die laufende Phase nicht`() {
        val a = armiere(ende = 60)
        val p = MealFoundation.planFrom(a, t0 + 80 * 60_000L, 0L, 2.25, 0.0, 0.0, STEP)
        assertEquals(MealFoundation.Binding.AFTER_WINDOW, p.binding)
        assertEquals(0.0, p.dueU, 1e-9)
    }

    @Test
    fun `bei ausgeschaltetem Fundament entsteht keine Momentaufnahme`() {
        val a = armiere(an = false)
        assertFalse(a.valid, "keine Autorisierung")
        assertEquals(
            MealFoundation.Binding.UNUSABLE_INPUT,
            MealFoundation.planFrom(a, t0 + 40 * 60_000L, 0L, 2.25, 0.0, 0.0, STEP).binding,
            "und damit auch kein rueckwirkender Rueckstand",
        )
    }

    @Test
    fun `Prime liest bei aktivem Fundament das gepinnte Phase-A-Budget`() {
        val a = armiere(budget = 3.0, anteil = 0.75)
        assertEquals(
            2.25, MealFoundation.primeBudgetU(a, liveTotalBudgetU = 4.0), 1e-9,
            "die spaetere Erhoehung auf 4,0 U erreicht Prime nicht",
        )
    }

    @Test
    fun `ohne Autorisierung liest Prime unveraendert das Live-Budget`() {
        assertEquals(4.0, MealFoundation.primeBudgetU(MealFoundation.Authorization.none(), 4.0), 1e-9)
    }

    // ---- Die Uebergabe folgt der Laufzeit bis zum Latch (P0-2) -----------

    /**
     * EINE CLEARANCE NACH DEM ARMEN VERSCHIEBT BEIDE PHASEN GEMEINSAM.
     *
     * Der erste Wurf fror die Uebergabe schon beim Markerdruck ein - zu einem
     * Zeitpunkt, an dem spaetere Verschiebungen noch gar nicht bekannt sein
     * KOENNEN. Damit war die gerade beseitigte Ueberlappung wieder da: Prime
     * gibt bis T+25 frei, das Fundament zaehlt ab T+15. Der damalige Test
     * "spaetere Clearance verschiebt sie nicht mehr" hat den Fehler
     * festgeschrieben - er ist durch diesen hier ersetzt.
     */
    @Test
    fun `eine Clearance nach dem Armen verschiebt die Uebergabe mit`() {
        val a = armiere()
        val clearanceBei = t0 + 10 * 60_000L
        assertEquals(
            clearanceBei + A_BIS * 60_000L, a.effectiveHandoverTs(clearanceBei),
            "die Uebergabe folgt dem verschobenen Prime-Fenster",
        )
        // Und bei T+20 - vor der verschobenen Uebergabe - entsteht nichts.
        val p = MealFoundation.planFrom(a, t0 + 20 * 60_000L, clearanceBei, 2.25, 0.0, 0.0, STEP)
        assertEquals(0.0, p.dueU, 1e-9)
        assertEquals(
            MealFoundation.Binding.BEFORE_WINDOW, p.binding,
            "waehrend Prime noch freigibt, schweigt das Fundament",
        )
    }

    /**
     * VOR DER FAELLIGKEIT WIRD NICHT GELATCHT (Toni 18.08., P0).
     *
     * Die Methode hiess zunaechst `latched(handoverTs)` und nahm jeden
     * Zeitpunkt entgegen - ein Aufrufer konnte bei T+10 den damals
     * berechneten T+15-Anker festschreiben, und eine spaetere Clearance waere
     * wieder ignoriert worden. Der beseitigte Fehler blieb ueber die API
     * formulierbar.
     *
     * Der ganze Ablauf in einem Test: zu frueh tut nichts, die Clearance
     * verschiebt weiter, und erst am wirklich erreichten Uebergang wird
     * festgeschrieben.
     */
    @Test
    fun `gelatcht wird erst am tatsaechlich erreichten Uebergang`() {
        val a = armiere()
        val clearanceBei = t0 + 10 * 60_000L

        // T+5: viel zu frueh - nichts passiert.
        val fruehVersuch = a.latchIfDue(t0 + 5 * 60_000L, primeWindowStartTs = 0L)
        assertEquals(0L, fruehVersuch.latchedHandoverTs, "vor der Faelligkeit kein Latch")

        // Die Clearance verschiebt die Uebergabe auf T+25.
        assertEquals(
            clearanceBei + A_BIS * 60_000L, a.effectiveHandoverTs(clearanceBei),
            "die Verschiebung wirkt noch",
        )

        // T+20: nach dem URSPRUENGLICHEN T+15, aber vor dem verschobenen T+25.
        val dazwischen = a.latchIfDue(t0 + 20 * 60_000L, clearanceBei)
        assertEquals(
            0L, dazwischen.latchedHandoverTs,
            "der alte Anker darf nicht nachtraeglich festgeschrieben werden",
        )

        // T+25: jetzt ist der verschobene Uebergang erreicht.
        val gelatcht = a.latchIfDue(t0 + 25 * 60_000L, clearanceBei)
        assertEquals(clearanceBei + A_BIS * 60_000L, gelatcht.latchedHandoverTs)

        // Und danach wandert er nicht mehr.
        assertEquals(
            gelatcht.latchedHandoverTs, gelatcht.effectiveHandoverTs(t0 + 50 * 60_000L),
            "eine spaete Clearance hat keine rueckwirkende Kraft mehr",
        )
    }

    /** Ein zweiter Latch veraendert nichts - sonst haette ein spaeter Zyklus
     *  die Wirkung einer Clearance nachtraeglich. */
    @Test
    fun `ein zweiter Latch ist wirkungslos`() {
        val einmal = armiere().latchIfDue(t0 + 20 * 60_000L, 0L)
        assertTrue(einmal.latchedHandoverTs > 0L, "der erste greift")
        val zweimal = einmal.latchIfDue(t0 + 50 * 60_000L, t0 + 40 * 60_000L)
        assertEquals(einmal.latchedHandoverTs, zweimal.latchedHandoverTs)
    }

    /** Ohne gueltige Autorisierung wird nie gelatcht. */
    @Test
    fun `ohne Autorisierung gibt es keinen Latch`() {
        val ohne = MealFoundation.Authorization.none().latchIfDue(t0 + 60 * 60_000L, 0L)
        assertEquals(0L, ohne.latchedHandoverTs)
    }

    // ---- Wiederherstellen ist fail-closed --------------------------------

    /**
     * WIDERSPRUECHLICHE DATEN ERGEBEN KEINE AUTORISIERUNG.
     *
     * Ein halb gelesenes Budget waere eine Insulinfreigabe, die niemand
     * erteilt hat.
     */
    @Test
    fun `eine widerspruechliche Generation wird beim Restore abgelehnt`() {
        val faelle = mapOf(
            "kein Marker" to MealFoundation.Authorization.restore(0L, BUDGET, A_SHARE, A_BIS, 45, t0 + 3_600_000L, true, 0L),
            "Budget NaN" to MealFoundation.Authorization.restore(t0, Double.NaN, A_SHARE, A_BIS, 45, t0 + 3_600_000L, true, 0L),
            "Budget 0" to MealFoundation.Authorization.restore(t0, 0.0, A_SHARE, A_BIS, 45, t0 + 3_600_000L, true, 0L),
            "Anteil ueber 1" to MealFoundation.Authorization.restore(t0, BUDGET, 1.5, A_BIS, 45, t0 + 3_600_000L, true, 0L),
            "Ende vor Marker" to MealFoundation.Authorization.restore(t0, BUDGET, A_SHARE, A_BIS, 45, t0 - 1000L, true, 0L),
            "Latch vor Marker" to MealFoundation.Authorization.restore(t0, BUDGET, A_SHARE, A_BIS, 45, t0 + 3_600_000L, true, t0 - 1000L),
            "negatives Fenster" to MealFoundation.Authorization.restore(t0, BUDGET, A_SHARE, -1, 45, t0 + 3_600_000L, true, 0L),
        )
        for ((name, a) in faelle) {
            assertFalse(a.valid, name)
            // UND ES KOMMT WIRKLICH `none()` ZURUECK, nicht die kaputte
            // Instanz mit gesetztem `valid=false`. Sonst koennte ein Aufrufer
            // `phaseABudgetU` lesen und einen Unsinnswert bekommen - die
            // Pruefung waere dann nur eine Empfehlung.
            assertEquals(0L, a.armedTs, "$name: genullt")
            assertEquals(0.0, a.totalBudgetU, 1e-12, "$name: genullt")
            assertEquals(0.0, a.phaseABudgetU, 1e-12, "$name: kein Teilbudget")
        }
    }

    /** Eine stimmige Generation kommt vollstaendig zurueck - auch der Latch. */
    @Test
    fun `eine stimmige Generation ueberlebt den Restore`() {
        val a = MealFoundation.Authorization.restore(
            t0, BUDGET, A_SHARE, A_BIS, 45, t0 + B_BIS * 60_000L, true, t0 + 20 * 60_000L,
        )
        assertTrue(a.valid)
        assertEquals(2.25, a.phaseABudgetU, 1e-9)
        assertEquals(t0 + 20 * 60_000L, a.latchedHandoverTs)
        assertTrue(
            a.pinnedMarkerAuthorized,
            "die gepinnte Marker-Autorisierung MUSS den Restore ueberleben - sonst " +
                "laese der Lift nach einem Neustart wieder die aktuelle Preference",
        )
        assertEquals(
            t0 + 20 * 60_000L, a.effectiveHandoverTs(t0 + 40 * 60_000L),
            "der gelatchte Anker gilt weiter, egal was Prime inzwischen meldet",
        )
    }

    /** Liegt die Uebergabe hinter dem Fensterende, ist das eine gueltige Lage
     *  ohne Fenster - kein Eingabefehler. */
    @Test
    fun `keine Zeit nach der Uebergabe ist eine gueltige Lage`() {
        val p = MealFoundation.plan(
            markerTs = t0, nowTs = t0 + 70 * 60_000L, handoverTs = t0 + 90 * 60_000L,
            totalBudgetU = BUDGET, phaseBBudgetU = B_BUDGET, confirmedNotSentPhaseAU = 0.0, phaseBUntilMin = B_BIS,
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
            totalBudgetU = BUDGET, phaseBBudgetU = B_BUDGET, confirmedNotSentPhaseAU = 0.0, phaseBUntilMin = B_BIS,
            deliveredFromBudgetU = 2.25, deliveredSinceHandoverU = 0.0, bolusStepU = STEP,
        )
        assertEquals(15, spaet.effectiveWindowMin, "nur noch 15 min")
        assertEquals(
            0.75 / 15.0, spaet.effectiveRateUPerMin, 1e-9,
            "dreifache Sollrate - sichtbar, nicht versteckt",
        )
    }
}
