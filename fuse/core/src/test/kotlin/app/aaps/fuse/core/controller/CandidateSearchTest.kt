package app.aaps.fuse.core.controller

import app.aaps.fuse.core.insulin.InsulinSample
import app.aaps.fuse.core.insulin.KernelOutcome
import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.insulin.UnitInsulinKernelBuilder
import app.aaps.fuse.core.insulin.UnitInsulinSampler
import app.aaps.fuse.core.ledger.AmountStage
import app.aaps.fuse.core.ledger.LedgerConfig
import app.aaps.fuse.core.ledger.LedgerEvent
import app.aaps.fuse.core.ledger.LedgerReducer
import app.aaps.fuse.core.ledger.LedgerState
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.fuse.core.predictor.IsfSlot
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** KC2-04, 05, 06, 25, 36, 37, 48. */
class CandidateSearchTest {

    private val anchor = 1_700_000_000_000L
    private val horizonMin = 120
    private val isf = 100.0

    /**
     * Ein absichtlich EINFACHES Modell: konstante Aktivitaet ueber 120 min,
     * Gesamtwirkung 1 U -> 100 mg/dl. Die Wirkungskurve selbst ist in
     * [app.aaps.fuse.core.insulin.UnitInsulinKernelTest] gegen die echte
     * oref-Formel geprueft; hier geht es um die SUCHE, und die soll man
     * nachrechnen koennen.
     */
    private val model = InsulinModelProvenance("TEST_FLAT", 2.0, 60, "test")

    private fun kernel(deliveryTs: Long = anchor): UnitInsulinKernel {
        val sampler = UnitInsulinSampler { doseU, offsetMin ->
            if (offsetMin >= 120) InsulinSample(0.0, 0.0)
            else InsulinSample(doseU * (1.0 - offsetMin / 120.0), doseU / 120.0)
        }
        return (UnitInsulinKernelBuilder.build(sampler, deliveryTs, model, "flat") as KernelOutcome.Ok).kernel
    }

    private fun prediction(meanAt: (Int) -> Double, lowerAt: (Int) -> Double): PredictorResult {
        val pts = (0..horizonMin).map {
            TrajectoryPoint(it, anchor + it * 60_000L, meanAt(it), lowerAt(it), 0.0, 0.0, 0.0)
        }
        return PredictorResult(
            points = pts,
            minMeanBg = pts.minOf { it.meanBg }, minLowerBg = pts.minOf { it.lowerBg },
            timeToMinLowerMin = 0, bgAtHorizonMean = pts.last().meanBg, bgAtHorizonLower = pts.last().lowerBg,
            lineageKind = "VIRTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 240.0, iobArrayGridMin = 1.0, modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
        )
    }

    private val isfSlots = listOf(IsfSlot(anchor - 3_600_000L, anchor + 10 * 3_600_000L, isf))

    private val band = CandidateSearch.Band(
        releaseTargetLowMgdl = 100.0,
        releaseTargetHighMgdl = 140.0,
        demandDeadbandMgdl = 10.0,
        guardFloorMgdl = 70.0,
        releaseHorizonMin = 30,
        liabilityHorizonMin = 120,
    )

    private fun caps(
        budget: Double = 5.0,
        iobTh: Double = 5.0,
        maxIob: Double = 5.0,
        increment: Double = 0.05,
        maxSmb: Double = 1.0,
    ) = CandidateSearch.Caps(budget, iobTh, maxIob, increment, maxSmb)

    // ---- Eintrittstor ----------------------------------------------------

    @Test
    fun `KC2-48 liegt die Baseline im Zielband, gibt es keinen SMB`() {
        // 145 liegt unter targetHigh+deadband = 150 -> kein Bedarf. Ein
        // Inkrement haette den Mittelwert trotzdem ueber targetLow gelassen;
        // genau diese Luecke hatte v0.2.
        val r = CandidateSearch.search(prediction({ 145.0 }, { 200.0 }), kernel(), isfSlots, band, caps())
        assertEquals(CandidateSearch.Reject.NO_DEMAND, r.reject)
        assertEquals(0.0, r.smbU)
        assertEquals(145.0, r.baselineMeanAtReleaseMgdl)
    }

