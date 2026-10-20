package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.PartialTbrOwnership
import app.aaps.fuse.core.controller.TbrPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Toni 20.10.2026: der langsame Korrekturkanal ueber dem Normalergebnis ([FuseTbrTranslator.ueberlagereLangsamenKanal]).
 * Die positive TBR laeuft wie das Profilbasal nebenher - keine SMB-Sperre, aber eine gemeinsame maxIOB-Grenze,
 * abgestimmt vor beiden Kommandos desselben Zyklus.
 */
class FuseSlowOverlayTest {

    private val schritt = 0.05

    private fun normal(request: FuseController.TbrRequest?, smb: Double = 0.0) = FuseTbrTranslator.Result(
        request = request,
        decision = FuseController.Decision(smb, FuseController.TbrAction.KEEP_CURRENT, FuseController.Block.NONE, 1.5, 220.0, 110.0, "guard"),
        reason = "NORMAL",
        alarm = false,
        smbBlockCause = if (smb > 0.0) TbrPolicy.SmbBlockCause.NONE else TbrPolicy.SmbBlockCause.SAFETY_ZERO,
    )

    private fun schritt(
        state: PartialTbrOwnership.State = PartialTbrOwnership.State(),
        sendCancel: Boolean = false,
        allowSet: Boolean = false,
    ) = PartialTbrOwnership.Step(
        state = state, smbBlocked = state.smbBlocked || sendCancel, sendCancel = sendCancel, allowSet = allowSet,
        reason = PartialTbrOwnership.Reason.NONE,
    )

    private val laeuft = PartialTbrOwnership.State(confirmedRunning = PartialTbrOwnership.Identity(2.0, 1_000L, 30))
    private val endet = PartialTbrOwnership.State(confirmedRunning = laeuft.confirmedRunning, ending = PartialTbrOwnership.Ending(1_000L))

    private fun ueber(
        n: FuseTbrTranslator.Result,
        want: Boolean,
        own: PartialTbrOwnership.Step,
        rest: Int? = null,
        busy: Boolean = false,
        raum: Double = 5.0,
    ) = FuseTbrTranslator.ueberlagereLangsamenKanal(
        normal = n, want = want, rateUPerH = 2.0, own = own,
        current = rest?.let { TbrPolicy.Current(2.0, it, TbrPolicy.SourceType.TEMP_BASAL) },
        pumpBusy = busy, basalStepUPerH = schritt, smbRaumU = raum, bolusStepU = 0.05,
    )

    /** Der SMB-Teil des Ergebnisses ist der des Normalpfads - dasselbe Objekt, dieselbe Ursache. */
    private fun smbUnberuehrt(n: FuseTbrTranslator.Result, o: FuseTbrTranslator.SlowOverlay) {
        assertSame(n.decision, o.result.decision)
        assertEquals(n.smbBlockCause, o.result.smbBlockCause)
        assertEquals(n.smbSwitchOff, o.result.smbSwitchOff)
        assertEquals(0.0, o.smbCutU, 0.0)
    }

    @Test
    fun `unbeteiligt bleibt das Normalergebnis dasselbe Objekt`() {
        val n = normal(FuseController.TbrRequest(0.0, 30), smb = 0.3)
        val o = ueber(n, want = false, own = schritt())
        assertFalse(o.acted)
        assertSame(n, o.result)
    }

    @Test
    fun `der Einstieg setzt die TBR und der SMB geht ungekuerzt mit`() {
        // Selbst ein knapper Spielraum kuerzt hier nichts: der neue Block ist NEBEN dem SMB bemessen.
        val n = normal(null, smb = 0.2)
        val o = ueber(n, want = true, own = schritt(allowSet = true), raum = 0.0)
        assertTrue(o.acted)
        assertEquals(FuseController.TbrRequest(2.0, 30), o.result.request)
        assertTrue(o.result.reason.startsWith(FuseTbrTranslator.SLOW_SET_REASON))
        smbUnberuehrt(n, o)
    }

    @Test
    fun `eine belegte Pumpe bekommt kein Kommando`() {
        val n = normal(null)
        val o = ueber(n, want = true, own = schritt(allowSet = true), busy = true)
        assertTrue(o.acted)
        assertNull(o.result.request)
        assertTrue(o.result.reason.startsWith(FuseTbrTranslator.SLOW_BUSY_REASON))
        smbUnberuehrt(n, o)
    }

