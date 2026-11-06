package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.MealBalance
import app.aaps.fuse.core.controller.PartialTbrOwnership
import app.aaps.fuse.core.controller.TbrPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Entwurf B, Toni 05.11.2026: die Basal-Ruecknahme der Rechner-Mahlzeit ueber dem Ergebnis
 * ([FuseTbrTranslator.ueberlagereRuecknahme]) - Setzen, Erneuern, Ersetzen und Abbruch ueber den eigenen Teil-TBR-
 * Lebenszyklus, eine Null des Normalpfads geht immer vor, und den SMB fasst sie nie an.
 */
class FuseWithdrawalOverlayTest {

    private val schritt = 0.05

    private fun normal(request: FuseController.TbrRequest?, smb: Double = 0.0) = FuseTbrTranslator.Result(
        request = request,
        decision = FuseController.Decision(smb, FuseController.TbrAction.KEEP_CURRENT, FuseController.Block.NONE, 0.0, 110.0, 90.0, "-"),
        reason = "NORMAL",
        alarm = false,
        smbBlockCause = TbrPolicy.SmbBlockCause.NONE,
    )

    private fun schritt(
        state: PartialTbrOwnership.State = PartialTbrOwnership.State(),
        sendCancel: Boolean = false,
        allowSet: Boolean = false,
    ) = PartialTbrOwnership.Step(
        state = state, smbBlocked = state.smbBlocked || sendCancel, sendCancel = sendCancel, allowSet = allowSet,
        reason = PartialTbrOwnership.Reason.NONE,
    )

    private val laeuft = PartialTbrOwnership.State(confirmedRunning = PartialTbrOwnership.Identity(0.25, 1_000L, 30))
    private val endet = PartialTbrOwnership.State(confirmedRunning = laeuft.confirmedRunning, ending = PartialTbrOwnership.Ending(1_000L))

    private fun ueber(
        n: FuseTbrTranslator.Result,
        want: Boolean,
        own: PartialTbrOwnership.Step,
        rate: Double = 0.25,
        rest: Int? = null,
        busy: Boolean = false,
    ) = FuseTbrTranslator.ueberlagereRuecknahme(
        normal = n, want = want, rateUPerH = rate, own = own,
        current = rest?.let { TbrPolicy.Current(0.25, it, TbrPolicy.SourceType.TEMP_BASAL) },
        pumpBusy = busy, basalStepUPerH = schritt,
    )

    @Test
    fun `unbeteiligt bleibt das Normalergebnis dasselbe Objekt`() {
        val n = normal(FuseController.TbrRequest(0.0, 30), smb = 0.3)
        val o = ueber(n, want = false, own = schritt())
        assertFalse(o.acted)
        assertSame(n, o.result)
    }

    @Test
    fun `gewuenscht und erlaubt setzt sie einen 30-min-Befehl, den SMB fasst sie nicht an`() {
        val n = normal(null)
        val o = ueber(n, want = true, own = schritt(allowSet = true))
        assertTrue(o.acted)
        assertEquals(FuseController.TbrRequest(0.25, MealBalance.COMMAND_MIN), o.result.request)
        assertTrue(o.result.reason.startsWith(FuseTbrTranslator.WITHDRAWAL_SET_REASON))
        assertSame(n.decision, o.result.decision)
    }

    @Test
    fun `dieselbe Rate wird erst kurz vor dem Ende erneuert, eine andere sofort ersetzt`() {
        val n = normal(null)
        val bestaetigt = schritt(state = laeuft, allowSet = true)
        // Gleiche Rate, noch 20 min: kein Kommando, die eigene Absenkung haelt den Normalpfad still.
        val halten = ueber(n, want = true, own = bestaetigt, rest = 20)
        assertTrue(halten.acted)
        assertNull(halten.result.request)
        assertTrue(halten.result.reason.startsWith(FuseTbrTranslator.WITHDRAWAL_HOLD_REASON))
        // Gleiche Rate, noch 5 min: Erneuerung.
        assertEquals(FuseController.TbrRequest(0.25, 30), ueber(n, want = true, own = bestaetigt, rest = 5).result.request)
        // Andere Rate: Ersetzen, egal wie viel noch laeuft.
        assertEquals(FuseController.TbrRequest(0.1, 30), ueber(n, want = true, own = bestaetigt, rate = 0.1, rest = 20).result.request)
    }

    @Test
    fun `der Abbruch geht als Rate 0 Dauer 0 hinaus - eine Null des Normalpfads geht vor`() {
        val abbruch = ueber(normal(null), want = false, own = schritt(state = endet, sendCancel = true))
        assertTrue(abbruch.acted)
        assertEquals(FuseController.TbrRequest(0.0, 0), abbruch.result.request)
        assertTrue(abbruch.result.reason.startsWith(FuseTbrTranslator.WITHDRAWAL_END_REASON))
        // Die Schutz-Null des Normalpfads: sie beendet die Absenkung ebenso und schuetzt sofort - dasselbe Kommando-Objekt.
        val null30 = FuseController.TbrRequest(0.0, 30)
        val n = normal(null30)
        val mitNull = ueber(n, want = false, own = schritt(state = endet, sendCancel = true))
        assertSame(null30, mitNull.result.request)
        assertTrue(mitNull.result.reason.startsWith(FuseTbrTranslator.WITHDRAWAL_END_BY_ZERO_REASON))
        // Auch ohne faelligen Abbruch: laeuft die eigene Absenkung, geht eine Null des Normalpfads hinaus.
        assertSame(null30, ueber(n, want = false, own = schritt(state = laeuft)).result.request)
    }

    @Test
    fun `fliesst ein SMB, endet die Absenkung nicht im selben Zyklus`() {
        val n = normal(null, smb = 0.2)
        val o = ueber(n, want = false, own = schritt(state = endet, sendCancel = true))
        assertTrue(o.acted)
        assertNull(o.result.request)
        assertTrue(o.result.reason.startsWith(FuseTbrTranslator.WITHDRAWAL_HOLD_SMB_REASON))
        assertSame(n.decision, o.result.decision)
        // Und neben einem SMB wird nie gesetzt.
        assertFalse(ueber(n, want = true, own = schritt(allowSet = true)).acted)
    }

    @Test
    fun `eine belegte Pumpe bekommt kein Kommando`() {
        val setzen = ueber(normal(null), want = true, own = schritt(allowSet = true), busy = true)
        assertTrue(setzen.acted)
        assertNull(setzen.result.request)
        assertTrue(setzen.result.reason.startsWith(FuseTbrTranslator.WITHDRAWAL_BUSY_REASON))
        val abbrechen = ueber(normal(null), want = false, own = schritt(state = endet, sendCancel = true), busy = true)
        assertNull(abbrechen.result.request)
        assertTrue(abbrechen.result.reason.startsWith(FuseTbrTranslator.WITHDRAWAL_BUSY_REASON))
    }

    @Test
    fun `ohne Setzerlaubnis haelt eine offene eigene Anforderung den Normalpfad still`() {
        val offen = PartialTbrOwnership.State(pendingRequest = PartialTbrOwnership.Identity(0.25, 1_000L, 30))
        val n = normal(FuseController.TbrRequest(0.45, 0))
        val o = ueber(n, want = true, own = schritt(state = offen, allowSet = false))
        assertTrue(o.acted)
        assertNull(o.result.request)
    }
}
