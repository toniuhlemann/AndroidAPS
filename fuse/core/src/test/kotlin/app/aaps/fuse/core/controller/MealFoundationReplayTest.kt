package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * OFFLINE-REPLAY DES MAHLZEITENFUNDAMENTS (Punkt 12, Toni 18.08.).
 *
 * Vier Aufteilungen - 100/0, 80/20, 75/25, 67/33 - gegen dieselben
 * eingefrorenen Mahlzeiten. 100/0 ist der heutige Stand und damit die
 * Vergleichsspur: bei ihr muss das Fundament nachweislich nichts tun.
 *
 * ZWEI FEHLER DES ERSTEN WURFS, beide von Toni gefunden, beide hier
 * korrigiert - sie sind der Grund, warum diese Datei so ausfuehrlich
 * kommentiert ist:
 *
 *   (1) FALSCHE ZYKLUSREIHENFOLGE UND ADDITION. Das Replay bildete
 *       `normal + dueU` - genau den additiven Bolus, den die
 *       Mindestversorgung verbietet. Richtig ist der BODEN:
 *       `final = max(normal, dueU)`, siehe [MealFoundation.contribute].
 *       Danach laufen die gemeinsamen Gates GENAU EINMAL, und gebucht wird
 *       nur die publizierte Menge.
 *
 *   (2) DER DECKEL TRAF DAS FALSCHE. Gekappt wurde die gesamte normale
 *       Spur am Phase-A-Budget. Gedeckelt gehoert aber NUR Prime - der
 *       sieht bei aktivem Fundament ueber `primeBudgetU` ausschliesslich
 *       `phaseABudgetU`. Korrektur- und Evidenzinsulin darf weiterhin
 *       ZUSAETZLICH zu den 3 U entstehen; alles andere widerspraeche dem
 *       bestaetigten Vertrag. Die Spuren sind deshalb getrennt.
 *
 * WAS SIMULIERT WIRD UND WAS NICHT. Simuliert werden Zeitverlauf und
 * Buchfuehrung. NICHT simuliert werden Regelentscheidungen und Gates: was
 * Prime und der Evidenzkanal abgeben, ist EINGEFROREN. Genau das macht den
 * Vergleich aussagekraeftig - der einzige Unterschied zwischen den Spuren
 * ist die Aufteilung.
 *
 * DIE GRENZE, ausdruecklich: `dueU` ist eine FORDERUNG, keine garantierte
 * Abgabe. Zwischen ihr und der Pumpe liegen unveraendert alle nachgelagerten
 * Gates - Signalqualitaet, gemessenes Tief, maxSMB, iobTH, maxIOB,
 * Transport, Ledger und Pumpengate. Das Replay sagt, was das Fundament
 * VERLANGT. Ob es fliesst, entscheidet ein vollstaendiger Runner-Replay,
 * der noch aussteht. Aussagen ueber Blutzuckerverlaeufe stehen hier
 * nirgends - die waeren geraten, nicht gemessen.
 *
 * Die Mahlzeitenformen sind aus den eigenen Geraetelaeufen ABGELEITET (die
 * Rohdaten gehoeren nicht ins Repository).
 */
class MealFoundationReplayTest {

    private val t0 = 1_786_000_000_000L
    private val BUDGET = 3.0
    private val A_BIS = 15
    private val B_BIS = 60
    private val STEP = 0.05
    private val BIS_MIN = 90

    // ---- Die eingefrorenen Mahlzeiten -------------------------------------

    /**
     * ZWEI GETRENNTE SPUREN, und die Trennung ist der ganze Punkt:
     *
     * @param primeU was PRIME in Minute i abgeben wollte. Diese Spur wird am
     *   jeweiligen Phase-A-Budget gedeckelt - bei aktivem Fundament sieht
     *   Prime ueber [MealFoundation.primeBudgetU] nur `phaseABudgetU`. Ein
     *   kleineres Phase A macht also auch den Prime kleiner.
     *
     * @param evidenzU was der EVIDENZ-/KORREKTURKANAL abgab. Diese Spur ist
     *   in allen Varianten identisch und wird NICHT gedeckelt: sie finanziert
     *   eine gemessene Stoerung, nicht die Mahlzeitenwette, und darf
     *   ausdruecklich ueber das gepinnte Budget hinausgehen.
     */
    private class Mahlzeit(
        val name: String,
        val primeU: Map<Int, Double> = emptyMap(),
        val evidenzU: Map<Int, Double> = emptyMap(),
    )

