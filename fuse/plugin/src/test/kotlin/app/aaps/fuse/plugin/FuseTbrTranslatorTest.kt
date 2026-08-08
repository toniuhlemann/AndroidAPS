package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.TbrPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Die drei Invarianten, die zwischen Regler, TBR-Tabelle und RT still
 *  falsch sein koennen. */
class FuseTbrTranslatorTest {

    private val cfg = TbrPolicy.Config(basalStepUPerH = 0.05)
    private val scheduled = 0.70

    private fun decision(smb: Double, tbr: FuseController.TbrAction) =
        FuseController.Decision(smb, tbr, FuseController.Block.NONE, 1.5, 180.0, 110.0, "smbRatio")

    private fun running(rate: Double, type: TbrPolicy.SourceType = TbrPolicy.SourceType.TEMP_BASAL) =
        TbrPolicy.Current(rate, 20, type)

    @Test
    fun `jede Reglerkategorie hat genau eine Absicht`() {
        assertEquals(TbrPolicy.Intent.SAFETY_ZERO, FuseTbrTranslator.intentOf(FuseController.TbrAction.ZERO_TEMP))
        assertEquals(TbrPolicy.Intent.NO_POSITIVE, FuseTbrTranslator.intentOf(FuseController.TbrAction.NO_NEW_POSITIVE))
        assertEquals(TbrPolicy.Intent.NO_POSITIVE, FuseTbrTranslator.intentOf(FuseController.TbrAction.CANCEL_TO_SCHEDULED))
        assertEquals(TbrPolicy.Intent.KEEP, FuseTbrTranslator.intentOf(FuseController.TbrAction.KEEP_CURRENT))
        // vollstaendig - eine neue Kategorie faellt beim Kompilieren auf
        assertEquals(4, FuseController.TbrAction.entries.size)
    }

    @Test
    fun `Abbruch ist Rate 0 und Dauer 0 - nicht Profilbasal`() {
        // Der andere Weg haette hier 0,70 U/h geliefert; der Loop haette das
        // ueber die Naehe zu pump.baseBasalRate als "passt schon" gelesen.
        val r = FuseTbrTranslator.combine(
            decision(0.20, FuseController.TbrAction.CANCEL_TO_SCHEDULED), running(1.50), scheduled, cfg
        )
        assertEquals(FuseController.TbrRequest(0.0, 0), r.request)
        assertEquals(0.20, r.decision.smbU, 1e-12)
    }

    @Test
    fun `eine laufende Absenkung wird nicht angetastet`() {
        val r = FuseTbrTranslator.combine(
            decision(0.20, FuseController.TbrAction.NO_NEW_POSITIVE), running(0.20), scheduled, cfg
        )
        assertNull(r.request)
    }

    @Test
    fun `Zero-Temp setzt eine echte Null ueber die volle Dauer`() {
        val r = FuseTbrTranslator.combine(
            decision(0.0, FuseController.TbrAction.ZERO_TEMP), null, scheduled, cfg
        )
        assertEquals(FuseController.TbrRequest(0.0, 30), r.request)
    }

    @Test
    fun `smbBlocked schlaegt auf die Menge durch - sonst dosiert FUSE trotz Sperre`() {
        val blocking = listOf(
            "FAKE_EXTENDED bei unsicherer Bahn" to FuseTbrTranslator.combine(
                decision(0.20, FuseController.TbrAction.ZERO_TEMP),
                running(1.20, TbrPolicy.SourceType.FAKE_EXTENDED), scheduled, cfg
            ),
            "Kernfehler" to FuseTbrTranslator.combine(
                decision(0.20, FuseController.TbrAction.KEEP_CURRENT), running(1.50), scheduled, cfg,
                fault = TbrPolicy.FaultCode.CORE_INPUT_INVALID
            ),
            "kein Safety-Snapshot" to FuseTbrTranslator.combine(
                decision(0.20, FuseController.TbrAction.KEEP_CURRENT), running(1.50), scheduled, cfg,
                fault = TbrPolicy.FaultCode.SAFETY_SNAPSHOT_MISSING
            ),
            "Pumpe beschaeftigt" to FuseTbrTranslator.combine(
                decision(0.20, FuseController.TbrAction.KEEP_CURRENT), running(1.50), scheduled, cfg,
                pumpBusy = true
            ),
        )
        for ((name, r) in blocking) {
            assertEquals(0.0, r.decision.smbU, 1e-12, name)
            assertTrue(r.reason.isNotBlank(), name)
        }
    }

