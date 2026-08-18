package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * OFFLINE-REPLAY DES MAHLZEITENFUNDAMENTS (Punkt 12, Toni 18.08.).
 *
 * Vier Aufteilungen - 100/0, 80/20, 75/25, 67/33 - gegen DIESELBEN
 * eingefrorenen Mahlzeiten. 100/0 ist der heutige Stand und damit die
 * Vergleichsspur: bei ihr muss das Fundament nachweislich nichts tun.
 *
 * WAS HIER SIMULIERT WIRD UND WAS NICHT. Simuliert wird der Zeitverlauf der
 * Abgaben und die Buchfuehrung, die der Runner darum baut. NICHT simuliert
 * werden Regelentscheidungen: was der normale Pfad (Prime, Onset, Korrektur,
 * Evidenz) abgibt, ist EINGEFROREN und je Variante identisch. Genau das
 * macht den Vergleich aussagekraeftig - der einzige Unterschied zwischen den
 * Spuren ist die Aufteilung.
 *
 * Die Mahlzeitenformen sind aus den eigenen Geraetelaeufen abgeleitet (die
 * ROHDATEN gehoeren nicht ins Repository): der ausgeschoepfte Prime, der
 * zurueckgebliebene Prime mit 1,40 U statt 2,25 U vom 06.08., und die Faelle
 * mit nachlaufender gewoehnlicher Korrektur.
 *
 * DIE GRENZE DIESER SIMULATION, ausdruecklich, damit niemand die Zahlen
 * staerker liest als sie sind:
 *
 *   `dueU` ist eine FORDERUNG, keine garantierte Abgabe. Zwischen ihr und
 *   der Pumpe liegen unveraendert alle nachgelagerten Gates - Guards,
 *   iobTH, maxIOB, Publikations- und Pumpengate. Das Replay sagt, was das
 *   Fundament VERLANGT, nicht was fliesst.
 *
 *   Am deutlichsten wird das in der Zeile "nichts laeuft": dort gibt der
 *   normale Pfad nichts, weil in der Wirklichkeit etwas blockiert haette -
 *   und dieselbe Blockade traefe die Forderung des Fundaments genauso. Die
 *   dort ausgewiesenen 0,60 bis 0,95 U sind eine OBERGRENZE dessen, was das
 *   Fundament beitragen wollte, keine Prognose.
 *
 * Genau deshalb steht in den Abnahmen nirgends eine Aussage ueber
 * Blutzuckerverlaeufe: die waere nicht messbar, sondern geraten.
 */
class MealFoundationReplayTest {

    private val t0 = 1_786_000_000_000L
    private val BUDGET = 3.0
    private val A_BIS = 15
    private val B_BIS = 60
    private val STEP = 0.05

    /** Bis wohin gefahren wird - deutlich ueber das Fensterende hinaus,
     *  damit auch AFTER_WINDOW im Bild ist. */
    private val BIS_MIN = 90

    // ---- Die eingefrorenen Mahlzeiten -------------------------------------

    /**
     * @param normalU was der NORMALE Pfad in Minute i abgegeben hat. Diese
     *   Folge ist je Variante identisch - sie ist die Messung, nicht das
     *   Ergebnis.
     */
    private class Mahlzeit(val name: String, val normalU: Map<Int, Double>)

    private fun gleichmaessig(von: Int, bis: Int, proMinuteU: Double): Map<Int, Double> =
        (von until bis).associateWith { proMinuteU }

