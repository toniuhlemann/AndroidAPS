package app.aaps.fuse.core.adapter

import app.aaps.fuse.core.controller.TbrPolicy
import app.aaps.fuse.core.profile.ProfileSlots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R81-F6: keine Bauhelfer-Ausnahme darf aus dem Loop austreten.
 *
 * Die Grenze verlaeuft bewusst zwischen zwei Sorten Code — Entscheidungs-
 * funktionen lehnen benannt ab, Bauhelfer werfen bei falsch geformten Arrays.
 * Damit die zweite Sorte trotzdem nie nach draussen durchschlaegt, laeuft jeder
 * Aufbau durch [CoreInputGuard].
 */
class CoreInputGuardTest {

    private val t0 = 1_700_000_000_000L
    private fun min(m: Int) = t0 + m * 60_000L

    @Test
    fun `ein gueltiger Aufbau kommt unveraendert durch`() {
        val out = CoreInputGuard.build {
            ProfileSlots.compressBasal(longArrayOf(min(0), min(10)), doubleArrayOf(0.7, 0.9), min(20))
        }
        assertTrue(out is CoreInputGuard.Outcome.Built)
        assertEquals(2, (out as CoreInputGuard.Outcome.Built).value.size)
    }

    @Test
    fun `unsortierte Basalzeiten werden zu CORE_INPUT_INVALID`() {
        val out = CoreInputGuard.build {
            ProfileSlots.compressBasal(longArrayOf(min(10), min(0)), doubleArrayOf(0.7, 0.9), min(20))
        }
        val failed = out as CoreInputGuard.Outcome.Failed
        assertEquals(TbrPolicy.FaultCode.CORE_INPUT_INVALID, failed.failure.fault)
        assertTrue(failed.failure.detail.contains("increasing"))
    }

    @Test
    fun `ungleich lange ISF-Spalten werden zu CORE_INPUT_INVALID`() {
        val out = CoreInputGuard.build {
            CycleAssembly.compressIsfSlots(longArrayOf(min(0), min(10)), doubleArrayOf(50.0), min(20))
        }
        assertEquals(
            TbrPolicy.FaultCode.CORE_INPUT_INVALID,
            (out as CoreInputGuard.Outcome.Failed).failure.fault
        )
    }

    @Test
    fun `der Fehlercode loest genau eine Gegenmassnahme aus - kein SMB, positive TBR abbrechen`() {
        val failure = (CoreInputGuard.build { error("kaputt") } as CoreInputGuard.Outcome.Failed).failure
        val d = TbrPolicy.decide(
            TbrPolicy.Intent.KEEP,
            TbrPolicy.Current(1.50, 20, TbrPolicy.SourceType.TEMP_BASAL),
            scheduledBasalUPerH = 0.70,
            cfg = TbrPolicy.Config(basalStepUPerH = 0.05),
            fault = failure.fault,
        )
        assertEquals(TbrPolicy.Outcome.Request(0.0, 0), d.outcome)
        assertTrue(d.smbBlocked)
    }
}
