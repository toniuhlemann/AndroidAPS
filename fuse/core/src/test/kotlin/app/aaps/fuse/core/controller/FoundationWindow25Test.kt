package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DAS 25-MIN-FUNDAMENT-FENSTER ALS EIN-VARIABLEN-TEST (Bauauftrag Toni
 * 31.08., Schritt C).
 *
 * Der Kandidat aendert GENAU EINE Groesse gegenueber dem Livestand:
 *
 *     Freigabe-Fenster        5 min   unveraendert
 *     Anteil Phase A          0,80    unveraendert
 *     Phase-A-Sofortanteil    1,00    unveraendert
 *     Fundament-Fenster       45 -> 25 min
 *     Fundament-Gesamtbudget  5 U     unveraendert
 *
 * Das Fenster ist eine PREFERENCE (`fuse_meal_foundation_end_min`, Bereich
 * 20..180) und bleibt konfigurierbar - diese Tests aendern keinen Default,
 * sie sichern den 25er-WERT des vorhandenen Knopfs ab: gleiche Menge,
 * frueher abgeschlossen, kein Carry, Phase A unberuehrt.
 *
 * AUSDRUECKLICH KEINE BG-BEHAUPTUNG (Nachweis 7): die Rechnung hier ist
 * rueckkopplungsblind. Was der fruehere Abschluss am Glukoseverlauf
 * aendert, kann nur der Live-Einvariablen-Test zeigen (Schritt E).
 */
class FoundationWindow25Test {

    private val t0 = 1_700_000_000_000L

    /** DER KANDIDAT - das Mutationsziel: zurueck auf 45 muss den
     *  Zeitabschluss-Test rot machen (Nachweis 6). */
    private val FENSTER_MIN = 25
    private val VERGLEICH_MIN = 45
    private val STEP = 0.05

    private fun auth(fensterMin: Int) = MealFoundation.arm(
        markerTs = t0,
        foundationEnabled = true,
        totalBudgetU = 5.0,
        phaseAShare = 0.8,
        phaseAUpfrontShare = 1.0,
        primeWindowMin = 5,
        wallCeilingMin = PrimeRelease.WALL_CEILING_MIN,
        phaseBUntilMin = fensterMin,
        markerAuthorized = true,
        pressObservedInThisProcess = true,
        primeDeclinedByUser = false,
    )

    private fun plan(
        a: MealFoundation.Authorization,
        minuten: Double,
        phaseBGeflossenU: Double,
        gesamtGeflossenU: Double,
    ) = MealFoundation.planFrom(
        a, t0 + (minuten * 60_000).toLong(), t0,
        gesamtGeflossenU, phaseBGeflossenU, 0.0, 0.0,
        DescentDeferredCarry.Eligibility.NO_DEFERRED, STEP,
    )

    /** Minutenweise Simulation: jeder Zyklus liefert genau sein `dueU`,
     *  Phase A (4 U Sofortanteil) gilt als geflossen. Rueckgabe: Summe der
     *  Phase-B-Lieferungen und die letzte Minute mit positiver Freigabe. */
    private fun durchlauf(a: MealFoundation.Authorization, bisMinute: Int): Pair<Double, Int> {
        var geliefert = 0.0
        var letztePositive = -1
        for (m in 5..bisMinute) {
            val p = plan(a, m.toDouble(), geliefert, 4.0 + geliefert)
            if (p.dueU > 0.0) {
                geliefert += p.dueU
                letztePositive = m
            }
            // Nachweis 4: Phase A + B zusammen nie ueber dem 5-U-Budget.
            assertTrue(4.0 + geliefert <= 5.0 + 1e-9) {
                "Budgetueberschreitung bei T+$m: ${4.0 + geliefert}"
            }
        }
        return geliefert to letztePositive
    }

    // ---- NACHWEIS 1: Phase A bleibt unberuehrt -------------------------

    @Test
    fun `das Fenster aendert an Phase A nichts - 4 U wie bisher`() {
        val a25 = auth(FENSTER_MIN)
        val a45 = auth(VERGLEICH_MIN)
        assertEquals(4.0, a25.phaseABudgetU, 1e-9)
        assertEquals(4.0, a25.phaseAUpfrontU, 1e-9, "Sofortanteil 1,00 -> ganze Phase A sofort")
        assertEquals(1.0, a25.phaseBBudgetU, 1e-9)
        // Ein-Variablen-Beweis: die Phase-A-Groessen der beiden Fenster
        // sind identisch, nur endTs unterscheidet sich.
        assertEquals(a45.phaseABudgetU, a25.phaseABudgetU, 1e-12)
        assertEquals(a45.phaseAUpfrontU, a25.phaseAUpfrontU, 1e-12)
        assertEquals(a45.phaseARemainderU, a25.phaseARemainderU, 1e-12)
        assertEquals(
            MealFoundation.remainingUpfrontU(a45, 0.0, 0.0),
            MealFoundation.remainingUpfrontU(a25, 0.0, 0.0),
            "der Sofort-Boden haengt nicht am Phase-B-Fenster",
        )
        assertEquals((VERGLEICH_MIN - FENSTER_MIN) * 60_000L, a45.endTs - a25.endTs)
    }