    @Test
    fun `knapp ueber der Oberkante plus Deadband oeffnet das Tor`() {
        val r = CandidateSearch.search(prediction({ 151.0 }, { 300.0 }), kernel(), isfSlots, band, caps())
        assertNull(r.reject)
        assertTrue(r.smbU > 0.0)
    }

    // ---- Guard und Zielband ----------------------------------------------

    @Test
    fun `KC2-04 gewaehlt wird der groesste Kandidat, der die Guardbahn haelt`() {
        // lower = 130 konstant, Wirkung am Liability-Horizont 100 mg/dl je U:
        // 130 - 100u >= 70  ->  u <= 0,60
        val r = CandidateSearch.search(prediction({ 220.0 }, { 130.0 }), kernel(), isfSlots, band, caps())
        assertNull(r.reject)
        assertEquals(0.60, r.smbU, 1e-12)
        assertTrue(r.minLowerWithCandidateMgdl!! >= 70.0)
        // begrenzt hat der Guard, nicht eine Mengenkappe
        assertEquals("guardFloor", r.bindingLimit)
        // eine Stufe mehr wuerde den Guard reissen
        val tooMuch = 130.0 - 0.65 * (119.0 / 120.0 * isf)
        assertTrue(tooMuch < 70.0, "0,65 U muesste den Guard verletzen, war $tooMuch")
    }

    @Test
    fun `KC2-36 ein guard-sicherer Kandidat, der den Mittelwert unter das Band drueckt, wird verworfen`() {
        // Guard unkritisch (lower 300), aber targetLow 200:
        // 220 - 25u >= 200  ->  u <= 0,80
        val strictBand = band.copy(releaseTargetLowMgdl = 200.0, releaseTargetHighMgdl = 205.0)
        val r = CandidateSearch.search(prediction({ 220.0 }, { 300.0 }), kernel(), isfSlots, strictBand, caps())
        assertNull(r.reject)
        assertEquals(0.80, r.smbU, 1e-12)
        assertTrue(r.meanWithCandidateMgdl!! >= 200.0)
    }

    @Test
    fun `verletzt schon das kleinste Inkrement den Guard, gibt es keinen SMB`() {
        val r = CandidateSearch.search(prediction({ 220.0 }, { 71.0 }), kernel(), isfSlots, band, caps())
        assertEquals(CandidateSearch.Reject.GUARD_FLOOR, r.reject)
        assertEquals(0.0, r.smbU)
    }

    @Test
    fun `der Guard bewertet das MINIMUM der Bahn, nicht ihren Endwert`() {
        // Ein Einbruch bei 40 min, danach harmlos - eine Endwertpruefung
        // wuerde ihn uebersehen.
        val r = CandidateSearch.search(
            prediction({ 220.0 }, { if (it == 40) 100.0 else 300.0 }), kernel(), isfSlots, band, caps()
        )
        assertNull(r.reject)
        // Wirkung bei 40 min: 40/120*100 = 33,33 je U -> 100 - 33,33u >= 70
        assertTrue(r.smbU <= 0.90 + 1e-12, "smb=${r.smbU}")
        assertTrue(r.minLowerWithCandidateMgdl!! >= 70.0)
    }

    // ---- Mengengrenzen ---------------------------------------------------

    @Test
    fun `KC2-06 das Release-Budget begrenzt die Menge und wird benannt`() {
        val r = CandidateSearch.search(
            prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots, band, caps(budget = 0.25)
        )
        assertEquals(0.25, r.smbU, 1e-12)
        assertEquals("releaseBudget", r.bindingLimit)
    }

    @Test
    fun `KC2-25 das Commitment des Ledgers verkleinert iobTH- UND maxIOB-Spielraum`() {
        val ledger = LedgerReducer.reduceAll(
            LedgerState(),
            listOf(
                LedgerEvent.Proposed("p1", 0.30, anchor, anchor - 600_000L),
                LedgerEvent.AmountObserved("p1", AmountStage.RT_PUBLISHED, 0.30),
            ),
            LedgerConfig(bolusStepU = 0.05),
        )
        assertEquals(0.30, ledger.transportCommitmentU, 1e-12)

        val rawIobThHeadroom = 0.50
        val rawMaxIobHeadroom = 2.00
        val effective = caps(
            budget = 5.0,
            iobTh = rawIobThHeadroom - ledger.transportCommitmentU,
            maxIob = rawMaxIobHeadroom - ledger.transportCommitmentU,
        )
        val r = CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots, band, effective)
        assertEquals(0.20, r.smbU, 1e-12)
        assertEquals("iobThHeadroom", r.bindingLimit)

