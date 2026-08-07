package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReboundWindowTest {

    private fun state(rebound: Boolean, r: Double?) = FuseController.State(
        health = Health.READY, safetyHold = false, phase = Phase.REARMING,
        netIobU = 0.5, bolusIobU = 0.5, basalIobU = 0.0,
        iobThU = 8.0, maxIobU = 8.0, targetMgdl = 98.0, isfMgdlPerU = 80.0,
        smbRatioCorrection = 0.15, smbRatioRise = 0.35,
        rSignedMgdlPerMin = r, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = 0.05, maxSmbU = 0.3, pumpBusy = false,
        reboundWindow = rebound,
    )

    /** DER 16:28-FALL vom 07.08.: elf Minuten nach q1<75 stand r auf 3,3 und
     *  die Rampe auf 0,35 - 1,65 U in die zweite Senke. Mit Fenster: 0,15. */
    @Test
    fun `im Rebound-Fenster bleibt die Rampe auf dem Korrektur-Anteil`() {
        assertEquals(0.15, state(rebound = true, r = 3.32).effectiveSmbRatio, 0.0)
        assertEquals(0.15, state(rebound = true, r = 5.0).effectiveSmbRatio, 0.0)
    }

    @Test
    fun `ohne Fenster arbeitet die Rampe unveraendert`() {
        assertEquals(0.35, state(rebound = false, r = 3.32).effectiveSmbRatio, 1e-12)
        assertEquals(0.15, state(rebound = false, r = 0.2).effectiveSmbRatio, 1e-12)
    }

    /** Einseitigkeit: der Deckel kann den Anteil NIE erhoehen. */
    @Test
    fun `der Deckel ist einseitig`() {
        for (r in listOf(null, -1.0, 0.0, 0.4, 0.9, 2.0, 6.0)) {
            val mit = state(rebound = true, r = r).effectiveSmbRatio
            val ohne = state(rebound = false, r = r).effectiveSmbRatio
            assertTrue(mit <= ohne + 1e-12) { "erhoeht bei r=$r" }
            assertEquals(0.15, mit, 0.0)
        }
    }
}
