package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.TailLiability
import app.aaps.fuse.core.observer.ActivityValidity
import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.signal.PairSlopeBand
import app.aaps.fuse.core.signal.SignalWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FuseScreenModelTest {

    private val now = 1_700_000_090_000L

    private fun signal(rSigned: Double? = 0.8, bound: SignalWindow.Bound = SignalWindow.Bound.NONE) =
        FuseSignalSource.Signal(
            sourceTs = 1_700_000_000_000L, rawBg = 132.0, q1 = 130.0, rSigned = rSigned,
            ukfRatePerMin = 1.1, ukfLearnedR = 2.2, rawSlopePerMin = 1.4, activityAtAnchor = 0.01, isfAtAnchor = 90.0,
            adjusted = emptyList(), activity = ActivityValidity.VALID,
            samplesUsed = 19, rawSeriesSize = 200, q1Outlier = false,
            boundedBy = bound, windowFromTs = 1_699_988_120_000L,
        )

    private fun outcome(
        smbU: Double = 0.15,
        block: FuseController.Block = FuseController.Block.NONE,
        abort: String? = null,
        signal: FuseSignalSource.Signal? = signal(),
        tail: TailLiability.Report? = null,
    ) = FuseCycleRunner.Outcome(
        decision = FuseController.Decision(
            smbU, FuseController.TbrAction.KEEP_CURRENT, block, 1.4, 180.0, 95.0, "smbRatio",
            tail, if (tail != null) 0.05 else 0.0,
        ),
        tbr = null, prediction = null, sourceTs = signal?.sourceTs, computeTs = 1_700_000_030_000L,
        health = Health.READY, gate = FusePumpGate.Result(FusePumpGate.Verdict.ALLOWED, "VirtualPumpPlugin"),
        reason = "KEEP", alarm = false, bgMgdl = 130.0, targetMgdl = 97.0, targetSource = "TT",
        signal = signal, band = PairSlopeBand.Estimate(0.8, 0.4, 153),
        discount = app.aaps.fuse.core.predictor.DriveDiscount.apply(0.8, 0.4, 0.01, 85.0, 1.0),
        onset = app.aaps.fuse.core.controller.OnsetChannel.Result(false, false, null, 1.5, "R_CONFIRMED"),
        prime = app.aaps.fuse.core.controller.PrimeRelease.Plan(false, 0.0, 1.2, "NO_MARKER"), policy = null,
        state = null, step = null, sensorEpoch = null, calibrationEpoch = null,
        isfMgdlPerU = 85.0, iobU = 1.2, abortReason = abort,
    )

    /** Ohne Zyklus GENAU eine Zeile — kein Geruest aus Nullen, das wie ein
     *  Ergebnis aussieht. */
    @Test
    fun `ohne Lauf steht genau eine Zeile auf dem Schirm`() {
        val t = FuseScreenModel.render(null, null, now)
        assertEquals(1, t.trim().lines().size)
        assertFalse(t.contains("0.00"))
    }

    @Test
    fun `ein Abbruchzyklus zeigt den Grund und erfindet keine Zahlen`() {
        val t = FuseScreenModel.render(outcome(abort = "signal: no raw glucose values", signal = null), null, now)
        assertTrue(t.contains("ABBRUCH"))
        assertTrue(t.contains("no raw glucose values"))
        // Kein Signal -> "-" statt einer Zahl.
        assertTrue(t.contains("Signal          -"))
    }

    /**
     * DER WICHTIGSTE FALL: berechnet und angefordert muessen GETRENNT stehen.
     * Sonst liest man eine Menge, die der Pumpen-Riegel laengst genullt hat, als
     * abgegeben.
     */
    @Test
    fun `berechnet und angefordert stehen getrennt`() {
        val t = FuseScreenModel.render(outcome(smbU = 0.15), null, now)
        assertTrue(t.contains("berechnet"))
        assertTrue(t.contains("angefordert"))
        assertTrue(t.contains("0.15 U SMB"))
    }

    @Test
    fun `nicht berechenbares r wird benannt, nicht als Null gezeigt`() {
        val t = FuseScreenModel.render(outcome(signal = signal(rSigned = null)), null, now)
        assertTrue(t.contains("nicht berechenbar"))
        assertFalse(t.contains("r               0.000"))
    }

    /** Die Fenstergrenze erscheint NUR, wenn sie gegriffen hat — sonst waere sie
     *  Rauschen in jeder Zeile. */
    @Test
    fun `die Fenstergrenze erscheint nur wenn sie gegriffen hat`() {
        assertFalse(FuseScreenModel.render(outcome(), null, now).contains("Fenster ab"))
        val t = FuseScreenModel.render(outcome(signal = signal(bound = SignalWindow.Bound.CALIBRATION_START)), null, now)
        assertTrue(t.contains("Fenster ab      CALIBRATION_START"))
    }

    @Test
    fun `der Schwanzbericht zeigt seinen Vermerk mit`() {
        val tail = TailLiability.evaluate(TailLiability.Input(120.0, 0.5, 85.0, 70.0, 0.0))
        val t = FuseScreenModel.render(outcome(tail = tail), null, now)
        assertTrue(t.contains("Schwanz"))
        assertTrue(t.contains(TailLiability.COMPLETENESS_STAGE1))
    }

    /** Ein unbrauchbarer Schwanzbericht darf nicht wie ein Ergebnis aussehen. */
    @Test
    fun `ein unbrauchbarer Schwanzbericht wird als solcher gezeigt`() {
        val kaputt = TailLiability.evaluate(TailLiability.Input(120.0, 0.5, 0.0, 70.0, 0.0))
        val t = FuseScreenModel.render(outcome(tail = kaputt), null, now)
        assertTrue(t.contains("unbrauchbar"))
    }

    @Test
    fun `Block und Phase sind getrennte Zeilen`() {
        val t = FuseScreenModel.render(outcome(block = FuseController.Block.TAIL), null, now)
        assertTrue(t.contains("Block           TAIL"))
        // Phase kommt vom Observer und ist hier unbekannt - also "-", nicht der
        // Blockname unter falschem Etikett.
        assertTrue(t.contains("Phase           -"))
    }

    @Test
    fun `das Ziel nennt seine Herkunft`() {
        assertTrue(FuseScreenModel.render(outcome(), null, now).contains("97 (TT)"))
    }

    @Test
    fun `das Alter des Laufs wird gezeigt`() {
        assertTrue(FuseScreenModel.render(outcome(), null, now).contains("vor 1 min"))
    }
}
