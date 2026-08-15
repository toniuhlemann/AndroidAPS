package app.aaps.fuse.plugin

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.PrimeRelease
import app.aaps.fuse.core.observer.Health
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class FuseDashboardModelTest {

    private val now = 1_700_000_300_000L

    private fun state() = FuseController.State(
        health = Health.READY,
        safetyHold = false,
        phase = app.aaps.fuse.core.observer.Phase.ARMED,
        netIobU = 0.4,
        bolusIobU = 0.6,
        basalIobU = -0.2,
        iobThU = 2.0,
        maxIobU = 4.0,
        targetMgdl = 98.0,
        isfMgdlPerU = 80.0,
        smbRatioCorrection = 0.15,
        smbRatioRise = 0.35,
        rSignedMgdlPerMin = 1.0,
        riseRampLowRPerMin = 0.5,
        riseRampHighRPerMin = 3.0,
        pumpIncrementU = 0.05,
        maxSmbU = 0.3,
        pumpBusy = false,
        mealWindow = true,
    )

    private fun outcome(
        block: FuseController.Block = FuseController.Block.NONE,
        health: Health = Health.READY,
        abort: String? = null,
        prime: PrimeRelease.Plan? = null,
    ) = FuseCycleRunner.Outcome(
        decision = FuseController.Decision(
            smbU = 0.2,
            tbr = FuseController.TbrAction.KEEP_CURRENT,
            block = block,
            insulinReqU = 0.7,
            predAtReleaseMgdl = 130.0,
            minLowerMgdl = 90.0,
            bindingLimit = "smbRatio",
        ),
        tbr = null,
        prediction = null,
        sourceTs = now - 60_000L,
        computeTs = now - 30_000L,
        health = health,
        gate = FusePumpGate.Result(FusePumpGate.Verdict.ALLOWED, "VirtualPumpPlugin"),
        reason = "KEEP",
        alarm = false,
        bgMgdl = 120.0,
        targetMgdl = 98.0,
        targetSource = "profile",
        signal = null,
        band = null,
        discount = null,
        onset = null,
        prime = prime,
        candidate = null,
        candidateGap = null,
        computeDurationMs = 5L,
        mealStats = null,
        policy = null,
        state = state(),
        step = null,
        sensorEpoch = null,
        calibrationEpoch = null,
        isfMgdlPerU = 80.0,
        iobU = 0.4,
        abortReason = abort,
        evidenceEpisodeCapMin = 360,
    )

    private fun ledger(hold: Boolean = false, transport: Double = 0.1) = FuseScreenModel.LedgerInfo(
        hold = hold,
        holdReason = if (hold) "LEDGER_GLOBAL_HOLD" else null,
        holdGeneration = 1L,
        activeErrors = emptyMap(),
        openEntries = 1,
        grossLiabilityU = 0.1,
        transportCommitmentU = transport,
        lastRepairTs = null,
    )

    @Test
    fun `ohne Zyklus zeigt die Kopfsektion keine erfundenen Werte`() {
        val v = FuseDashboardModel.build(
            null, null, now,
            FuseScreenModel.MarkerInfo(0L, 90, 3.0),
            null,
            FuseDashboardModel.ProfileInfo(0.65),
        )
        assertTrue(v.status.contains("WARTET"))
        assertTrue(v.action.contains("Keine aktuelle"))
        assertEquals("IOB -", v.iobLine)
        assertNull(v.transport)
        assertNull(v.windows)
    }

    @Test
    fun `Ledger Hold widerspricht READY bereits in der Kopfzeile`() {
        val v = FuseDashboardModel.build(outcome(), null, now, null, ledger(hold = true), null)
        assertTrue(v.status == "HOLD")
        assertTrue(v.decisionReason.contains("LEDGER_GLOBAL_HOLD"))
        assertTrue(v.gate.contains("Ledger"))
    }

    @Test
    fun `berechnet und angefordert bleiben in der kompakten Aktion getrennt`() {
        val aps = mock<APSResult> {
            on { smb } doReturn 0.15
            on { isTempBasalRequested } doReturn false
        }
        val v = FuseDashboardModel.build(outcome(), aps, now, null, ledger(), null)
        assertTrue(v.action.contains("SMB 0.20 U -> 0.15 U")) { v.action }
    }

    @Test
    fun `Signalzeile nennt Stoerung Modus und wirksamen Anteil`() {
        val v = FuseDashboardModel.build(outcome(), null, now, null, ledger(), null)
        assertTrue(v.signal.contains("Störung r +1.00 mg/dl/min")) { v.signal }
        assertTrue(v.signal.contains("Anstieg"))
        assertTrue(v.signal.contains("SMB-Anteil 0.19"))
    }

    @Test
    fun `Marker zeigt konfiguriert verbraucht verfuegbar und das Freigabefenster`() {
        val markerTs = now - 3 * 60_000L
        val v = FuseDashboardModel.build(
            outcome(prime = PrimeRelease.Plan(true, 0.2, 2.4, "PRIME")),
            null,
            now,
            FuseScreenModel.MarkerInfo(markerTs, 90, 3.0),
            ledger(),
            null,
        )
        assertTrue(v.marker.contains("AKTIV seit 3/90 min")) { v.marker }
        assertTrue(v.marker.contains("publiziert 0.60 U"))
        assertTrue(v.marker.contains("verfuegbar 2.40 U"))
        assertTrue(v.marker.contains("3/15 min Freigabe"))
    }

    @Test
    fun `wirksame IOB Spielraeume ziehen offene Transportmenge ab`() {
        val v = FuseDashboardModel.build(outcome(), null, now, null, ledger(transport = 0.1), null)
        // cap=max(net 0.4, bolus 0.6); iobTH-Rest=2.0-0.6-0.1=1.3
        assertTrue(v.iobLine.contains("IOB 0.40 U")) { v.iobLine }
        assertTrue(v.iobLine.contains("Bolus 0.60"))
        assertTrue(v.iobLine.contains("Basal -0.20"))
        assertTrue(v.limits.contains("iobTH 1.30 U")) { v.limits }
        assertTrue(v.limits.contains("maxIOB 3.30 U"))
        // Die offene Transportmenge bekommt ihre EIGENE Zeile - sie ist die
        // Erklaerung des verengten Spielraums.
        assertTrue(v.transport?.contains("0.10 U") == true) { v.transport ?: "null" }
    }

    @Test
    fun `ohne offene Transportmenge verschwindet die Transportzeile`() {
        val v = FuseDashboardModel.build(outcome(), null, now, null, ledger(transport = 0.0), null)
        assertNull(v.transport)
    }

    @Test
    fun `gleiche Spielraeume werden nur einmal angezeigt`() {
        val equalState = state().copy(iobThU = 4.0, maxIobU = 4.0)
        val equalOutcome = outcome().copy(state = equalState)
        val v = FuseDashboardModel.build(equalOutcome, null, now, null, ledger(transport = 0.1), null)
        assertTrue(v.limits.contains("3.30 U (beide Grenzen)")) { v.limits }
    }

    @Test
    fun `Profilzeile trennt Ziel ISF und planmaessiges Basal`() {
        val v = FuseDashboardModel.build(
            outcome(), null, now, null, ledger(), FuseDashboardModel.ProfileInfo(0.65)
        )
        assertTrue(v.profile.contains("Ziel 98 mg/dl (profile)"))
        assertTrue(v.profile.contains("ISF 80 mg/dl/U"))
        assertTrue(v.profile.contains("Basal 0.65 U/h"))
    }

    // ---- Neu 15.08.: Evidenz- und Fensterzeile ----------------------------

    @Test
    fun `Evidenzzeile zeigt Phase Alter verbucht Bestand und Kredit`() {
        val o = outcome().copy(
            evidenceEpisodeId = now - 124 * 60_000L,
            evidenceEpisodeMin = 124,
            evidencePhase = "ACTIVE",
            evidenceCommittedU = 2.1,
            evidenceStockMgdl = 12.0,
            evidenceCreditMgdlPerMin = 0.4,
        )
        val v = FuseDashboardModel.build(o, null, now, null, ledger(), null)
        assertTrue(v.evidence.contains("Episode AKTIV")) { v.evidence }
        assertTrue(v.evidence.contains("124/360 min"))
        assertTrue(v.evidence.contains("verbucht 2.10 U"))
        assertTrue(v.evidence.contains("Bestand 12 mg/dl"))
        assertTrue(v.evidence.contains("Kredit +0.40/min"))
    }

    @Test
    fun `Evidenzzeile nennt den Denial-Grund menschenlesbar`() {
        val o = outcome().copy(evidenceEpisodeDenial = "MARKER_STALE")
        val v = FuseDashboardModel.build(o, null, now, null, ledger(), null)
        assertTrue(v.evidence.contains("keine Episode")) { v.evidence }
        assertTrue(v.evidence.contains("Marker zu alt"))
    }

    /**
     * DIE ANZEIGE RECHNET UEBER DIESELBE FUNKTION WIE DER REGLER
     * (NightWindow.effectiveDeadbandMgdl). Der 15.08. hat gezeigt, was eine
     * zweite "aehnliche" Formel wert waere: die Verdrahtung fehlte, und eine
     * eigene Anzeige-Formel haette "entwaffnet" gezeigt, waehrend das
     * Totband scharf war.
     */
    @Test
    fun `Totbandzeile scharf ohne Kredit und entwaffnet mit Kredit`() {
        val nacht = state().copy(nightWindow = true, nightDeadbandMgdl = 45.0)
        val scharf = FuseDashboardModel.build(
            outcome().copy(state = nacht), null, now, null, ledger(), null
        )
        assertTrue(scharf.windows?.contains("Nacht-Totband scharf bis 143") == true) { scharf.windows ?: "null" }

        val mitKredit = FuseDashboardModel.build(
            outcome().copy(
                state = nacht,
                evidenceEpisodeId = 1L,
                evidenceEpisodeMin = 10,
                evidencePhase = "ACTIVE",
                evidenceCreditMgdlPerMin = 0.4,
            ),
            null, now, null, ledger(), null
        )
        assertTrue(mitKredit.windows?.contains("Nacht-Totband entwaffnet (Kredit)") == true) { mitKredit.windows ?: "null" }
    }

    @Test
    fun `ohne aktives Fenster gibt es keine Fensterzeile`() {
        val v = FuseDashboardModel.build(outcome(), null, now, null, ledger(), null)
        assertNull(v.windows)
    }
    /** Geraetefund 15.08.: "tailHeadroom=-0.4432277446939927" auf der
     *  Karte. Das Token bleibt, die Zahl wird lesbar. */
    @Test
    fun `lange Dezimalzahlen im Grund-Token werden gekuerzt`() {
        val o = outcome(block = FuseController.Block.TAIL).let {
            it.copy(decision = it.decision.copy(bindingLimit = "tailHeadroom=-0.4432277446939927"))
        }
        val v = FuseDashboardModel.build(o, null, now, null, ledger(), null)
        assertTrue(v.decisionReason.contains("tailHeadroom=-0.44")) { v.decisionReason }
        assertTrue(!v.decisionReason.contains("0.4432277446939927")) { v.decisionReason }
    }
}