    private fun gleichmaessig(von: Int, bis: Int, proMinuteU: Double): Map<Int, Double> =
        (von until bis).associateWith { proMinuteU }

    private val mahlzeiten = listOf(
        Mahlzeit("Prime schoepft aus", primeU = gleichmaessig(0, 15, 0.15)),
        // DER GEMESSENE FALL vom 06.08.: Prime bleibt bei 1,40 U stehen -
        // unter jedem Phase-A-Budget, also von der Deckelung unberuehrt. Das
        // macht ihn zur saubersten Vergleichsmahlzeit.
        Mahlzeit("Prime bleibt zurueck", primeU = gleichmaessig(0, 14, 0.10)),
        Mahlzeit(
            "Korrektur traegt nach",
            primeU = gleichmaessig(0, 14, 0.10),
            evidenzU = gleichmaessig(20, 60, 0.02),
        ),
        Mahlzeit(
            "Korrektur uebererfuellt",
            primeU = gleichmaessig(0, 15, 0.15),
            evidenzU = gleichmaessig(16, 40, 0.05),
        ),
        Mahlzeit("nichts laeuft"),
    )

    private val varianten = listOf(1.00, 0.80, 0.75, 0.67)

    // ---- Der Replay-Lauf ---------------------------------------------------

    private class Spur(
        val primeU: Double,
        val evidenzU: Double,
        /** Was das FUNDAMENT ueber den normalen Vorschlag hinaus beisteuerte. */
        val fundamentU: Double,
        /** Was insgesamt publiziert wurde. */
        val publiziertU: Double,
        val letzteBindung: MealFoundation.Binding?,
        val maxRueckstandU: Double,
        val eingriffe: Int,
        /** Der gebuchte Stand je Minute - fuer die Reject-Probe. */
        val stand: List<Double>,
    )

    /**
     * Ein Durchlauf in der REIHENFOLGE DES RUNNERS.
     *
     *   1. latchen (wie `buche` es zuerst tut)
     *   2. den Plan holen - VOR der Entscheidung, denn seine Forderung geht
     *      ja in sie ein
     *   3. `contribute`: der Boden, keine Addition
     *   4. Gates (hier nicht simuliert) - EINMAL ueber den finalen Kandidaten
     *   5. buchen, was publiziert wurde
     *
     * Schritt 2 und der Export-Snapshot sind ZWEI Auswertungen desselben
     * Zustands zu verschiedenen Zeitpunkten im Zyklus. Der Runner erzeugt
     * heute nur die fuer den Export (nach `buche`); die Verdrahtung braucht
     * zusaetzlich die fuer die Entscheidung (davor). Das ist eine Auflage an
     * die Verdrahtung, kein Mangel des Replays.
     *
     * @param abgelehnt Minuten, in denen das Publikationsgate die Menge
     *   NACHWEISLICH entfernt - dann wird nichts gebucht.
     */
    private fun fahre(anteil: Double, m: Mahlzeit, abgelehnt: Set<Int> = emptySet()): Spur {
        val auth = MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = BUDGET, phaseAShare = anteil,
            primeWindowMin = A_BIS, wallCeilingMin = 45, pressObservedInThisProcess = true, primeDeclinedByUser = false, markerAuthorized = true, phaseBUntilMin = B_BIS,
        )
        var ausBudget = 0.0
        var seitUebergabe = 0.0
        var primeVerbraucht = 0.0
        var primeSumme = 0.0
        var evidenzSumme = 0.0
        var fundamentSumme = 0.0
        var publiziertSumme = 0.0
        var eingriffe = 0
        var maxRueckstand = 0.0
        var letzteBindung: MealFoundation.Binding? = null
        var laufend = auth
        val stand = mutableListOf<Double>()

