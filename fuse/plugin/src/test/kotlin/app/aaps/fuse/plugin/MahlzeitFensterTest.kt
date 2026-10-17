package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.EarlySupply
import app.aaps.fuse.core.controller.ExpectationAccount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Das Mahlzeitfenster des Kontos fuer Bestaetigung nach Tief und Anzeige. Geraetebefund: der Knopf "Mahlzeit nach Tief
 * bestaetigen" verschwand nach 90 min (Markerfenster aus 1.x), waehrend das Konto gesperrt war und das Fenster
 * (Mahlzeitfrist) weiterlief. Synthetische Werte.
 */
class MahlzeitFensterTest {

    private val t0 = 1_700_000_000_000L
    private val min = 60_000L
    private val frist = 180

    private fun konto(budget: Boolean = true): ExpectationAccount.State {
        val werte = requireNotNull(ExpectationAccount.Curve.ausEckwerten(1.0, 2.0, 3.0, 4.0)).values()
        val params = requireNotNull(ExpectationAccount.Params.of(werte, 120, 90.0, 15, 0.3))
        val einstellung = requireNotNull(EarlySupply.Settings.of(1.0, 0.5, if (budget) 8.0 else 0.0, 0.5, 1.0, 2.0, 10))
        return ExpectationAccount.armEarly(
            authId = t0, windowEndTs = t0 + frist * min, params = params, settings = einstellung,
            switches = EarlySupply.Switches(true, true, true, true), startStable = true, nowTs = t0, bgMgdl = 100.0,
            isfMgdlPerU = 50.0, unitAction = { t -> EarlySupply.orefActionFraction(t, 45.0, 540.0) },
        )
    }

    private fun gesperrt(k: ExpectationAccount.State) = k.copy(earlyLowLocked = true, earlyLowLockSinceTs = t0 + 40 * min)

    @Test
    fun `nach einem Tief gilt die Bestaetigung im ganzen Fenster - auch nach den alten 90 min`() {
        val k = gesperrt(konto())
        assertTrue(MahlzeitFenster.gesperrtNachTief(k, t0, t0 + 60 * min))
        assertTrue(MahlzeitFenster.gesperrtNachTief(k, t0, t0 + 91 * min)) { "Knopf nach 90 min weg (Fehler vor dem Fix)" }
        assertTrue(MahlzeitFenster.gesperrtNachTief(k, t0, t0 + (frist - 1) * min))
        assertFalse(MahlzeitFenster.gesperrtNachTief(k, t0, t0 + frist * min)) { "nach dem Fenster gibt es nichts zu bestaetigen" }
    }

    @Test
    fun `kein Knopf ohne Sperre, ohne Budget, nach Widerruf oder fuer ein Konto eines frueheren Drucks`() {
        val jetzt = t0 + 91 * min
        assertFalse(MahlzeitFenster.gesperrtNachTief(konto(), t0, jetzt)) { "ohne Sperre" }
        assertFalse(MahlzeitFenster.gesperrtNachTief(gesperrt(konto(budget = false)), t0, jetzt)) { "Budget 0" }
        assertFalse(MahlzeitFenster.gesperrtNachTief(gesperrt(konto()), 0L, jetzt)) { "Marker widerrufen" }
        assertFalse(MahlzeitFenster.gesperrtNachTief(gesperrt(konto()), t0 + 100 * min, jetzt)) { "neuer Druck, Konto noch alt" }
        assertFalse(MahlzeitFenster.gesperrtNachTief(null, t0, jetzt)) { "kein Konto" }
    }

    @Test
    fun `das Fenster endet mit der Autorisierung des Kontos zum gesetzten Marker`() {
        val k = konto()
        assertEquals(t0 + frist * min, MahlzeitFenster.endeTs(k, t0))
        assertEquals(0L, MahlzeitFenster.endeTs(k, 0L))
        assertEquals(0L, MahlzeitFenster.endeTs(k, t0 + 5 * min))
        assertTrue(MahlzeitFenster.laeuft(k, t0, t0 + 120 * min))
        assertFalse(MahlzeitFenster.laeuft(k, t0, t0 + frist * min))
    }
}
