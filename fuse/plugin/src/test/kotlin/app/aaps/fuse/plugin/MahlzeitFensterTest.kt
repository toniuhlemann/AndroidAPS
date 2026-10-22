package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.EarlySupply
import app.aaps.fuse.core.controller.ExpectationAccount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Das Mahlzeitfenster des Kontos fuer die Anzeige. Geraetebefund: es galt das 90-min-Markerfenster aus 1.x, waehrend
 * das Fenster des Kontos (Mahlzeitfrist) weiterlief. Synthetische Werte.
 */
class MahlzeitFensterTest {

    private val t0 = 1_700_000_000_000L
    private val min = 60_000L
    private val frist = 180

    private fun konto(): ExpectationAccount.State {
        val werte = requireNotNull(ExpectationAccount.Curve.ausEckwerten(1.0, 2.0, 3.0, 4.0)).values()
        val params = requireNotNull(ExpectationAccount.Params.of(werte, 120, 90.0, 15, 0.3))
        val einstellung = requireNotNull(EarlySupply.Settings.of(1.0, 0.5, 8.0, 0.5, 1.0, 2.0, 10))
        return ExpectationAccount.armEarly(
            authId = t0, windowEndTs = t0 + frist * min, params = params, settings = einstellung,
            switches = EarlySupply.Switches(true, true, true, true), startStable = true, nowTs = t0, bgMgdl = 100.0,
            isfMgdlPerU = 50.0, unitAction = { t -> EarlySupply.orefActionFraction(t, 45.0, 540.0) },
        )
    }

    @Test
    fun `das Fenster endet mit der Autorisierung des Kontos zum gesetzten Marker`() {
        val k = konto()
        assertEquals(t0 + frist * min, MahlzeitFenster.endeTs(k, t0))
        assertEquals(0L, MahlzeitFenster.endeTs(k, 0L))
        assertEquals(0L, MahlzeitFenster.endeTs(k, t0 + 5 * min))
        assertEquals(0L, MahlzeitFenster.endeTs(null, t0))
        assertTrue(MahlzeitFenster.laeuft(k, t0, t0 + 120 * min))
        assertTrue(MahlzeitFenster.laeuft(k, t0, t0 + 91 * min)) { "auch nach den alten 90 min" }
        assertFalse(MahlzeitFenster.laeuft(k, t0, t0 + frist * min))
    }
}