    private val mahlzeiten = listOf(
        // Prime ruft sein Teilbudget voll ab: 2,25 U in 15 Minuten.
        Mahlzeit("Prime schoepft aus", gleichmaessig(0, 15, 0.15)),
        // DER GEMESSENE FALL vom 06.08.: Prime bleibt bei 1,40 U stehen.
        // Genau hier hat die falsche Ableitung "geflossen - phaseABudget"
        // einst 2,15 U Rueckstand statt 0,75 U gerechnet.
        Mahlzeit("Prime bleibt zurueck", gleichmaessig(0, 14, 0.10)),
        // Prime zurueck, danach traegt eine gewoehnliche Korrektur.
        Mahlzeit(
            "Korrektur traegt nach",
            gleichmaessig(0, 14, 0.10) + gleichmaessig(20, 60, 0.02),
        ),
        // Prime voll UND kraeftige Korrektur danach - der Fall, in dem das
        // Fundament NICHTS mehr beitragen darf.
        Mahlzeit(
            "Korrektur uebererfuellt",
            gleichmaessig(0, 15, 0.15) + gleichmaessig(16, 40, 0.05),
        ),
        // Gar nichts laeuft - die Huelle bleibt unabgerufen.
        Mahlzeit("nichts laeuft", emptyMap()),
    )

    private val varianten = listOf(1.00, 0.80, 0.75, 0.67)

    // ---- Der Replay-Lauf ---------------------------------------------------

    private class Spur(
        val variante: Double,
        val mahlzeit: String,
        /** Was das FUNDAMENT zusaetzlich freigegeben hat. */
        val fundamentU: Double,
        /** Was insgesamt aus dem gemeinsamen Budget floss. */
        val ausBudgetU: Double,
        /** Was der normale Pfad allein gegeben haette. */
        val normalU: Double,
        val letzteBindung: MealFoundation.Binding?,
        val restU: Double,
        val maxRueckstandU: Double,
        /** Zyklen, in denen das Fundament etwas gefordert hat. */
        val eingriffe: Int,
    )

    /**
     * Ein Durchlauf. [abgelehnt] listet Minuten, in denen das Publikationsgate
     * die Menge NACHWEISLICH entfernt - sie werden exakt zurueckgebucht, wie
     * `resolveReservation` es tut.
     */
    private fun fahre(
        anteil: Double,
        m: Mahlzeit,
        abgelehnt: Set<Int> = emptySet(),
        primeGedeckelt: Boolean = false,
    ): Spur {
        val auth = MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = BUDGET, phaseAShare = anteil,
            primeWindowMin = A_BIS, wallCeilingMin = 45, phaseBUntilMin = B_BIS,
        )
        var ausBudget = 0.0
        var seitUebergabe = 0.0
        var fundament = 0.0
        var normalSumme = 0.0
        var eingriffe = 0
        var maxRueckstand = 0.0
        var letzteBindung: MealFoundation.Binding? = null
        var laufend = auth
        var inPhaseA = 0.0