        // ohne die Verrechnung waeren es 0,50 gewesen - also 0,30 U zu viel
        val naive = CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots, band, caps(iobTh = rawIobThHeadroom))
        assertEquals(0.50, naive.smbU, 1e-12)
    }

    @Test
    fun `ein ausgeschoepfter Spielraum ergibt keine Dosis`() {
        val r = CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots, band, caps(iobTh = 0.0))
        assertEquals(CandidateSearch.Reject.NO_HEADROOM, r.reject)
        assertEquals("iobThHeadroom", r.bindingLimit)
    }

    @Test
    fun `unter einer Pumpenstufe wird nicht aufgerundet`() {
        val r = CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots, band, caps(maxSmb = 0.04))
        assertEquals(CandidateSearch.Reject.BELOW_PUMP_INCREMENT, r.reject)
        assertEquals(0.0, r.smbU)
    }

    // ---- Fail-closed -----------------------------------------------------

    @Test
    fun `KC2-37 deckt der Modellhorizont das Bewertungsfenster nicht, gibt es keinen SMB`() {
        val short = InsulinModelProvenance("TEST_SHORT", 1.0, 30, "test")   // 60 min
        val sampler = UnitInsulinSampler { doseU, offsetMin ->
            if (offsetMin >= 60) InsulinSample(0.0, 0.0)
            else InsulinSample(doseU * (1.0 - offsetMin / 60.0), doseU / 60.0)
        }
        val shortKernel = (UnitInsulinKernelBuilder.build(sampler, anchor, short, "short") as KernelOutcome.Ok).kernel
        val r = CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), shortKernel, isfSlots, band, caps())
        assertEquals(CandidateSearch.Reject.MODEL_HORIZON_TOO_SHORT, r.reject)
    }

    @Test
    fun `eine Luecke in den ISF-Slots ergibt keinen Ersatzwert`() {
        val gapped = listOf(IsfSlot(anchor, anchor + 30 * 60_000L, isf))
        val r = CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots = gapped, band, caps())
        assertEquals(CandidateSearch.Reject.ISF_SLOT_MISSING, r.reject)
    }

    @Test
    fun `ein fehlender Horizontpunkt ergibt keine Dosis`() {
        val r = CandidateSearch.search(
            prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots,
            band.copy(liabilityHorizonMin = 240), caps()
        )
        assertEquals(CandidateSearch.Reject.HORIZON_MISSING, r.reject)
    }

    @Test
    fun `ein gesperrter Ledger verhindert jede neue Dosis`() {
        val r = CandidateSearch.search(
            prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots, band, caps(), ledgerHold = true
        )
        assertEquals(CandidateSearch.Reject.LEDGER_HOLD, r.reject)
    }

    // ---- Lieferlatenz ----------------------------------------------------

    @Test
    fun `KC2-05 vor der Lieferung wirkt der Kandidat nicht - auch nicht ein bisschen`() {
        // Lieferung 10 min nach dem Anker: die Wirkung am Release-Horizont
        // faellt entsprechend kleiner aus, und vorher ist sie exakt null.
        val delayed = kernel(anchor + 10 * 60_000L)
        val r = CandidateSearch.search(
            prediction({ 220.0 }, { 130.0 }), delayed, isfSlots,
            band.copy(liabilityHorizonMin = 110), caps()
        )
        assertNull(r.reject)
        // Wirkung am Release-Horizont: nur 20 der 30 min zaehlen
        assertEquals(20.0 / 120.0 * isf, r.effectPerUAtReleaseMgdl!!, 1e-9)
        // und die Bahn VOR der Lieferung bleibt unveraendert
        assertEquals(0.0, delayed.activityAt(anchor + 5 * 60_000L, 1.0))
    }

    @Test
    fun `eine Lieferung vor dem Bahnanfang wird abgewiesen`() {
        val r = CandidateSearch.search(
            prediction({ 400.0 }, { 400.0 }), kernel(anchor - 60_000L), isfSlots, band, caps()
        )
        assertEquals(CandidateSearch.Reject.DELIVERY_BEFORE_ANCHOR, r.reject)
    }
}
