package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Audit R95, Fix 3: der Ledger-Hold muss NACH PrimeRelease.lift greifen.
 * Die Suche allein genuegt nicht - Ratio-Pfad (Kernel-Ausfall) und
 * Sofort-Freigabe laufen an ihrem LEDGER_HOLD-Reject vorbei.
 */
class LedgerHoldGateTest {

    private fun state() = FuseController.State(
        health = Health.READY, safetyHold = false, phase = Phase.REARMING,
        netIobU = 0.5, bolusIobU = 0.5, basalIobU = 0.0,
        iobThU = 8.0, maxIobU = 8.0, targetMgdl = 98.0, isfMgdlPerU = 80.0,
        smbRatioCorrection = 0.15, smbRatioRise = 0.35,
        rSignedMgdlPerMin = 1.0, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = 0.05, maxSmbU = 0.3, pumpBusy = false,
    )

    private fun base(smb: Double, block: FuseController.Block = FuseController.Block.NONE) =
        FuseController.Decision(smb, FuseController.TbrAction.KEEP_CURRENT, block, 1.0, 150.0, 110.0, "smbRatio")

    @Test
    fun `ohne Hold bleibt die Entscheidung dieselbe Instanz`() {
        val d = base(0.15)
        assertSame(d, LedgerHoldGate.apply(d, hold = false))
    }

    @Test
    fun `Hold nullt eine positive Menge und benennt den Grund`() {
        val d = LedgerHoldGate.apply(base(0.15), hold = true)
        assertEquals(0.0, d.smbU, 1e-12)
        assertEquals(FuseController.Block.LEDGER_HOLD, d.block)
        assertEquals("ledgerHold", d.bindingLimit)
        // Die TBR-Achse bleibt unangetastet - Hold ist "keine NEUE Dosis",
        // kein Sicherheitsbefund der Bahn.
        assertEquals(FuseController.TbrAction.KEEP_CURRENT, d.tbr)
    }

    @Test
    fun `Hold ohne Menge laesst die Entscheidung unveraendert`() {
        val d = base(0.0, FuseController.Block.NO_DEMAND)
        assertSame(d, LedgerHoldGate.apply(d, hold = true))
    }

    /** Der Kernfall des Audits: die Sofort-Freigabe hebt eine NO_DEMAND-Basis
     *  an - und der Hold muss AUCH diese gehobene Menge nullen, sonst waere
     *  er ueber den Lift umgehbar. */
    @Test
    fun `Hold greift nach dem Lift durch`() {
        val plan = PrimeRelease.Plan(true, 0.20, 1.0, "PRIME")
        val lifted = PrimeRelease.lift(base(0.0, FuseController.Block.NO_DEMAND), plan, state())
        assertEquals(0.20, lifted.smbU, 1e-9)

        val held = LedgerHoldGate.apply(lifted, hold = true)
        assertEquals(0.0, held.smbU, 1e-12)
        assertEquals(FuseController.Block.LEDGER_HOLD, held.block)
    }
}
