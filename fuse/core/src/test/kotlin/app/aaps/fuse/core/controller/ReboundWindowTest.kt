package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryPoint
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
        reboundWindow = rebound, mealWindow = true,
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

    /** FENSTER-TRIO: ausserhalb des Mahlzeit-Fensters gilt der
     *  Korrektur-Anteil, egal wie hoch r steht - r kann positiv sein,
     *  waehrend BG faellt (GPT-Befund 07.08., von Toni bestaetigt). */
    @Test
    fun `ausserhalb des Mahlzeit-Fensters bleibt die Rampe zu`() {
        val s = state(rebound = false, r = 3.0).copy(mealWindow = false)
        assertEquals(0.15, s.effectiveSmbRatio, 0.0)
    }

    private fun pred(anchor: Double, mean: Double) = PredictorResult(
        points = (1..60).map { TrajectoryPoint(it, it * 60_000L, mean, mean, 0.0, 0.0, 0.0) },
        predictionAnchorTs = 0L, bgAtAnchor = anchor,
        minMeanBg = mean, minLowerBg = mean, timeToMinLowerMin = 30,
        bgAtHorizonMean = mean, bgAtHorizonLower = mean,
        lineageKind = "ACTUAL", trajectoryContentHash = "h",
        iobArraySpanMin = 235.0, iobArrayGridMin = 5.0,
        modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
    )

    /**
     * DER 00:26-FALL (Vorfall #5, eingefroren): Rebound-Fenster aktiv, Anker
     * 107 bei Ziel 98 - r-aufgeblaehte Bahn meldet Bedarf, aber die Erholung
     * bis Ziel+25 ist ERWUENSCHT: Totband nullt die Dosis. 14 solcher Zyklen
     * gaben in der Nacht 07./08.08. zusammen 1,05 U.
     */
    @Test
    fun `im Rebound-Fenster nullt das Totband unterhalb von Ziel plus 25`() {
        val d = FuseController.decide(state(rebound = true, r = 3.32), pred(anchor = 107.0, mean = 180.0))
        assertEquals(0.0, d.smbU, 0.0)
        assertEquals("reboundDeadband", d.bindingLimit)
        assertEquals(FuseController.Block.NO_DEMAND, d.block)
    }

    /** Ueber Ziel+25 dosiert auch das Rebound-Fenster wieder - gedeckelt auf
     *  den Korrektur-Anteil, aber nicht tot. */
    @Test
    fun `oberhalb des Totbands dosiert das Fenster gedeckelt weiter`() {
        val d = FuseController.decide(state(rebound = true, r = 3.32), pred(anchor = 130.0, mean = 180.0))
        assertTrue(d.smbU > 0.0)
        assertEquals(0.15, state(rebound = true, r = 3.32).effectiveSmbRatio, 0.0)
    }
}
