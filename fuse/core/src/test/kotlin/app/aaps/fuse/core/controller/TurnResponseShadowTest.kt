package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TurnResponseShadowTest {

    private val t0 = 1_700_000_000_000L
    private fun s(min: Int, fast: Double, raw: Double = fast) =
        TurnResponseShadow.Sample(t0 + min * 60_000L, raw, fast)

    private fun classify(
        samples: List<TurnResponseShadow.Sample>,
        slow: Double,
        healthy: Boolean = true,
        outlier: Boolean = false,
    ) = TurnResponseShadow.classify(samples, slow, 0.5, healthy, outlier)

    @Test
    fun `Fall 1 - 11 Uhr 33 erkennt positive Abwaertswende und waehlt R50`() {
        val r = classify(
            listOf(
                s(0, fast = 2.94, raw = 1.92),
                s(1, fast = 2.88, raw = 1.86),
                s(2, fast = 2.69, raw = 1.67),
            ),
            slow = 2.81,
        )

        assertEquals(TurnResponseShadow.Phase.TURNING_DOWN, r.phase)
        assertEquals(TurnResponseShadow.Reason.DOWN_CONFIRMED, r.reason)
        assertEquals(50, r.adaptiveRestraintTauMin)
        assertEquals(-0.19, r.delta1MgdlPerMin!!, 1e-9)
        assertEquals(-0.25, r.delta2MgdlPerMin!!, 1e-9)
        assertNull(r.upwardMeanDriveMgdlPerMin)
    }

    @Test
    fun `Aufwaertswende hebt nur einen konservativen Mittelkandidaten hervor`() {
        val r = classify(
            listOf(
                s(0, fast = 0.55, raw = 0.60),
                s(1, fast = 0.72, raw = 0.75),
                s(2, fast = 0.91, raw = 0.95),
            ),
            slow = 0.40,
        )

        assertEquals(TurnResponseShadow.Phase.TURNING_UP, r.phase)
        assertEquals(0.55, r.upwardMeanDriveMgdlPerMin!!, 1e-9)
        assertEquals(60, r.adaptiveRestraintTauMin)
    }

    @Test
    fun `negativer Drive bleibt ausnahmslos auf R60`() {
        val r = classify(
            listOf(
                s(0, fast = 0.20, raw = -0.1),
                s(1, fast = 0.02, raw = -0.3),
                s(2, fast = -0.30, raw = -0.6),
            ),
            slow = 1.20,
        )

        assertEquals(TurnResponseShadow.Phase.ALIGNED, r.phase)
        assertEquals(TurnResponseShadow.Reason.NEGATIVE_DRIVE_PRESERVED, r.reason)
        assertEquals(60, r.adaptiveRestraintTauMin)
    }

    @Test
    fun `blosse Differenz fast kleiner r ist noch keine Wende`() {
        val r = classify(
            listOf(s(0, 2.40), s(1, 2.38), s(2, 2.36)),
            slow = 2.80,
        )

        assertEquals(TurnResponseShadow.Phase.ALIGNED, r.phase)
        assertEquals(TurnResponseShadow.Reason.NO_CONFIRMED_TURN, r.reason)
        assertEquals(60, r.adaptiveRestraintTauMin)
    }

    @Test
    fun `Luecke und ungesundes Signal koennen keine Wende behaupten`() {
        val gap = classify(listOf(s(0, 1.0), s(1, 0.8), s(4, 0.4)), slow = 2.0)
        val unhealthy = classify(listOf(s(0, 1.0), s(1, 0.8), s(2, 0.4)), slow = 2.0, healthy = false)

        assertEquals(TurnResponseShadow.Phase.UNKNOWN, gap.phase)
        assertEquals(TurnResponseShadow.Reason.GAP, gap.reason)
        assertEquals(TurnResponseShadow.Phase.UNKNOWN, unhealthy.phase)
        assertEquals(TurnResponseShadow.Reason.SIGNAL_UNHEALTHY, unhealthy.reason)
    }

    @Test
    fun `statische Matrix bleibt genau R60 R55 R50 R45`() {
        assertEquals(listOf(60, 55, 50, 45), TurnResponseShadow.STATIC_RESTRAINT_TAUS_MIN)
        assertTrue(TurnResponseShadow.STATIC_RESTRAINT_TAUS_MIN.zipWithNext().all { (a, b) -> b < a })
    }
}
