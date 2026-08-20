package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DescentDeferredCarryTest {

    private val t0 = 1_787_000_000_000L

    @Test
    fun `Fruehstuecksluecke wird einmalig als unvermeidbarer Rueckstand erfasst`() {
        val observed = DescentDeferredCarry.observe(
            currentU = 0.0,
            phase = MealFoundation.Phase.PHASE_A,
            blockedByMeasuredDescent = true,
            phaseABudgetU = 3.0,
            deliveredPhaseAU = 0.75,
            nowTs = t0 + 17 * 60_000L,
            handoverTs = t0 + 20 * 60_000L,
            maxPositivePerCycleU = 0.30,
        )
        assertEquals(1.65, observed, 1e-9)

        // Derselbe offene Prime-Rueckstand erscheint im naechsten Rechenweg
        // erneut. Er darf nicht als zweites Ereignis addiert werden.
        val repeated = DescentDeferredCarry.observe(
            currentU = observed,
            phase = MealFoundation.Phase.PHASE_A,
            blockedByMeasuredDescent = true,
            phaseABudgetU = 3.0,
            deliveredPhaseAU = 0.75,
            nowTs = t0 + 17 * 60_000L,
            handoverTs = t0 + 20 * 60_000L,
            maxPositivePerCycleU = 0.30,
        )
        assertEquals(1.65, repeated, 1e-9, "kein zyklisches Aufsummieren derselben Luecke")
    }

    @Test
    fun `nur ein finaler Abwaertsblock in Phase A erzeugt Aufschub`() {
        val basis = 0.20
        for ((phase, blocked) in listOf(
            MealFoundation.Phase.PHASE_A to false,
            MealFoundation.Phase.PHASE_B to true,
            MealFoundation.Phase.NONE to true,
            MealFoundation.Phase.AFTER_WINDOW to true,
        )) {
            assertEquals(
                basis,
                DescentDeferredCarry.observe(
                    basis, phase, blocked, 3.0, 0.75,
                    t0 + 17 * 60_000L, t0 + 20 * 60_000L, 0.30,
                ),
                1e-9,
                "$phase blocked=$blocked",
            )
        }
    }

    @Test
    fun `Erholung allein reicht nicht wenn Tief Rebound oder Signalfehler vorliegt`() {
        fun eligibility(
            phase: MealFoundation.Phase = MealFoundation.Phase.PHASE_B,
            latch: Boolean = false,
            healthy: Boolean = true,
            low: Boolean = false,
            rebound: Boolean = false,
        ) = DescentDeferredCarry.eligibility(1.0, phase, latch, healthy, low, rebound)

        assertEquals(DescentDeferredCarry.Eligibility.ELIGIBLE, eligibility())
        assertEquals(DescentDeferredCarry.Eligibility.NOT_PHASE_B, eligibility(phase = MealFoundation.Phase.PHASE_A))
        assertEquals(DescentDeferredCarry.Eligibility.LATCH_ACTIVE, eligibility(latch = true))
        assertEquals(DescentDeferredCarry.Eligibility.SIGNAL_UNHEALTHY, eligibility(healthy = false))
        assertEquals(DescentDeferredCarry.Eligibility.MEASURED_LOW, eligibility(low = true))
        assertEquals(DescentDeferredCarry.Eligibility.REBOUND_ACTIVE, eligibility(rebound = true))
        assertEquals(
            DescentDeferredCarry.Eligibility.NO_DEFERRED,
            DescentDeferredCarry.eligibility(
                0.0, MealFoundation.Phase.PHASE_B, false, true, false, false,
            ),
        )
    }

    @Test
    fun `Transportbeweis und Sicherheitsaufschub teilen denselben realen Rueckstand`() {
        val a = DescentDeferredCarry.allocate(
            phaseABudgetU = 3.0,
            deliveredPhaseAU = 1.35,
            confirmedNotSentPhaseAU = 0.30,
            descentDeferredPhaseAU = 1.65,
            descentEligibility = DescentDeferredCarry.Eligibility.ELIGIBLE,
        )
        assertEquals(1.65, a.phaseAShortfallU, 1e-9)
        assertEquals(0.30, a.effectiveTransportCarryU, 1e-9)
        assertEquals(1.35, a.effectiveDescentCarryU, 1e-9)
        assertEquals(1.65, a.totalEffectiveCarryU, 1e-9)
    }

    @Test
    fun `Phase B verteilt den Aufschub linear und nie als Burst`() {
        fun plan(eligibility: DescentDeferredCarry.Eligibility) = MealFoundation.plan(
            markerTs = t0,
            nowTs = t0 + 21 * 60_000L,
            handoverTs = t0 + 20 * 60_000L,
            totalBudgetU = 3.75,
            phaseBBudgetU = 0.75,
            confirmedNotSentPhaseAU = 0.0,
            descentDeferredPhaseAU = 1.65,
            descentCarryEligibility = eligibility,
            phaseBUntilMin = 60,
            deliveredFromBudgetU = 1.35,
            deliveredSinceHandoverU = 0.0,
            bolusStepU = 0.05,
        )

        val eligible = plan(DescentDeferredCarry.Eligibility.ELIGIBLE)
        val blocked = plan(DescentDeferredCarry.Eligibility.LATCH_ACTIVE)
        assertEquals(2.40, eligible.remainingInWindowU, 1e-9)
        assertEquals(0.75, blocked.remainingInWindowU, 1e-9)
        assertTrue(eligible.dueU <= 0.05 + 1e-9, "weiter genau ein Pumpenschritt je Zyklus")
        assertEquals(2.40 / 40.0, eligible.effectiveRateUPerMin, 1e-9)
    }
}