    // ---- NACHWEIS 2: Phase B beginnt unveraendert nach 5 Minuten -------

    @Test
    fun `Phase B beginnt in beiden Fenstern nach fuenf Minuten`() {
        // Erste Faelligkeit = Uebergabe + step/rate: beim 25er (0,05 U/min)
        // ist der erste 0,05er-Schritt bei T+6 faellig, beim 45er
        // (0,025 U/min) erst bei T+7 - der BEGINN bleibt in beiden T+5.
        for ((a, ersteFaellig) in listOf(auth(FENSTER_MIN) to 6, auth(VERGLEICH_MIN) to 7)) {
            assertEquals(
                MealFoundation.Binding.BEFORE_WINDOW,
                plan(a, 4.9, 0.0, 4.0).binding,
                "vor der Uebergabe ist Phase A zustaendig",
            )
            assertEquals(
                MealFoundation.Binding.ON_SCHEDULE,
                plan(a, 5.0, 0.0, 4.0).binding,
                "ab T+5 laeuft die Rampe - Phase B hat begonnen",
            )
            assertEquals(
                0.0, plan(a, ersteFaellig - 0.5, 0.0, 4.0).dueU, 1e-12,
                "eine halbe Minute vor der ersten Faelligkeit ist noch kein Schritt frei",
            )
            assertTrue(plan(a, ersteFaellig.toDouble(), 0.0, 4.0).dueU > 0.0) {
                "bei T+$ersteFaellig muss der erste Schritt faellig sein"
            }
        }
    }

    // ---- NACHWEIS 3: DER ZEITABSCHLUSS (Mutationsziel) -----------------

    @Test
    fun `das 1-U-Restbudget ist spaetestens bei T+25 verbraucht`() {
        val (geliefert, letztePositive) = durchlauf(auth(FENSTER_MIN), 40)
        // Tonis Kandidat geht exakt auf: 20 min x 0,05 U/min = 1,00 U.
        assertEquals(1.0, geliefert, 1e-9, "das ganze Phase-B-Budget fliesst")
        assertTrue(letztePositive <= FENSTER_MIN) {
            "ZEITABSCHLUSS verletzt: letzte Freigabe bei T+$letztePositive > T+$FENSTER_MIN"
        }
        // Nach dem Fenster VERFAELLT der Rest - kein Carry (Tonis Auflage).
        val danach = plan(auth(FENSTER_MIN), FENSTER_MIN + 1.0, geliefert, 4.0 + geliefert)
        assertEquals(MealFoundation.Binding.AFTER_WINDOW, danach.binding)
        assertEquals(0.0, danach.dueU, 1e-9, "nach dem Fenster wird nichts mehr frei")
    }

    @Test
    fun `was nicht floss wird nach T+25 ehrlich als Rueckstand ausgewiesen`() {
        // Der Lapse-Zweig von Nachweis 3: nichts geliefert -> der ganze
        // 1-U-Rest steht als backlogU im Plan, nicht als stilles Nichts.
        val p = plan(auth(FENSTER_MIN), FENSTER_MIN + 1.0, 0.0, 4.0)
        assertEquals(MealFoundation.Binding.AFTER_WINDOW, p.binding)
        assertEquals(1.0, p.backlogU, 1e-9, "der nie geflossene Rest ist sichtbar")
        assertEquals(0.0, p.dueU, 1e-9)
    }

    // ---- NACHWEIS 4: das Budget bleibt hart ----------------------------

    @Test
    fun `bei ausgeschoepftem Gesamtbudget schweigt das Fundament`() {
        val p = plan(auth(FENSTER_MIN), 10.0, 1.0, 5.0)
        assertEquals(MealFoundation.Binding.BUDGET_EXHAUSTED, p.binding)
        assertEquals(0.0, p.dueU, 1e-9)
    }

    // ---- NACHWEIS 5: der 45er-Zwilling zeigt NUR die Verschiebung ------

    @Test
    fun `der Vergleich mit 45 beweist nur die zeitliche Verschiebung`() {
        val (g25, l25) = durchlauf(auth(FENSTER_MIN), 60)
        val (g45, l45) = durchlauf(auth(VERGLEICH_MIN), 60)
        assertEquals(g45, g25, 1e-9, "GLEICHE Menge - das Fenster aendert kein Budget")
        assertTrue(l25 <= FENSTER_MIN && l45 > FENSTER_MIN && l45 <= VERGLEICH_MIN) {
            "nur die Zeitachse verschiebt sich: 25er bis T+$l25, 45er bis T+$l45"
        }
        // Die Sollrate verdoppelt sich sichtbar: 1 U auf 20 statt 40 min.
        val r25 = plan(auth(FENSTER_MIN), 10.0, 0.0, 4.0)
        val r45 = plan(auth(VERGLEICH_MIN), 10.0, 0.0, 4.0)
        assertEquals(0.05, r25.effectiveRateUPerMin, 1e-9)
        assertEquals(0.025, r45.effectiveRateUPerMin, 1e-9)
    }
}
