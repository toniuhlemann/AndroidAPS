package app.aaps.fuse.plugin

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.PrimeRelease
import app.aaps.fuse.core.observer.Health
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
        assertTrue(v.limits.contains("unbekannt"))
    }

    @Test
    fun `Ledger Hold widerspricht READY bereits in der Kopfzeile`() {
        val v = FuseDashboardModel.build(outcome(), null, now, null, ledger(hold = true), null)
        assertTrue(v.status == "HOLD")
        assertTrue(v.decisionReason.contains("LEDGER_GLOBAL_HOLD"))
        assertTrue(v.hardStops.contains("Ledger"))
    }

    @Test
    fun `berechnet und angefordert bleiben in der kompakten Aktion getrennt`() {
        val aps = mock<APSResult> {
            on { smb } doReturn 0.15
            on { isTempBasalRequested } doReturn false
        }
        val v = FuseDashboardModel.build(outcome(), aps, now, null, ledger(), null)
        assertTrue(v.action.contains("0.20 U berechnet"))
        assertTrue(v.action.contains("0.15 U angefordert"))
    }

    @Test
    fun `Marker zeigt konfiguriert verbraucht verfuegbar und beide Fenster`() {
        val markerTs = now - 3 * 60_000L
        val v = FuseDashboardModel.build(
            outcome(prime = PrimeRelease.Plan(true, 0.2, 2.4, "PRIME")),
            null,
            now,
            FuseScreenModel.MarkerInfo(markerTs, 90, 3.0),
            ledger(),
            null,
        )
        assertTrue(v.marker.contains("publiziert 0.60 U"))
        assertTrue(v.marker.contains("verfuegbar 2.40 U"))
        assertTrue(v.marker.contains("3/15 min Freigabe"))
        assertTrue(v.marker.contains("3/45 min Wandzeit"))
        assertTrue(v.marker.contains("3/90 min Kontext"))
    }

    @Test
    fun `wirksame IOB Spielraeume ziehen offene Transportmenge ab`() {
        val v = FuseDashboardModel.build(outcome(), null, now, null, ledger(transport = 0.1), null)
        // cap=max(net 0.4, bolus 0.6); iobTH-Rest=2.0-0.6-0.1=1.3
        assertTrue(v.limits.contains("iobTH 2.00 U (Rest 1.30 U)"))
        assertTrue(v.limits.contains("maxIOB 4.00 U (Rest 3.30 U)"))
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
}