    @Test
    fun `ReadOnlyHold ergibt keine Anforderung, traegt den Grund aber weiter`() {
        val r = FuseTbrTranslator.combine(
            decision(0.20, FuseController.TbrAction.ZERO_TEMP),
            running(1.20, TbrPolicy.SourceType.FAKE_EXTENDED), scheduled, cfg
        )
        assertNull(r.request)
        assertTrue(r.alarm)
        assertEquals("FAKE_EXTENDED_READ_ONLY", r.reason)
    }

    @Test
    fun `ohne Sperre bleibt die Menge unveraendert`() {
        val r = FuseTbrTranslator.combine(
            decision(0.25, FuseController.TbrAction.KEEP_CURRENT), running(0.70), scheduled, cfg
        )
        assertEquals(0.25, r.decision.smbU, 1e-12)
        assertFalse(r.alarm)
        assertNull(r.request)
    }

    // ---- C7a: ein Zertifikat fuer BEIDE Groessen --------------------------

    /**
     * DAS GEGENBEISPIEL DES CONTROL-AUDITS (Codex-Adjudication, "C7
     * combined-action counterexample"): der SMB wird gegen eine Bahn geprueft,
     * in der die laufende Null-TBR steckt; beendet die TBR-Achse diese Null im
     * SELBEN Zyklus, enthaelt die ausgefuehrte Aktion Insulin, das im Zeugnis
     * nicht vorkam (0,30 U zurueckgehaltenes Basal sind bei ISF 80 bis zu
     * 24 mg/dl). Konservative Aufloesung: die Zurueckhaltung BLEIBT, der SMB
     * bleibt - kein Cancel.
     */
    @Test
    fun `C7a ein SMB und das Ende der laufenden Null gelten nicht beide`() {
        val r = FuseTbrTranslator.combine(
            decision(0.20, FuseController.TbrAction.KEEP_CURRENT), running(0.0), scheduled, cfg
        )
        assertNull(r.request)
        assertEquals(0.20, r.decision.smbU, 1e-12)
        assertTrue(r.reason.startsWith(FuseTbrTranslator.C7A_REASON), r.reason)
        // Der unterdrueckte Abbruch bleibt im Grund lesbar.
        assertTrue(r.reason.contains("KEEP_CANCEL_STALE_ZERO"), r.reason)
    }

    /** OHNE Menge bleibt der Abbruch der eigenen Null erhalten - der
     *  Widerspruch "Basal aus und schneller Kanal offen" entsteht erst mit
     *  einem SMB. */
    @Test
    fun `C7a ohne SMB wird die laufende Null weiterhin beendet`() {
        val r = FuseTbrTranslator.combine(
            decision(0.0, FuseController.TbrAction.KEEP_CURRENT), running(0.0), scheduled, cfg
        )
        assertEquals(FuseController.TbrRequest(0.0, 0), r.request)
    }

    /** Die Einseitigkeit: der Abbruch einer POSITIVEN TBR senkt Insulin und
     *  bleibt deshalb auch neben einem SMB bestehen. */
    @Test
    fun `C7a beruehrt den Abbruch einer positiven TBR nicht`() {
        val r = FuseTbrTranslator.combine(
            decision(0.20, FuseController.TbrAction.KEEP_CURRENT), running(1.50), scheduled, cfg
        )
        assertEquals(FuseController.TbrRequest(0.0, 0), r.request)
        assertEquals(0.20, r.decision.smbU, 1e-12)
        assertFalse(r.reason.startsWith(FuseTbrTranslator.C7A_REASON), r.reason)
    }

    /** Auch die ERNEUERUNG einer auslaufenden Null ist kein Ende der
     *  Zurueckhaltung - sie darf nicht am C7a-Veto haengenbleiben. Der SMB ist
     *  auf diesem Pfad ohnehin gesperrt; geprueft wird die Anforderung. */
    @Test
    fun `C7a unterdrueckt keine Erneuerung derselben Null`() {
        val r = FuseTbrTranslator.combine(
            decision(0.20, FuseController.TbrAction.ZERO_TEMP),
            TbrPolicy.Current(0.0, 5, TbrPolicy.SourceType.TEMP_BASAL), scheduled, cfg
        )
        assertEquals(FuseController.TbrRequest(0.0, 30), r.request)
    }
}