        for (min in 0..BIS_MIN) {
            val now = t0 + min * 60_000L
            laufend = laufend.latchIfDue(now, 0L)

            // (2) Die Forderung - VOR der Entscheidung.
            val vorher = MealFoundation.snapshot(
                laufend, now, 0L,
                deliveredFromBudgetU = ausBudget,
                deliveredSinceHandoverU = seitUebergabe,
                deliveredPhaseAU = ausBudget - seitUebergabe,
                confirmedNotSentPhaseAU = 0.0,
                descentDeferredPhaseAU = 0.0,
                descentCarryEligibility = DescentDeferredCarry.Eligibility.NO_DEFERRED,
                bolusStepU = STEP,
            )

            // PRIME wird am gepinnten Phase-A-Budget gedeckelt - das ist die
            // Wirklichkeit, nicht eine Variante: primeBudgetU() gibt ihm bei
            // aktivem Fundament genau phaseABudgetU.
            val primeWunsch = m.primeU[min] ?: 0.0
            val prime = minOf(primeWunsch, max(0.0, laufend.phaseABudgetU - primeVerbraucht))
            // EVIDENZ bleibt ungedeckelt - sie finanziert eine gemessene
            // Stoerung und darf ueber das gepinnte Budget hinaus.
            val evidenz = m.evidenzU[min] ?: 0.0
            val normalerKandidat = prime + evidenz

            // (3) DER BODEN, KEINE ADDITION.
            val beitrag = MealFoundation.contribute(normalerKandidat, vorher.dueU)
            if (vorher.backlogU > maxRueckstand) maxRueckstand = vorher.backlogU
            if (vorher.binding != null) letzteBindung = vorher.binding

            // (4) Gates liefen hier - genau einmal ueber den finalen
            // Kandidaten. (5) Gebucht wird nur, was publiziert wurde.
            if (min in abgelehnt) {
                stand.add(ausBudget)
                continue
            }
            if (beitrag.requestedFoundationU > 0.0) eingriffe++
            val publiziert = beitrag.finalCandidateU
            ausBudget += publiziert
            primeVerbraucht += prime
            if (vorher.phase == MealFoundation.Phase.PHASE_B) seitUebergabe += publiziert
            primeSumme += prime
            evidenzSumme += evidenz
            fundamentSumme += beitrag.requestedFoundationU
            publiziertSumme += publiziert
            stand.add(ausBudget)
        }
        return Spur(
            primeSumme, evidenzSumme, fundamentSumme, publiziertSumme,
            letzteBindung, maxRueckstand, eingriffe, stand,
        )
    }

    // ---- Abnahme 1: kein additiver Bolus ------------------------------------

    /**
     * DER FEHLER, DEN TONI GEFUNDEN HAT - jetzt als Zusicherung.
     *
     * Verlangen normaler Pfad und Fundament im selben Zyklus je 0,05 U, ist
     * das Ergebnis 0,05 U und NICHT 0,10 U. Der Beitrag des Fundaments ist
     * dann null: der Boden lag schon.
     */
    @Test
    fun `das Fundament addiert nicht auf den normalen Vorschlag`() {
        val gleich = MealFoundation.contribute(0.05, 0.05)
        assertEquals(0.05, gleich.finalCandidateU, 1e-9, "MUSS der Boden sein, nicht die Summe")
        assertEquals(0.0, gleich.requestedFoundationU, 1e-9)

        val normalHoeher = MealFoundation.contribute(0.15, 0.05)
        assertEquals(0.15, normalHoeher.finalCandidateU, 1e-9)
        assertEquals(0.0, normalHoeher.requestedFoundationU, 1e-9, "der normale Pfad traegt allein")

        val fundamentHoeher = MealFoundation.contribute(0.02, 0.05)
        assertEquals(0.05, fundamentHoeher.finalCandidateU, 1e-9)
        assertEquals(
            0.03, fundamentHoeher.requestedFoundationU, 1e-9,
            "nur die DIFFERENZ ist Beitrag des Fundaments - der Rest ist fremdes Verdienst",
        )
    }

    /**
     * NICHT BERECHENBAR IST NICHT NULL (Toni 18.08., P0).
     *
     * DIESER TEST HAT DEN FEHLER FESTGESCHRIEBEN. Seine letzte Zeile lautete
     * `assertEquals(0.05, contribute(NaN, 0.05).finalCandidateU)` - er
     * verlangte also ausdruecklich, dass ein UNBERECHENBARER normaler
     * Kandidat eine Fundamentdosis von 0,05 U erzeugt. Aus "ich weiss nicht,
     * wo dieser Zyklus steht" wurde "gib etwas".
     *
     * Die 0 bleibt der sauberen Aussage vorbehalten, die sie schon traegt:
     * NO_DEMAND, also kein Bedarf. Ein NaN ist etwas anderes und muss
     * fail-closed sein.
     */
    @Test
    fun `ein unberechenbarer Kandidat erzeugt keine Dosis`() {
        for (kaputt in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.5)) {
            val c = MealFoundation.contribute(kaputt, 0.05)
            assertEquals(0.0, c.finalCandidateU, 1e-9, "$kaputt darf nichts freigeben")
            assertEquals(0.0, c.requestedFoundationU, 1e-9, "$kaputt darf nichts anfordern")
            assertTrue(!c.usable, "$kaputt MUSS als unbrauchbar gemeldet werden")
        }
    }

    /**
     * DIE GEGENRICHTUNG: ein unbrauchbares SOLL laesst den normalen Vorschlag
     * unangetastet. Hier ist die Fehlerrichtung umgekehrt - das Fundament
     * schweigt, der bestehende Pfad laeuft weiter wie ohne Fundament.
     */
    @Test
    fun `ein unbrauchbares Soll laesst den normalen Vorschlag stehen`() {
        for (kaputt in listOf(Double.NaN, -1.0, Double.POSITIVE_INFINITY)) {
            val c = MealFoundation.contribute(0.10, kaputt)
            assertEquals(0.10, c.finalCandidateU, 1e-9, "Soll $kaputt")
            assertEquals(0.0, c.requestedFoundationU, 1e-9)
            assertTrue(c.usable, "der KANDIDAT war ja in Ordnung")
        }
    }

    /** Und die echte Null bleibt eine gueltige Aussage - kein Bedarf. */
    @Test
    fun `ein Kandidat von null ist brauchbar und kein Fehler`() {
        val c = MealFoundation.contribute(0.0, 0.05)
        assertTrue(c.usable, "0 heisst NO_DEMAND, nicht unberechenbar")
        assertEquals(0.05, c.finalCandidateU, 1e-9)
        assertEquals(0.05, c.requestedFoundationU, 1e-9)
    }

    // ---- Abnahme 2: das gemeinsame Budget ----------------------------------

    /**
     * DAS FUNDAMENT BLEIBT IN SEINEM TEILBUDGET - und fordert nichts mehr,
     * sobald das GESAMTBUDGET erschoepft ist, auch wenn sein eigenes noch
     * offen waere.
     */
    @Test
    fun `keine Variante ueberschreitet das Teilbudget des Fundaments`() {
        for (v in varianten) for (m in mahlzeiten) {
            val s = fahre(v, m)
            assertTrue(
                s.fundamentU <= BUDGET * (1.0 - v) + 1e-9,
                "${m.name} @ ${(v * 100).toInt()}: Fundament gab ${s.fundamentU} U, " +
                    "Teilbudget ist ${BUDGET * (1.0 - v)} U",
            )
        }
    }

    @Test
    fun `bei erschoepftem Gesamtbudget fordert das Fundament nichts`() {
        val auth = MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = BUDGET, phaseAShare = 0.67,
            primeWindowMin = A_BIS, wallCeilingMin = 45, pressObservedInThisProcess = true, primeDeclinedByUser = false, markerAuthorized = true, phaseBUntilMin = B_BIS,
        )
        val snap = MealFoundation.snapshot(
            auth, t0 + 30 * 60_000L, 0L, BUDGET, 0.0, BUDGET, 0.0,
            0.0, DescentDeferredCarry.Eligibility.NO_DEFERRED, STEP,
        )
        assertEquals(0.0, snap.dueU, 1e-9)
        assertEquals(MealFoundation.Binding.BUDGET_EXHAUSTED, snap.binding)
    }

    /**
     * PRIME WIRD GEDECKELT, EVIDENZ NICHT.
     *
     * Das ist der Vertrag, den das erste Replay verletzt hat. Ein kleineres
     * Phase A macht den Prime kleiner - aber der Evidenzkanal bleibt in jeder
     * Variante gleich gross und darf ueber die 3 U hinaus.
     */
    @Test
    fun `Prime folgt dem Phase-A-Budget, Evidenz bleibt unberuehrt`() {
        val ausgeschoepft = mahlzeiten.first { it.name == "Prime schoepft aus" }
        assertEquals(2.25, fahre(1.00, ausgeschoepft).primeU, 1e-9, "100/0: nichts zu deckeln")
        assertEquals(2.01, fahre(0.67, ausgeschoepft).primeU, 1e-9, "67/33: Prime sieht nur 2,01 U")

        val mitEvidenz = mahlzeiten.first { it.name == "Korrektur uebererfuellt" }
        val evidenzwerte = varianten.map { fahre(it, mitEvidenz).evidenzU }
        for (e in evidenzwerte) assertTrue(
            abs(e - evidenzwerte.first()) < 1e-9,
            "die Evidenzspur MUSS in allen Varianten gleich sein, war $evidenzwerte",
        )
        assertTrue(
            evidenzwerte.first() > 0.0 && fahre(0.67, mitEvidenz).publiziertU > BUDGET,
            "und sie darf das gepinnte Budget ueberschreiten - sonst waere sie gedeckelt",
        )
    }

    // ---- Abnahme 3: Mindestversorgung statt Aufschlag -----------------------

    @Test
    fun `auf ausreichende normale Abgaben legt das Fundament nichts drauf`() {
        val uebererfuellt = mahlzeiten.first { it.name == "Korrektur uebererfuellt" }
        for (v in varianten) {
            val s = fahre(v, uebererfuellt)
            assertEquals(
                0.0, s.fundamentU, 1e-9,
                "@ ${(v * 100).toInt()}: der normale Pfad liefert bereits mehr als das Soll",
            )
        }
    }

    @Test
    fun `bei erfuelltem Soll ist dueU in jedem Zyklus null`() {
        val auth = MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = BUDGET, phaseAShare = 0.75,
            primeWindowMin = A_BIS, wallCeilingMin = 45, pressObservedInThisProcess = true, primeDeclinedByUser = false, markerAuthorized = true, phaseBUntilMin = B_BIS,
        )
        for (min in A_BIS..B_BIS) {
            val now = t0 + min * 60_000L
            val soll = MealFoundation.snapshot(
                auth, now, 0L, 2.25, 0.0, 2.25, 0.0,
                0.0, DescentDeferredCarry.Eligibility.NO_DEFERRED, STEP,
            ).plannedTotalU
            val snap = MealFoundation.snapshot(
                auth, now, 0L, 2.25 + soll, soll, 2.25, 0.0,
                0.0, DescentDeferredCarry.Eligibility.NO_DEFERRED, STEP,
            )
            assertEquals(0.0, snap.dueU, 1e-9, "T+$min: Soll erfuellt, trotzdem gefordert")
        }
    }

    // ---- Abnahme 4: Reject hinterlaesst keine Spur --------------------------

    /**
     * EIN ABGELEHNTER ZYKLUS BUCHT NICHTS.
     *
     * MEINE ERSTE FASSUNG DIESES TESTS WAR FALSCH KONSTRUIERT. Sie verglich
     * den Reject-Lauf mit einer Mahlzeit, aus der die abgelehnten Minuten
     * ENTFERNT waren - und erwartete Gleichheit. Das kann nicht stimmen: die
     * Zeit laeuft in beiden Laeufen weiter, das Fundament fordert in diesen
     * Minuten also trotzdem, und nach einem Reject holt es den entstandenen
     * Rueckstand spaeter auf. Beide Effekte sind GEWOLLT.
     *
     * Die tatsaechliche Zusicherung ist enger und pruefbar: in einer
     * abgelehnten Minute darf sich der gebuchte Stand NICHT bewegen.
     */
    @Test
    fun `in einer abgelehnten Minute bewegt sich die Buchung nicht`() {
        val abgelehnt = setOf(20, 21, 35, 50)
        for (v in varianten) for (m in mahlzeiten) {
            val s = fahre(v, m, abgelehnt)
            for (min in abgelehnt) {
                if (min == 0 || min > BIS_MIN) continue
                assertEquals(
                    s.stand[min - 1], s.stand[min], 1e-12,
                    "${m.name} @ $v, T+$min: die abgelehnte Menge wurde trotzdem gebucht",
                )
            }
        }
    }

    /**
     * UND DER RUECKSTAND WIRD DANACH AUFGEHOLT, nicht verschluckt.
     *
     * Die Gegenrichtung zum Test oben: haette ein Reject die Forderung
     * dauerhaft geloescht, waere die Mindestversorgung nach jeder Ablehnung
     * um genau diese Menge kleiner - und niemand saehe es.
     */
    @Test
    fun `nach einer Ablehnung holt das Fundament den Rueckstand auf`() {
        val leer = mahlzeiten.first { it.name == "nichts laeuft" }
        val ohne = fahre(0.75, leer)
        val mit = fahre(0.75, leer, abgelehnt = setOf(20, 21, 22))
        assertTrue(
            mit.fundamentU > ohne.fundamentU - 3 * STEP - 1e-9,
            "nach drei Ablehnungen fehlen dauerhaft ${ohne.fundamentU - mit.fundamentU} U",
        )
    }

    // ---- Abnahme 5: 100/0 ist eine Nullspur ---------------------------------

    @Test
    fun `bei hundert zu null tut das Fundament nachweislich nichts`() {
        for (m in mahlzeiten) {
            val s = fahre(1.00, m)
            assertEquals(0.0, s.fundamentU, 1e-9, "${m.name}: 100/0 MUSS eine Nullspur sein")
            assertEquals(0, s.eingriffe, "${m.name}: kein einziger Eingriff erlaubt")
        }
    }

    // ---- Der Bericht -------------------------------------------------------

    /**
     * KEINE ZUSICHERUNG, sondern die Auswertung zum Draufschauen.
     *
     * Er entscheidet die Aufteilung NICHT - dafuer fehlen die Gates. Was er
     * zeigt, ist der Rechenkern und der Zeitplan: wieviel das Fundament
     * VERLANGEN wuerde, in wievielen Zyklen, und wie weit es dabei je
     * zurueckliegt.
     */
    @Test
    fun `Replay-Bericht`() {
        val z = StringBuilder()
        z.appendLine()
        z.appendLine("=== OFFLINE-REPLAY MAHLZEITENFUNDAMENT ".padEnd(78, '='))
        z.appendLine("Budget $BUDGET U | Prime bis T+$A_BIS | Fenster bis T+$B_BIS | Schritt $STEP U")
        z.appendLine("Prime am Phase-A-Budget gedeckelt, Evidenz ungedeckelt, max-Semantik")
        for (m in mahlzeiten) {
            z.appendLine()
            z.appendLine("--- ${m.name} ".padEnd(78, '-'))
            z.appendLine(
                "%-9s %8s %8s %10s %10s %8s %8s  %s".format(
                    "Variante", "Prime", "Evidenz", "Fundament", "publiziert",
                    "maxRueck", "Zyklen", "letzte Bindung",
                )
            )
            for (v in varianten) {
                val s = fahre(v, m)
                z.appendLine(
                    "%-9s %8.2f %8.2f %10.2f %10.2f %8.2f %8d  %s".format(
                        "${(v * 100).toInt()}/${Math.round((1 - v) * 100)}",
                        s.primeU, s.evidenzU, s.fundamentU, s.publiziertU,
                        s.maxRueckstandU, s.eingriffe, s.letzteBindung?.name ?: "-",
                    )
                )
            }
        }
        z.appendLine()
        println(z)
    }

    // ==== DER AUFTEILUNGS-VERGLEICH (Replay, Toni/Codex 19.08.) ============
    //
    // EIN-VARIABLEN-DISZIPLIN: Gesamtbudget und Fenster bleiben ueber alle
    // vier Laeufe KONSTANT, variiert wird ausschliesslich der Phase-A-Anteil.
    // Genau das prueft `der Vergleich variiert nur die Aufteilung` nach -
    // sonst waere jeder Unterschied zwischen den Spuren nicht zuzuordnen.
    //
    // WAS DIESER VERGLEICH BEANTWORTEN KANN, und nur das: WANN welche Menge
    // gefordert und gebucht wird. Die Mahlzeitenformen sind eingefroren, die
    // Regelentscheidungen ebenso.
    //
    // WAS ER AUSDRUECKLICH NICHT KANN - drei der Groessen aus Codex' Liste:
    //
    //   GUARD-/TAIL-BINDUNGEN entstehen im Regler, und der ist hier nicht
    //   simuliert (s. Klassenkopf). Was hier `binding` heisst, ist
    //   ausschliesslich die FUNDAMENT-Bindung aus `plan()`.
    //
    //   DIE MODELLIERTE IOB-SPITZE braucht ein Insulinmodell. Der Kern hat
    //   eines (`UnitInsulinKernel`), es wird aber aus dem AAPS-Insulinplugin
    //   gebaut und ist in diesem Modul nicht verfuegbar. Eine eigene Kurve
    //   hier waere eine zweite Wahrheit.
    //
    //   DER EFFEKTIVE UEBERTRAG entsteht im Ledger aus einem Nicht-Sende-
    //   BEWEIS. Ihn in diesem Rig nachzubilden hiesse, die Buchhaltung ein
    //   zweites Mal zu schreiben - genau der Fehler, wegen dem der erste E2E
    //   zurueckgezogen wurde. Er gehoert in den Runner-Pfad.
    //
    // Diese drei brauchen den Runner-Replay. Hier steht, was ohne ihn
    // ehrlich messbar ist.

    /** Die Vergleichsgroessen EINER Spur. */
    private class Kennzahlen(
        val anteil: Double,
        val mahlzeit: String,
        /** Kumulativ gebucht bei T+15/30/45/60. */
        val bei: Map<Int, Double>,
        /** Laengste zusammenhaengende Strecke ohne jede Buchung [min]. */
        val leerlaufMin: Int,
        val maxRueckstandU: Double,
        val restRueckstandU: Double,
        val fundamentU: Double,
        val letzteBindung: MealFoundation.Binding?,
    )

    private fun kennzahlen(anteil: Double, m: Mahlzeit): Kennzahlen {
        val spur = fahre(anteil, m)
        // `stand` ist der KUMULATIVE Buchungsstand je Minute. Eine Minute
        // ohne Zuwachs ist eine Leerlaufminute.
        var leerlauf = 0
        var maxLeerlauf = 0
        for (i in 1 until spur.stand.size) {
            if (spur.stand[i] - spur.stand[i - 1] <= 1e-9) {
                leerlauf++
                if (leerlauf > maxLeerlauf) maxLeerlauf = leerlauf
            } else leerlauf = 0
        }
        val bei = listOf(15, 30, 45, 60).associateWith { spur.stand.getOrElse(it) { spur.stand.last() } }
        // Der Rueckstand AM FENSTERENDE - was nie geflossen ist.
        val ende = MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = BUDGET, phaseAShare = anteil,
            primeWindowMin = A_BIS, wallCeilingMin = 45, phaseBUntilMin = B_BIS,
            markerAuthorized = true, pressObservedInThisProcess = true, primeDeclinedByUser = false,
        )
        val rest = max(0.0, ende.phaseBBudgetU - spur.fundamentU)
        return Kennzahlen(
            anteil, m.name, bei, maxLeerlauf, spur.maxRueckstandU, rest,
            spur.fundamentU, spur.letzteBindung,
        )
    }

    /**
     * DIE EIN-VARIABLEN-PROBE. Ohne sie waere der ganze Vergleich wertlos:
     * jeder Unterschied koennte aus einem anderen Budget oder Fenster
     * stammen statt aus der Aufteilung.
     */
    @Test
    fun `der Vergleich variiert nur die Aufteilung`() {
        val autorisierungen = varianten.map { anteil ->
            MealFoundation.arm(
                markerTs = t0, foundationEnabled = true, totalBudgetU = BUDGET, phaseAShare = anteil,
                primeWindowMin = A_BIS, wallCeilingMin = 45, phaseBUntilMin = B_BIS,
                markerAuthorized = true, pressObservedInThisProcess = true, primeDeclinedByUser = false,
            )
        }
        for (a in autorisierungen) {
            assertEquals(BUDGET, a.totalBudgetU, 1e-9, "das Gesamtbudget MUSS konstant bleiben")
            assertEquals(t0 + B_BIS * 60_000L, a.endTs, "und das Fenster auch")
            assertEquals(A_BIS, a.pinnedPrimeWindowMin, "und die Uebergabe")
        }
        // UND DIE TEILBUDGETS MUESSEN SICH WIRKLICH UNTERSCHEIDEN - sonst
        // vergliche der Lauf vier Mal dasselbe.
        val teilbudgets = autorisierungen.map { it.phaseBBudgetU }
        assertEquals(
            teilbudgets.size, teilbudgets.distinct().size,
            "vier verschiedene Aufteilungen MUESSEN vier verschiedene Teilbudgets ergeben",
        )
    }

    /**
     * DIE VERGLEICHSTAFEL - sie wird ausgegeben, nicht behauptet.
     *
     * Der Test sichert nur die Zusicherungen, die aus der Bauform folgen; die
     * Auswertung selbst ist eine MESSUNG und gehoert in den Bericht, nicht in
     * eine Zusicherung. Eine Zahl hier festzuschreiben hiesse, eine
     * Replay-Hypothese zur Regel zu machen, bevor sie jemand gelesen hat.
     */
    @Test
    fun `Aufteilungs-Vergleich ueber die eingefrorenen Mahlzeiten`() {
        println("SPLIT anteil;mahlzeit;T15;T30;T45;T60;leerlaufMin;maxRueckstandU;restRueckstandU;fundamentU;bindung")
        for (m in mahlzeiten) {
            for (anteil in varianten) {
                val k = kennzahlen(anteil, m)
                println(
                    "SPLIT %.2f;%s;%.3f;%.3f;%.3f;%.3f;%d;%.3f;%.3f;%.3f;%s".format(
                        k.anteil, k.mahlzeit,
                        k.bei[15], k.bei[30], k.bei[45], k.bei[60],
                        k.leerlaufMin, k.maxRueckstandU, k.restRueckstandU, k.fundamentU,
                        k.letzteBindung?.name ?: "-",
                    )
                )
            }
        }

        // DIE EINZIGE ZUSICHERUNG, die aus der Bauform folgt und nicht aus
        // der Hypothese: 100/0 ist der heutige Stand - dort darf das
        // Fundament nichts beitragen. Bliebe hier etwas uebrig, waere das
        // Einschalten des Schalters allein schon eine Verhaltensaenderung.
        for (m in mahlzeiten) {
            assertEquals(
                0.0, kennzahlen(1.00, m).fundamentU, 1e-9,
                "${m.name}: bei 100/0 MUSS das Fundament schweigen",
            )
        }
    }

}
