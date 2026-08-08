package app.aaps.fuse.core.observer

import app.aaps.fuse.core.adapter.CycleAssembly
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * C9 (Codex-Adjudication 09.08.): zwischen der Zustands-Kontinuitaet
 * (1,5 min) und dem R-Segmentbruch (3,0 min) lag ein Band, in dem WEDER die
 * Sprungerkennung noch der Segmentbruch griff - ein Kalibrier-/Regimesprung
 * mit dt = 2 min liess alten und neuen Punkt in derselben Steigungs-
 * schaetzung stehen. Das Sprungfenster reicht jetzt bis zur Segmentgrenze.
 */
class InputStepBandTest {

    private val t0 = 1_786_000_000_000L

    private fun stepDetected(dtMin: Double, jump: Double): Boolean {
        val o = ObserverStateMachine(sessionId = "c9")
        o.step(
            CycleAssembly.observerInput(
                sourceTs = t0, computeTs = t0, signalInputBg = 100.0, q1 = 100.0,
                rSigned = 0.5, sensorEpoch = 0L, calibrationEpoch = 0L,
                activity = ActivityValidity.VALID, profileIsfValid = true, inputGap = false,
            )
        )
        val t1 = t0 + (dtMin * 60_000.0).toLong()
        val s = o.step(
            CycleAssembly.observerInput(
                sourceTs = t1, computeTs = t1, signalInputBg = 100.0 + jump, q1 = 100.0 + jump,
                rSigned = 0.5, sensorEpoch = 0L, calibrationEpoch = 0L,
                activity = ActivityValidity.VALID, profileIsfValid = true, inputGap = false,
            )
        )
        return HealthReason.INPUT_STEP_RECOVERY in s.healthReasons
    }

    @Test
    fun `Sprung im frueher ungedeckten Band 1,5 bis 3 min wird erkannt`() {
        assertTrue(stepDetected(2.0, 30.0)) { "dt 2,0 min mit 30 mg/dl Sprung (C9-Luecke)" }
        assertTrue(stepDetected(2.9, 25.0)) { "dt 2,9 min - noch innerhalb des R-Segments" }
    }

    @Test
    fun `Sprung im bisherigen Fenster wird weiterhin erkannt`() {
        assertTrue(stepDetected(1.0, 30.0))
    }
}