    @Test
    fun `ein laufender Block wird gehalten und ein passender SMB laeuft daneben`() {
        // KEEP_CANCEL_POSITIVE des Normalpfads: Abbruch (0, 0) - unterdrueckt. Der SMB passt neben den Rest.
        val n = normal(FuseController.TbrRequest(0.0, 0), smb = 0.3)
        val o = ueber(n, want = true, own = schritt(laeuft, allowSet = true), rest = 20, raum = 1.0)
        assertTrue(o.acted)
        assertNull(o.result.request)
        assertTrue(o.result.reason.startsWith(FuseTbrTranslator.SLOW_HOLD_REASON))
        smbUnberuehrt(n, o)
        // Auch eine modellbedingte Null ersetzt ihn nicht, solange der Kanal laufen will.
        assertNull(ueber(normal(FuseController.TbrRequest(0.0, 30)), want = true, own = schritt(laeuft, allowSet = true), rest = 20).result.request)
    }

    @Test
    fun `ohne Kanal-Kommando gilt die gemeinsame maxIOB-Grenze schon in diesem Zyklus`() {
        // 0,17 U Spielraum neben dem geplanten Rest: der SMB 0,3 wird auf 0,15 abgerundet.
        val knapp = ueber(normal(null, smb = 0.3), want = true, own = schritt(laeuft, allowSet = true), rest = 20, raum = 0.17)
        assertEquals(0.15, knapp.result.decision.smbU, 1e-9)
        assertEquals(0.15, knapp.smbCutU, 1e-9)
        assertEquals(TbrPolicy.SmbBlockCause.NONE, knapp.result.smbBlockCause)
        // Kein Platz fuer einen Bolusschritt: SMB 0, die Ursache steht da.
        val voll = ueber(normal(null, smb = 0.3), want = true, own = schritt(laeuft, allowSet = true), rest = 20, raum = 0.02)
        assertEquals(0.0, voll.result.decision.smbU, 0.0)
        assertEquals(TbrPolicy.SmbBlockCause.SLOW_CORRECTION, voll.result.smbBlockCause)
        // Auch bei belegter Pumpe (kein Kommando moeglich).
        assertEquals(0.1, ueber(normal(null, smb = 0.3), want = true, own = schritt(allowSet = true), busy = true, raum = 0.1).result.decision.smbU, 1e-9)
        // Ein ohnehin genullter SMB behaelt seine eigene Ursache.
        val schonNull = ueber(normal(null, smb = 0.0), want = true, own = schritt(laeuft, allowSet = true), rest = 20, raum = 0.0)
        assertEquals(TbrPolicy.SmbBlockCause.SAFETY_ZERO, schonNull.result.smbBlockCause)
    }

    @Test
    fun `kurz vor dem Ende wird erneuert - mit dem SMB`() {
        val n = normal(null, smb = 0.2)
        val o = ueber(n, want = true, own = schritt(laeuft, allowSet = true), rest = 5, raum = 0.0)
        assertEquals(FuseController.TbrRequest(2.0, 30), o.result.request)
        smbUnberuehrt(n, o)
    }

    @Test
    fun `der Abbruch ist Rate 0 Dauer 0 - der SMB geht mit, eine Null des Normalpfads geht statt seiner hinaus`() {
        // Der Abbruch macht den Platz - der SMB bleibt ungekuerzt, auch bei knappem Spielraum.
        val mitSmb = normal(null, smb = 0.3)
        val o = ueber(mitSmb, want = false, own = schritt(endet, sendCancel = true), rest = 20, raum = -0.2)
        assertEquals(FuseController.TbrRequest(0.0, 0), o.result.request)
        assertTrue(o.result.reason.startsWith(FuseTbrTranslator.SLOW_END_REASON))
        smbUnberuehrt(mitSmb, o)
        // Will der Normalpfad eine Null setzen (Schutz), geht DIESE hinaus - sie beendet den Block ebenso.
        val n = normal(FuseController.TbrRequest(0.0, 30))
        val z = ueber(n, want = false, own = schritt(endet, sendCancel = true), rest = 20)
        assertSame(n.request, z.result.request)
        assertTrue(z.result.reason.startsWith(FuseTbrTranslator.SLOW_END_BY_ZERO_REASON))
        // Im Backoff des Abbruchs: eine Null darf hinaus, ein Abbruch des Normalpfads nicht doppelt ...
        assertSame(n.request, ueber(n, want = false, own = schritt(endet), rest = 20).result.request)
        assertNull(ueber(normal(FuseController.TbrRequest(0.0, 0)), want = false, own = schritt(endet), rest = 20).result.request)
        // ... und ohne Kommando bleibt der Rest stehen: dann gilt die gemeinsame Grenze fuer den SMB.
        assertEquals(0.1, ueber(mitSmb, want = false, own = schritt(endet), rest = 20, raum = 0.1).result.decision.smbU, 1e-9)
    }
}
