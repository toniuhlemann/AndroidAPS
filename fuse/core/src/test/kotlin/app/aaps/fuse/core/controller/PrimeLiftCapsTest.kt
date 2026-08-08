package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Audit R95, NEU-04: PrimeRelease.lift muss tailHeadroom und onsetEnvelope
 * respektieren - vorher konnte ein tail-gedeckelter Basis-SMB (z. B. 0,05 U
 * am Schwanz-Limit) per Lift auf die Fenster-Rate (0,20 U) gehoben werden,
 * viermal ueber dem Schwanz-Deckel, wiederholbar jeden Zyklus des Fensters.
 */
class PrimeLiftCapsTest {

    private fun state() = FuseController.State(
        health = Health.READY, safetyHold = false, phase = Phase.REARMING,
        netIobU = 0.5, bolusIobU = 0.5, basalIobU = 0.0,
        iobThU = 8.0, maxIobU = 8.0, targetMgdl = 98.0, isfMgdlPerU = 80.0,
        smbRatioCorrection = 0.15, smbRatioRise = 0.35,
        rSignedMgdlPerMin = 1.0, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = 0.05, maxSmbU = 0.3, pumpBusy = false,
    )

    private fun base(smb: Double, limit: String = "tailHeadroom=0.05") =
        FuseController.Decision(smb, FuseController.TbrAction.KEEP_CURRENT, FuseController.Block.NONE, 1.0, 150.0, 110.0, limit)

    private val plan = PrimeRelease.Plan(true, 0.20, 1.0, "PRIME")

    @Test
    fun `ohne uebergebene Deckel wuerde der Lift heben - der Altzustand`() {
        val d = PrimeRelease.lift(base(0.05), plan, state())
        assertEquals(0.20, d.smbU, 1e-9)
    }

    @Test
    fun `tailHeadroom kappt den Lift`() {
        // Schwanz-Deckel 0,05: der Lift darf die Basis nicht mehr anheben.
        val d = PrimeRelease.lift(base(0.05), plan, state(), tailHeadroomU = 0.05)
        assertEquals(0.05, d.smbU, 1e-9)
    }

    @Test
    fun `onsetCap kappt den Lift auf die Restenvelope`() {
        val d = PrimeRelease.lift(base(0.05), plan, state(), onsetCapU = 0.10)
        assertEquals(0.10, d.smbU, 1e-9)
        assertEquals("primeRelease", d.bindingLimit)
    }

    @Test
    fun `beide Deckel - der engere gewinnt`() {
        val d = PrimeRelease.lift(base(0.05), plan, state(), tailHeadroomU = 0.15, onsetCapU = 0.10)
        assertEquals(0.10, d.smbU, 1e-9)
    }

    /** Fix-Pass 2 Nr. 2 (NEU-BS-01): In-Flight-Menge reduziert die
     *  Lift-Headrooms genauso wie die Such-Headrooms. State: iobTH 8,
     *  capIob 0,5 -> nominal 7,5 frei; mit 7,45 U In-Flight bleibt 0,05 -
     *  der Lift darf nicht mehr auf 0,20 heben. */
    @Test
    fun `transportCommitment kappt den Lift`() {
        val d = PrimeRelease.lift(base(0.05), plan, state(), transportCommitmentU = 7.45)
        assertEquals(0.05, d.smbU, 1e-9)
        // Ohne Transportmenge hebt derselbe Aufruf weiterhin.
        assertEquals(0.20, PrimeRelease.lift(base(0.05), plan, state()).smbU, 1e-9)
    }
}
