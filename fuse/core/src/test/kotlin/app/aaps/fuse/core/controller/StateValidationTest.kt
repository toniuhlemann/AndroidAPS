package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Audit R95, F-P0-08: der IOB-Snapshot muss endlich und plausibel sein,
 * BEVOR er Headroom-Gates speist (NaN <= x ist false - beide Gates waeren
 * still offen; korrupt negatives Bolus-IOB blaeht sie auf). Der State wirft
 * im CoreInputGuard -> benannter Abort statt stiller Fehlregelung.
 */
class StateValidationTest {

    private fun state(
        net: Double = 0.5, bolus: Double = 0.5, basal: Double = 0.0,
        isf: Double = 80.0, target: Double = 98.0, iobTh: Double = 8.0, maxIob: Double = 8.0,
    ) = FuseController.State(
        health = Health.READY, safetyHold = false, phase = Phase.REARMING,
        netIobU = net, bolusIobU = bolus, basalIobU = basal,
        iobThU = iobTh, maxIobU = maxIob, targetMgdl = target, isfMgdlPerU = isf,
        smbRatioCorrection = 0.15, smbRatioRise = 0.35,
        rSignedMgdlPerMin = 1.0, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = 0.05, maxSmbU = 0.3, pumpBusy = false,
    )

    @Test
    fun `physiologische Werte passieren - auch negatives Basal-IOB`() {
        // Zero-Temps machen Basal-IOB normal negativ; Netto darf mitfallen.
        val s = state(net = -0.4, bolus = 0.1, basal = -0.5)
        assertEquals(0.1, s.capIobU, 0.0)
    }

    @Test
    fun `nicht endliche IOB-Felder werfen`() {
        assertThrows(IllegalArgumentException::class.java) { state(net = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { state(bolus = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { state(basal = Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `korrupt negatives Bolus-IOB wirft`() {
        // Der R95-Gegenpruefungs-Fall: NS-Bolus insulin=-50 -> bolusIob stark
        // negativ -> capIobU negativ -> Gates dauerhaft offen. Jetzt: Wurf.
        assertThrows(IllegalArgumentException::class.java) { state(net = -50.0, bolus = -50.0) }
        assertThrows(IllegalArgumentException::class.java) { state(bolus = -0.5) }
    }

    @Test
    fun `betragsgrenzen und kerngroessen`() {
        assertThrows(IllegalArgumentException::class.java) { state(net = 150.0, bolus = 150.0) }
        assertThrows(IllegalArgumentException::class.java) { state(isf = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { state(isf = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { state(target = 20.0) }
        assertThrows(IllegalArgumentException::class.java) { state(iobTh = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { state(maxIob = -1.0) }
    }
}
