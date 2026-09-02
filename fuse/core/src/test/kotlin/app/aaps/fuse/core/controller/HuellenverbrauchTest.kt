package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WAS DIE HUELLENZEILE ZEIGEN MUSS.
 *
 * Der Befund: die Zeile rechnete `primeSpentU` gegen die eingestellte
 * Huelle. Beide Zahlen sind fuer diese Aussage falsch - `primeSpentU`
 * waechst nur im Prime-Fenster, und `primeBudgetU` gibt bei bewaffnetem
 * Fundament nur das Phase-A-Budget zurueck. Nach der Uebergabe an Phase B
 * lief die Abgabe weiter und die Zeile meldete unveraendert zu viel frei.
 *
 * Alle Werte sind synthetisch.
 */
class HuellenverbrauchTest {

    private val t0 = 1_700_000_000_000L

    private fun auth(budgetU: Double = 4.0) = MealFoundation.arm(
        markerTs = t0, foundationEnabled = true, totalBudgetU = budgetU, phaseAShare = 0.75,
        phaseAUpfrontShare = 0.0, primeWindowMin = 15, wallCeilingMin = 45, phaseBUntilMin = 60,
        pressObservedInThisProcess = true, primeDeclinedByUser = false, markerAuthorized = true,
    )

    /**
     * PFLICHTFALL PHASE A -> PHASE B.
     *
     * In Phase A sind 1,20 U geflossen, danach in Phase B weitere 0,80 U.
     * `primeSpentU` steht seit der Uebergabe still (1,20). Die Zeile muss
     * trotzdem 2,00 U verbucht und 2,00 U frei melden - sonst behauptet
     * sie Spielraum, den das Budget selbst nicht mehr sieht.
     */
    @Test
    fun `der Verbrauch waechst auch nach der Uebergabe an Phase B`() {
        val a = auth(budgetU = 4.0)

        val phaseA = MealFoundation.envelopeUse(
            auth = a, primeSpentU = 1.20,
            deliveredPhaseAU = 1.20, deliveredSinceHandoverU = 0.0,
            livePrimeEnvelopeU = 4.0,
        )
        assertEquals(4.0, phaseA.envelopeU, 1e-9)
        assertEquals(1.20, phaseA.spentU, 1e-9)
        assertEquals(2.80, phaseA.freeU, 1e-9)
        assertTrue(phaseA.armed)

        // Uebergabe: `primeSpentU` bleibt stehen, Phase B liefert weiter.
        val phaseB = MealFoundation.envelopeUse(
            auth = a, primeSpentU = 1.20,
            deliveredPhaseAU = 1.20, deliveredSinceHandoverU = 0.80,
            livePrimeEnvelopeU = 4.0,
        )
        assertEquals(2.00, phaseB.spentU, 1e-9)
        assertEquals(2.00, phaseB.freeU, 1e-9)

        // DIE ALTE RECHNUNG, ausdruecklich als Gegenprobe: sie stand still.
        assertEquals(phaseA.spentU, 1.20, 1e-9)
    }

    /**
     * DIE HUELLE IST DIE GEPINNTE, nicht die aktuelle Einstellung. Wer
     * waehrend der laufenden Mahlzeit die Einstellung aendert, verschiebt
     * die Autorisierung nicht - die Zeile darf das nicht anders erzaehlen.
     */
    @Test
    fun `die Huelle folgt der Autorisierung und nicht der Einstellung`() {
        val u = MealFoundation.envelopeUse(
            auth = auth(budgetU = 3.0), primeSpentU = 0.0,
            deliveredPhaseAU = 0.0, deliveredSinceHandoverU = 0.0,
            livePrimeEnvelopeU = 9.9,
        )
        assertEquals(3.0, u.envelopeU, 1e-9)
    }

    /**
     * OHNE BEWAFFNETES FUNDAMENT bleibt es bei der alten Paarung: es gibt
     * keine Phasen, `primeSpentU` ist der ganze Verbrauch.
     */
    @Test
    fun `ohne Fundament gelten Live-Huelle und primeSpentU`() {
        val u = MealFoundation.envelopeUse(
            auth = MealFoundation.Authorization.none(), primeSpentU = 0.45,
            deliveredPhaseAU = 7.0, deliveredSinceHandoverU = 7.0,
            livePrimeEnvelopeU = 2.0,
        )
        assertFalse(u.armed)
        assertEquals(2.0, u.envelopeU, 1e-9)
        assertEquals(0.45, u.spentU, 1e-9)
        assertEquals(1.55, u.freeU, 1e-9)
    }

    /** Mehr verbucht als Huelle heisst frei = 0, nie negativ. */
    @Test
    fun `frei wird nicht negativ`() {
        val u = MealFoundation.envelopeUse(
            auth = auth(budgetU = 1.0), primeSpentU = 0.0,
            deliveredPhaseAU = 0.9, deliveredSinceHandoverU = 0.6,
            livePrimeEnvelopeU = 1.0,
        )
        assertEquals(1.5, u.spentU, 1e-9)
        assertEquals(0.0, u.freeU, 1e-9)
    }
}