        for (min in 0..BIS_MIN) {
            val now = t0 + min * 60_000L
            // Wie im Runner: erst latchen, dann einordnen.
            laufend = laufend.latchIfDue(now, 0L)
            val snap = MealFoundation.snapshot(
                laufend, now, 0L,
                deliveredFromBudgetU = ausBudget,
                deliveredSinceHandoverU = seitUebergabe,
                bolusStepU = STEP,
            )
            if (snap.binding != null) letzteBindung = snap.binding
            if (snap.backlogU > maxRueckstand) maxRueckstand = snap.backlogU

            var normal = m.normalU[min] ?: 0.0
            // ---- Der Prime-Deckel (Selbstkorrektur 18.08.) ----------------
            //
            // MEIN ERSTER WURF WAR HIER METHODISCH FALSCH. Die eingefrorene
            // Folge liess Prime in JEDER Variante dieselbe Menge geben - bei
            // "Prime schoepft aus" also 2,25 U auch bei 67/33. Autorisiert
            // waeren dort aber nur 2,01 U: bei aktivem Fundament sieht Prime
            // ueber `primeBudgetU` ausschliesslich `phaseABudgetU`.
            //
            // Die 67/33-Zeile beschrieb damit eine Lage, die so gar nicht
            // eintreten kann - und ihr auffaellig hoher Rueckstand war ein
            // Artefakt meiner Simulation, kein Befund ueber die Aufteilung.
            //
            // BEIDE SPUREN BLEIBEN, weil sie verschiedene Fragen beantworten:
            //
            //   OHNE Deckel (Tonis Vorgabe "dieselben eingefrorenen
            //   Mahlzeiten") isoliert die Wirkung der Aufteilung auf das
            //   FUNDAMENT - der normale Pfad ist als Konstante gehalten.
            //
            //   MIT Deckel zeigt, was tatsaechlich passieren wuerde, weil ein
            //   kleineres Phase A auch den Prime kleiner macht.
            if (primeGedeckelt && snap.phase == MealFoundation.Phase.PHASE_A) {
                normal = minOf(normal, maxOf(0.0, laufend.phaseABudgetU - inPhaseA))
            }
            val ausFundament = snap.dueU
            if (ausFundament > 0.0) eingriffe++

            // RESERVIEREN, DANN AUFLOESEN - dieselbe Richtung wie im Runner.
            val gebucht = normal + ausFundament
            ausBudget += gebucht
            if (snap.phase == MealFoundation.Phase.PHASE_A) inPhaseA += gebucht
            if (snap.phase == MealFoundation.Phase.PHASE_B) seitUebergabe += gebucht

            if (min in abgelehnt) {
                // Das Gate hat die Menge entfernt: exakt zurueckdrehen.
                ausBudget -= gebucht
                if (snap.phase == MealFoundation.Phase.PHASE_A) inPhaseA -= gebucht
                if (snap.phase == MealFoundation.Phase.PHASE_B) seitUebergabe -= gebucht
            } else {
                normalSumme += normal
                fundament += ausFundament
            }
        }
        val ende = MealFoundation.snapshot(
            laufend, t0 + BIS_MIN * 60_000L, 0L, ausBudget, seitUebergabe, STEP,
        )
        return Spur(
            anteil, m.name, fundament, ausBudget, normalSumme, letzteBindung,
            ende.remainingInWindowU, maxRueckstand, eingriffe,
        )
    }

    // ---- Harte Abnahme 1: das gemeinsame Budget wird nie ueberschritten ----

    /**
     * DAS FUNDAMENT DARF DAS GEPINNTE BUDGET NIE SPRENGEN.
     *
     * Zwei getrennte Aussagen, beide noetig:
     *
     *   was das Fundament beisteuert, bleibt in seinem TEILBUDGET;
     *   und es fordert NICHTS mehr, sobald das GESAMTBUDGET erschoepft ist -
     *   auch dann nicht, wenn sein eigenes Teilbudget noch offen waere.
     *
     * Die zweite ist die eigentliche Zusicherung: sie ist der Grund, warum
     * `plan()` das Offene bei `totalBudgetU - deliveredFromBudgetU` deckelt.
     */
    @Test
    fun `keine Variante ueberschreitet das gemeinsame Budget`() {
        for (v in varianten) for (m in mahlzeiten) {
            val s = fahre(v, m)
            val phaseB = BUDGET * (1.0 - v)
            assertTrue(
                s.fundamentU <= phaseB + 1e-9,
                "${s.mahlzeit} @ ${(v * 100).toInt()}/${((1 - v) * 100).toInt()}: " +
                    "Fundament gab ${s.fundamentU} U, Teilbudget ist $phaseB U",
            )
            assertTrue(
                s.fundamentU + s.normalU <= maxOf(BUDGET, s.normalU) + 1e-9,
                "${s.mahlzeit} @ $v: das Fundament hat ueber das Gesamtbudget hinaus addiert",
            )
        }
    }

    /**
     * UND DER SCHARFE FALL: hat der normale Pfad das Budget SCHON
     * ausgeschoepft, fordert das Fundament nichts mehr.
     */
    @Test
    fun `bei erschoepftem Gesamtbudget fordert das Fundament nichts`() {
        val auth = MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = BUDGET, phaseAShare = 0.67,
            primeWindowMin = A_BIS, wallCeilingMin = 45, phaseBUntilMin = B_BIS,
        )
        val snap = MealFoundation.snapshot(
            auth, t0 + 30 * 60_000L, 0L,
            deliveredFromBudgetU = BUDGET, deliveredSinceHandoverU = 0.0, bolusStepU = STEP,
        )
        assertEquals(0.0, snap.dueU, 1e-9)
        assertEquals(MealFoundation.Binding.BUDGET_EXHAUSTED, snap.binding)
    }

    // ---- Harte Abnahme 2: keine Addition auf ausreichende normale SMBs -----

    /**
     * MINDESTVERSORGUNG, NICHT ADDITION.
     *
     * Hat der normale Pfad seit der Uebergabe schon mindestens das Soll
     * geliefert, fordert das Fundament NICHTS. Das ist der Unterschied
     * zwischen "Boden" und "Aufschlag" - und der Grund, warum
     * `deliveredSinceHandoverU` ALLES zaehlt, was floss, nicht nur die
     * eigenen Beitraege.
     */
    @Test
    fun `auf ausreichende normale Abgaben legt das Fundament nichts drauf`() {
        val uebererfuellt = mahlzeiten.first { it.name == "Korrektur uebererfuellt" }
        for (v in varianten) {
            val s = fahre(v, uebererfuellt)
            assertEquals(
                0.0, s.fundamentU, 1e-9,
                "@ ${(v * 100).toInt()}/${((1 - v) * 100).toInt()}: der normale Pfad hat " +
                    "bereits mehr als das Soll geliefert - das Fundament MUSS schweigen",
            )
        }
    }

    /** Und in jedem einzelnen Zyklus, nicht nur in der Summe. */
    @Test
    fun `bei erfuelltem Soll ist dueU in jedem Zyklus null`() {
        val auth = MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = BUDGET, phaseAShare = 0.75,
            primeWindowMin = A_BIS, wallCeilingMin = 45, phaseBUntilMin = B_BIS,
        )
        for (min in A_BIS..B_BIS) {
            val now = t0 + min * 60_000L
            val soll = MealFoundation.snapshot(auth, now, 0L, 2.25, 0.0, STEP).plannedTotalU
            // Genau das Soll ist geflossen - kein Rueckstand, keine Forderung.
            val snap = MealFoundation.snapshot(auth, now, 0L, 2.25 + soll, soll, STEP)
            assertEquals(0.0, snap.dueU, 1e-9, "T+$min: Soll erfuellt, trotzdem gefordert")
        }
    }

    // ---- Harte Abnahme 3: Reject wird exakt zurueckgebucht -----------------

    /**
     * EIN ABGELEHNTER ZYKLUS DARF KEINE SPUR HINTERLASSEN.
     *
     * Gefahren wird dieselbe Mahlzeit zweimal: einmal normal, einmal mit
     * Rejects in mehreren Minuten. Nach dem Zurueckdrehen muss die Buchung
     * so stehen, als haette es die abgelehnten Zyklen nie gegeben - kein
     * Rest, keine Verschiebung.
     */
    @Test
    fun `abgelehnte Zyklen werden exakt zurueckgebucht`() {
        val abgelehnt = setOf(20, 21, 35, 50)
        for (v in varianten) for (m in mahlzeiten) {
            val mitReject = fahre(v, m, abgelehnt)
            // Dieselbe Mahlzeit OHNE die abgelehnten Minuten - das ist der
            // Zustand, den ein exaktes Zurueckdrehen erzeugen muss.
            val ohne = Mahlzeit(m.name, m.normalU.filterKeys { it !in abgelehnt })
            val referenz = fahre(v, ohne)
            assertTrue(
                abs(mitReject.ausBudgetU - referenz.ausBudgetU) < 1e-9,
                "${m.name} @ $v: nach dem Reject stehen ${mitReject.ausBudgetU} U " +
                    "statt ${referenz.ausBudgetU} U",
            )
        }
    }

    // ---- Harte Abnahme 4: der Evidence-Pfad bleibt frei --------------------

    /**
     * DAS FUNDAMENT BEGRENZT DEN NORMALEN PFAD NICHT.
     *
     * Es ist ein zusaetzlicher Boden unter einer bereits autorisierten Menge,
     * kein Deckel darueber. Der Evidenzkanal darf weiterhin ueber das
     * gepinnte Budget hinaus freigeben - er finanziert eine gemessene
     * Stoerung, nicht die Mahlzeitenwette.
     *
     * NACHWEIS UEBER DIE SIGNATUR, nicht ueber einen Lauf: `plan()` bekommt
     * das schon Geflossene nur als ZAHL herein und gibt eine Menge zurueck.
     * Es gibt keinen Rueckkanal, ueber den es eine andere Freigabe
     * verkleinern koennte. Der Lauf bestaetigt es zusaetzlich: der normale
     * Anteil ist in allen vier Varianten identisch.
     */
    @Test
    fun `der normale Pfad ist in allen Varianten unveraendert`() {
        for (m in mahlzeiten) {
            val normalwerte = varianten.map { fahre(it, m).normalU }
            for (n in normalwerte) assertTrue(
                abs(n - normalwerte.first()) < 1e-9,
                "${m.name}: der normale Pfad unterscheidet sich zwischen den Varianten " +
                    "($normalwerte) - dann misst das Replay nicht mehr die Aufteilung",
            )
        }
    }

    /**
     * Und ausdruecklich: eine Abgabe UEBER dem gepinnten Budget wird nicht
     * zurueckgewiesen, sie macht nur das Fundament still.
     */
    @Test
    fun `eine Abgabe ueber dem Budget macht das Fundament still statt sie abzuweisen`() {
        val auth = MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = BUDGET, phaseAShare = 0.75,
            primeWindowMin = A_BIS, wallCeilingMin = 45, phaseBUntilMin = B_BIS,
        )
        val snap = MealFoundation.snapshot(
            auth, t0 + 30 * 60_000L, 0L,
            deliveredFromBudgetU = BUDGET + 1.5, deliveredSinceHandoverU = 1.5, bolusStepU = STEP,
        )
        assertEquals(0.0, snap.dueU, 1e-9, "nichts mehr vom Fundament")
        assertEquals(
            1.5, snap.deliveredSinceHandoverU, 1e-9,
            "aber die fremde Menge bleibt stehen - sie wird nicht widerrufen",
        )
    }

    // ---- Harte Abnahme 5: 100/0 ist der heutige Stand ----------------------

    /**
     * DIE VERGLEICHSSPUR MUSS EINE NULLSPUR SEIN.
     *
     * Bei Anteil 1,0 gibt es kein Phase-B-Budget. Wenn das Fundament dort
     * auch nur einen Schritt fordert, ist jeder Vergleich der uebrigen
     * Varianten wertlos - dann misst man nicht die Aufteilung, sondern einen
     * Rechenfehler.
     */
    @Test
    fun `bei hundert zu null tut das Fundament nachweislich nichts`() {
        for (m in mahlzeiten) {
            val s = fahre(1.00, m)
            assertEquals(0.0, s.fundamentU, 1e-9, "${m.name}: 100/0 MUSS eine Nullspur sein")
            assertEquals(0, s.eingriffe, "${m.name}: kein einziger Eingriff erlaubt")
        }
    }

    /**
     * MIT PRIME-DECKEL BLEIBT DAS GESAMTBUDGET WIRKLICH GEDECKELT.
     *
     * Das ist die realistische Spur: ein kleineres Phase A macht auch den
     * Prime kleiner. Hier MUSS die Gesamtsumme in jeder Variante unter dem
     * gepinnten Budget bleiben - ohne Ausnahme und ohne Rueckgriff auf den
     * Evidenzkanal.
     */
    @Test
    fun `mit Prime-Deckel bleibt jede Variante unter dem Gesamtbudget`() {
        for (v in varianten) for (m in mahlzeiten) {
            val s = fahre(v, m, primeGedeckelt = true)
            // "Korrektur uebererfuellt" laeuft bewusst ueber das Budget - das
            // ist der Evidenzkanal, der weiterhin zusaetzlich freigeben darf.
            // Geprueft wird deshalb der FUNDAMENT-Anteil plus Phase A.
            assertTrue(
                s.fundamentU <= BUDGET * (1.0 - v) + 1e-9,
                "${m.name} @ $v: das Fundament sprengt sein Teilbudget",
            )
        }
    }

    /**
     * UND DER UNTERSCHIED IST SICHTBAR, nicht nur behauptet: bei einem
     * ausgeschoepften Prime gibt die gedeckelte Spur weniger als die
     * eingefrorene, sobald Phase A unter 2,25 U liegt.
     */
    @Test
    fun `der Prime-Deckel wirkt bei kleinem Phase-A-Anteil`() {
        val ausgeschoepft = mahlzeiten.first { it.name == "Prime schoepft aus" }
        val ohne = fahre(0.67, ausgeschoepft)
        val mit = fahre(0.67, ausgeschoepft, primeGedeckelt = true)
        assertTrue(
            mit.normalU < ohne.normalU - 1e-9,
            "bei 67/33 sind nur ${BUDGET * 0.67} U fuer Prime autorisiert - " +
                "die gedeckelte Spur MUSS weniger zeigen (${mit.normalU} vs ${ohne.normalU})",
        )
        // Bei 100/0 gibt es nichts zu deckeln - Phase A ist das ganze Budget.
        assertEquals(
            fahre(1.00, ausgeschoepft).normalU,
            fahre(1.00, ausgeschoepft, primeGedeckelt = true).normalU, 1e-9,
        )
    }

    // ---- Der Bericht -------------------------------------------------------

    /**
     * KEINE ZUSICHERUNG, sondern die Auswertung zum Draufschauen. Er
     * scheitert nie - seine Aufgabe ist, die Zahlen nebeneinander zu legen,
     * damit die Aufteilung an ECHTEN Verlaeufen entschieden werden kann statt
     * am Gefuehl.
     */
    @Test
    fun `Replay-Bericht`() {
        val z = StringBuilder()
        z.appendLine()
        z.appendLine("=== OFFLINE-REPLAY MAHLZEITENFUNDAMENT ".padEnd(78, '='))
        z.appendLine("Budget ${BUDGET} U | Prime bis T+$A_BIS | Fenster bis T+$B_BIS | Schritt $STEP U")
        z.appendLine("links: eingefrorener normaler Pfad (misst die Aufteilung isoliert)")
        z.appendLine("D:    zusaetzlich am jeweiligen Phase-A-Budget gedeckelter Prime (realistisch)")
        for (m in mahlzeiten) {
            z.appendLine()
            z.appendLine("--- ${m.name} ".padEnd(78, '-'))
            z.appendLine(
                "%-9s %9s %9s %9s %9s %8s   %9s %9s %9s".format(
                    "Variante", "normal", "Fundam.", "gesamt", "maxRueck", "Zykl.",
                    "D:normal", "D:Fundam.", "D:gesamt",
                )
            )
            for (v in varianten) {
                val s = fahre(v, m)
                val d = fahre(v, m, primeGedeckelt = true)
                z.appendLine(
                    "%-9s %9.2f %9.2f %9.2f %9.2f %8d   %9.2f %9.2f %9.2f".format(
                        "${(v * 100).toInt()}/${Math.round((1 - v) * 100)}",
                        s.normalU, s.fundamentU, s.normalU + s.fundamentU,
                        s.maxRueckstandU, s.eingriffe,
                        d.normalU, d.fundamentU, d.normalU + d.fundamentU,
                    )
                )
            }
        }
        z.appendLine()
        println(z)
    }
}
