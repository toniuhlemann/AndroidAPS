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
import app.aaps.fuse.core.predictor.DriveDecayModel
import app.aaps.fuse.core.predictor.DriveEstimate
import app.aaps.fuse.core.predictor.InsulinLineage
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.fuse.core.predictor.IobPoint
import app.aaps.fuse.core.predictor.IsfSlot
import app.aaps.fuse.core.predictor.PredictorInput
import app.aaps.fuse.core.predictor.PredictorOutcome
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryCore
import app.aaps.fuse.core.predictor.TrajectoryPoint
import app.aaps.fuse.core.predictor.VirtualTrajectoryFactory
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

    /**
     * Punkte ab offsetMin = 1 — wie der echte `TrajectoryCore` (R81-F1). Die
     * frueheren Testpunkte begannen bei 0 und haben damit genau den Fehler
     * verdeckt, den `EchteBahn...` unten nachweist.
     */
    private fun prediction(meanAt: (Int) -> Double, lowerAt: (Int) -> Double): PredictorResult {
        val pts = (1..horizonMin).map {
            TrajectoryPoint(it, anchor + it * 60_000L, meanAt(it), lowerAt(it), 0.0, 0.0, 0.0)
        }
        return PredictorResult(
            points = pts,
            predictionAnchorTs = anchor,
            bgAtAnchor = meanAt(0),
            // R85-F5: die Minima schliessen den ANKER ein - genau wie beim
            // echten TrajectoryCore. Ein Testobjekt, das dem Vertrag nicht
            // folgt, prueft am Ende nur sich selbst.
            minMeanBg = minOf(meanAt(0), pts.minOf { it.meanBg }),
            minLowerBg = minOf(meanAt(0), pts.minOf { it.lowerBg }),
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

    // ---- R79-F5 / F7 -----------------------------------------------------

    @Test
    fun `R79-F5 eine Lieferung hinter dem Freigabehorizont wird nicht als Maximalkandidat genehmigt`() {
        // Ohne die Pruefung waere effectPerU im ganzen Fenster null: Bedarf,
        // Guard und Zielband blieben unveraendert, und die Suche haette den
        // groessten durch die Kappen erlaubten Kandidaten zurueckgegeben.
        val late = kernel(anchor + 45 * 60_000L)      // Release-Horizont ist 30 min
        val r = CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), late, isfSlots, band, caps())
        assertEquals(CandidateSearch.Reject.DELIVERY_AFTER_RELEASE, r.reject)
        assertEquals(0.0, r.smbU)
    }

    @Test
    fun `R79-F5 genau auf dem Freigabehorizont zaehlt schon als zu spaet`() {
        val onTheDot = kernel(anchor + 30 * 60_000L)
        val r = CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), onTheDot, isfSlots, band, caps())
        assertEquals(CandidateSearch.Reject.DELIVERY_AFTER_RELEASE, r.reject)
    }

    @Test
    fun `R79-F5 ein im Fenster wirkungsloser Kandidat wird abgewiesen`() {
        // Kernel ohne Aktivitaet: IOB faellt, aber es wirkt nichts.
        val inert = UnitInsulinSampler { doseU, offsetMin ->
            if (offsetMin >= 120) InsulinSample(0.0, 0.0) else InsulinSample(doseU * (1.0 - offsetMin / 120.0), 0.0)
        }
        val inertKernel = (UnitInsulinKernelBuilder.build(inert, anchor, model, "inert") as KernelOutcome.Ok).kernel
        val r = CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), inertKernel, isfSlots, band, caps())
        assertEquals(CandidateSearch.Reject.NO_EFFECT_IN_WINDOW, r.reject)
    }

    @Test
    fun `R79-F5 der Freigabehorizont darf nicht hinter dem Guard-Horizont liegen`() {
        val r = CandidateSearch.search(
            prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots,
            band.copy(releaseHorizonMin = 60, liabilityHorizonMin = 30), caps()
        )
        assertEquals(CandidateSearch.Reject.INVALID_BAND, r.reject)
        assertTrue(r.bindingLimit.contains("releaseHorizon"))
    }

    // ---- R81-F1: Ankervertrag gegen den ECHTEN Predictor ------------------

    /** Die Bahn kommt aus `TrajectoryCore`, nicht aus handgebauten Punkten.
     *  Genau dieser Vertrag war gebrochen: echte Punkte beginnen bei Minute 1. */
    private fun realPrediction(
        bgAtAnchor: Double = 220.0,
        drive: Double = 0.0,
        driveLower: Double = drive,
        /** Prior-freier Zwilling des unteren Antriebs (C2); null = kein Prior. */
        driveLowerPriorFree: Double? = null,
    ): PredictorResult {
        val gridMs = 60_000L
        val n = horizonMin + 60
        val pts = (0..n).map {
            IobPoint(anchor + it * gridMs, iob = 1.0, activity = 0.0, basalIob = 0.0)
        }
        val trajectory = VirtualTrajectoryFactory.of(
            lineage = InsulinLineage.VirtualController("session", 1L, "modelhash"),
            points = pts,
            arrayAsOfTs = anchor,
            model = InsulinModelProvenance("TEST_FLAT", 3.0, 60, "test"),
            iobCalculationHash = "hash",
        )
        val outcome = TrajectoryCore.predict(
            PredictorInput(
                predictionAnchorTs = anchor,
                bgAtAnchor = bgAtAnchor,
                drive = DriveEstimate(drive, driveLower, 0.8, "test", driveLowerPriorFree),
                decay = DriveDecayModel.ExponentialDecay(60.0),
                trajectory = trajectory,
                isfSlots = listOf(IsfSlot(anchor - 3_600_000L, anchor + 10 * 3_600_000L, isf)),
                horizonMin = horizonMin,
            )
        )
        assertTrue(outcome is PredictorOutcome.Ok, "predictor rejected: $outcome")
        return (outcome as PredictorOutcome.Ok).result
    }

    @Test
    fun `R81-F1 eine echte Predictor-Bahn wird akzeptiert und die erste Minute zaehlt mit`() {
        val real = realPrediction()
        // Vertrag des echten Kerns: erster Punkt bei Minute 1.
        assertEquals(1, real.points.first().offsetMin)
        assertEquals(anchor, real.predictionAnchorTs)

        // Lieferung GENAU am Anker - der Normalfall, der vorher als
        // DELIVERY_BEFORE_ANCHOR abgewiesen wurde.
        val r = CandidateSearch.search(real, kernel(anchor), isfSlots, band, caps())
        assertNull(r.reject, "reject=${r.reject} (${r.bindingLimit})")
        assertTrue(r.smbU > 0.0)

        // Die erste Minute nach dem Anker ist integriert: 30 volle Minuten,
        // nicht 29.
        assertEquals(30.0 / 120.0 * isf, r.effectPerUAtReleaseMgdl!!, 1e-9)
    }

    @Test
    fun `R81-F1 ein Punktraster, das nicht zum Anker passt, wird abgewiesen`() {
        val real = realPrediction()
        val shifted = real.copy(predictionAnchorTs = anchor + 17_000L)
        val r = CandidateSearch.search(shifted, kernel(anchor), isfSlots, band, caps())
        assertEquals(CandidateSearch.Reject.GRID_INCONSISTENT, r.reject)
    }

    @Test
    fun `R81-F1 eine Lieferung vor dem Anker bleibt abgewiesen`() {
        val r = CandidateSearch.search(realPrediction(), kernel(anchor - 1L), isfSlots, band, caps())
        assertEquals(CandidateSearch.Reject.DELIVERY_BEFORE_ANCHOR, r.reject)
    }

    // ---- R83-F1 / F6 ------------------------------------------------------

    @Test
    fun `R83-F1 ein BG unter dem Floor AM ANKER verhindert jeden SMB`() {
        // 68 am Anker, Minute 1 schon wieder ueber dem Floor, danach steiler
        // Anstieg mit klarem Bedarf. Der Guard laeuft ueber [0..H] - wer erst
        // bei Minute 1 anfaengt, gibt hier frei.
        val real = realPrediction(bgAtAnchor = 68.0, drive = 6.0)
        assertTrue(real.points.first().lowerBg > band.guardFloorMgdl, "Minute 1 = ${real.points.first().lowerBg}")
        assertTrue(real.points[29].meanBg > band.releaseTargetHighMgdl + band.demandDeadbandMgdl)

        val r = CandidateSearch.search(real, kernel(anchor), isfSlots, band, caps())
        assertEquals(CandidateSearch.Reject.GUARD_FLOOR, r.reject)
        assertEquals(0.0, r.smbU)

        // Gegenprobe: derselbe Verlauf mit sicherem Anker gibt frei.
        val safe = realPrediction(bgAtAnchor = 120.0, drive = 6.0)
        assertNull(CandidateSearch.search(safe, kernel(anchor), isfSlots, band, caps()).reject)
    }

    @Test
    fun `R83-F1 der Ankerwert selbst geht dosisunabhaengig in das Minimum ein`() {
        // Steil steigende Bahn: ALLE Zukunftspunkte bleiben auch mit dem
        // groessten zulaessigen Kandidaten oberhalb des Ankers. Dann - und nur
        // dann - ist das Minimum exakt der Ankerwert.
        //
        // Die fruehere Fassung pruefte `minLower <= 71` und behauptete damit
        // Dosisunabhaengigkeit, die daraus gar nicht folgt: das GESAMTminimum
        // darf sehr wohl durch Kandidatenwirkung in einer spaeteren Minute
        // unter den Ankerwert fallen. Dosisunabhaengig ist nur der Wert bei t=0.
        val real = realPrediction(bgAtAnchor = 71.0, drive = 6.0)
        val r = CandidateSearch.search(real, kernel(anchor), isfSlots, band, caps())
        assertNull(r.reject)
        assertTrue(r.smbU > 0.0)
        assertEquals(71.0, r.minLowerWithCandidateMgdl!!, 1e-9)

        // Gegenprobe zur Konstruktion: der tiefste Zukunftspunkt liegt auch mit
        // der gewaehlten Dosis ueber dem Anker.
        val lowestFuture = real.points.indices.minOf { i ->
            real.points[i].lowerBg - r.smbU * (minOf(i + 1, 119) / 120.0 * isf)
        }
        assertTrue(lowestFuture > 71.0, "tiefster Zukunftspunkt war $lowestFuture")
    }

    @Test
    fun `R83-F6 ein unsortiertes, lueckenhaftes oder doppeltes Raster wird abgewiesen`() {
        val real = realPrediction()
        val p = real.points
        val cases = mapOf(
            "unsortiert" to p.toMutableList().also { val x = it[1]; it[1] = it[2]; it[2] = x },
            "luecke" to p.filterIndexed { i, _ -> i != 5 },
            "duplikat" to p.toMutableList().also { it[3] = it[2] },
        )
        for ((name, pts) in cases) {
            val r = CandidateSearch.search(real.copy(points = pts), kernel(anchor), isfSlots, band, caps())
            assertEquals(CandidateSearch.Reject.GRID_INCONSISTENT, r.reject, name)
        }
    }

    @Test
    fun `R79-F7 ungueltige Konfiguration wirft nicht, sondern wird benannt abgelehnt`() {
        val nan = Double.NaN
        assertEquals(
            CandidateSearch.Reject.INVALID_BAND,
            CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots, band.copy(guardFloorMgdl = nan), caps()).reject
        )
        assertEquals(
            CandidateSearch.Reject.INVALID_BAND,
            CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots, band.copy(releaseTargetLowMgdl = 200.0, releaseTargetHighMgdl = 100.0), caps()).reject
        )
        assertEquals(
            CandidateSearch.Reject.INVALID_CAPS,
            CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots, band, caps(increment = 0.0)).reject
        )
        assertEquals(
            CandidateSearch.Reject.INVALID_CAPS,
            CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots, band, caps(budget = nan)).reject
        )
        assertEquals(
            CandidateSearch.Reject.INVALID_CAPS,
            CandidateSearch.search(prediction({ 400.0 }, { 400.0 }), kernel(), isfSlots, band, caps(maxSmb = -1.0)).reject
        )
    }

    // ---- FIX 6b: finale Wirkungspruefung der Sofort-Freigabe ---------------

    /** Flache Bahn bei 120, Boden 70, 1 U wirkt 100 mg/dl bis Minute 120:
     *  0,3 U druecken auf 90 (sicher), 0,6 U auf 60 (Boden gerissen). */
    @Test
    fun `verifyGuardFloor laesst sichere Mengen durch und reisst am Boden`() {
        val p = prediction({ 120.0 }, { 120.0 })
        assertNull(CandidateSearch.verifyGuardFloor(p, kernel(), isfSlots, band, 0.3))
        assertEquals(
            CandidateSearch.Reject.GUARD_FLOOR,
            CandidateSearch.verifyGuardFloor(p, kernel(), isfSlots, band, 0.6)
        )
    }

    /** Technische Ausfaelle sind bei der NACHPRUEFUNG fail-closed - anders
     *  als beim Suche-Gate gibt es hier keinen fail-open-Basispfad. */
    @Test
    fun `verifyGuardFloor lehnt technische Ausfaelle ab`() {
        val p = prediction({ 120.0 }, { 120.0 })
        assertEquals(
            CandidateSearch.Reject.NON_FINITE,
            CandidateSearch.verifyGuardFloor(p, kernel(), isfSlots, band, Double.NaN)
        )
        assertEquals(
            CandidateSearch.Reject.NON_FINITE,
            CandidateSearch.verifyGuardFloor(p, kernel(), isfSlots, band, 0.0)
        )
        // Lieferung hinter dem Freigabehorizont: Wirkung im Fenster unpruefbar.
        assertEquals(
            CandidateSearch.Reject.DELIVERY_AFTER_RELEASE,
            CandidateSearch.verifyGuardFloor(p, kernel(deliveryTs = anchor + 31 * 60_000L), isfSlots, band, 0.3)
        )
        // ISF-Luecke im Haftungsfenster.
        assertEquals(
            CandidateSearch.Reject.ISF_SLOT_MISSING,
            CandidateSearch.verifyGuardFloor(
                p, kernel(), listOf(IsfSlot(anchor, anchor + 30 * 60_000L, isf)), band, 0.3
            )
        )
    }

    /**
     * Codex-P0 22.08. (Liveness-Kanal): [CandidateSearch.verifyTechnicalIntegrity]
     * ist EXAKT [CandidateSearch.verifyGuardFloor] ohne das semantische
     * Urteil. Der Diskriminator: eine Menge, die den Boden reisst, ist
     * technisch einwandfrei - und jeder technische Ausfall ist in beiden
     * Pruefungen identisch typisiert.
     */
    @Test
    fun `verifyTechnicalIntegrity laesst nur das semantische Urteil aus`() {
        val p = prediction({ 120.0 }, { 120.0 })
        // Semantik-Diskriminator: 0,6 U reissen den Boden (GUARD_FLOOR) -
        // die Integritaetskette allein hat daran nichts auszusetzen.
        assertEquals(
            CandidateSearch.Reject.GUARD_FLOOR,
            CandidateSearch.verifyGuardFloor(p, kernel(), isfSlots, band, 0.6)
        )
        assertNull(CandidateSearch.verifyTechnicalIntegrity(p, kernel(), isfSlots, band, 0.6))
        // Technische Ausfaelle: in beiden identisch.
        assertEquals(
            CandidateSearch.Reject.NON_FINITE,
            CandidateSearch.verifyTechnicalIntegrity(p, kernel(), isfSlots, band, Double.NaN)
        )
        assertEquals(
            CandidateSearch.Reject.ISF_SLOT_MISSING,
            CandidateSearch.verifyTechnicalIntegrity(
                p, kernel(), listOf(IsfSlot(anchor, anchor + 30 * 60_000L, isf)), band, 0.3
            )
        )
        assertEquals(
            CandidateSearch.Reject.DELIVERY_AFTER_RELEASE,
            CandidateSearch.verifyTechnicalIntegrity(
                p, kernel(deliveryTs = anchor + 31 * 60_000L), isfSlots, band, 0.3
            )
        )
    }

    // ---- C1: die BREMSBAHN in jeder Mit-Dosis-Pruefung ---------------------
    // Codex-Adjudication (D-Tabelle C1, H1, K2 Punkt 6): der Baseline-Guard nahm
    // laengst das Minimum aus Haupt- und Bremsbahn, search/verifyGuardFloor
    // bekamen aber nur die Hauptbahn. Die Wirkung einer konkreten Dosis wurde
    // damit NIE gegen die pessimistischere Bahn geprueft.

    /**
     * Codex' kuerzestes Gegenbeispiel, Zahl fuer Zahl:
     *
     *   Boden 70, Hauptbahn-lower 95, Bremsbahn-lower 74
     *   kleinster Kandidat 0,05 U wirkt am Haftungshorizont
     *     0,05 * 119/120 * 100 = 4,96 mg/dl
     *   Hauptbahn  95 - 4,96 = 90,04  -> passiert
     *   Bremsbahn  74 - 4,96 = 69,04  -> muss sperren
     *
     * Der erste Teil des Tests haelt das ALTE Verhalten fest (restraint = null),
     * der zweite das neue - so steht die Regression im Test selbst.
     */
    @Test
    fun `C1 die Bremsbahn wird MIT der Kandidatenwirkung geprueft`() {
        val main = prediction({ 220.0 }, { 95.0 })
        val brake = prediction({ 220.0 }, { 74.0 })
        val c = caps(maxSmb = 0.05)

        val mainOnly = CandidateSearch.search(main, kernel(), isfSlots, band, c)
        assertNull(mainOnly.reject, "Hauptbahn allein laesst 0,05 U durch (Gegenbeispiel)")
        assertEquals(0.05, mainOnly.smbU, 1e-12)
        assertTrue(mainOnly.minLowerWithCandidateMgdl!! > 70.0)

        val dual = CandidateSearch.search(main, kernel(), isfSlots, band, c, restraint = brake)
        assertEquals(CandidateSearch.Reject.GUARD_FLOOR, dual.reject)
        assertEquals(0.0, dual.smbU)
    }

    @Test
    fun `C1 auch die finale Wirkungspruefung sieht die Bremsbahn`() {
        val main = prediction({ 220.0 }, { 95.0 })
        val brake = prediction({ 220.0 }, { 74.0 })
        assertNull(CandidateSearch.verifyGuardFloor(main, kernel(), isfSlots, band, 0.05))
        assertEquals(
            CandidateSearch.Reject.GUARD_FLOOR,
            CandidateSearch.verifyGuardFloor(main, kernel(), isfSlots, band, 0.05, restraint = brake),
        )
    }

    /** Einseitigkeit, die Grundbedingung: ohne Bremsbahn aendert sich NICHTS. */
    @Test
    fun `C1 ohne Bremsbahn gilt exakt das bisherige Verhalten`() {
        for (lower in listOf(300.0, 130.0, 95.0, 71.0)) {
            val p = prediction({ 220.0 }, { lower })
            val a = CandidateSearch.search(p, kernel(), isfSlots, band, caps())
            val b = CandidateSearch.search(p, kernel(), isfSlots, band, caps(), restraint = null)
            assertEquals(a.smbU, b.smbU, 0.0)
            assertEquals(a.reject, b.reject)
        }
    }

    /**
     * J1/J2 (Codex J-Tabelle): verletzt eine Dosis den Guard auf EINER der
     * beiden Bahnen, darf auch keine groessere Dosis zulaessig sein. Geprueft
     * ueber das gesamte Pumpenraster - Monotonie ist der Grund, warum die Suche
     * absteigend abbrechen darf.
     */
    @Test
    fun `J1-J2 nach dem ersten Guard-Verstoss ist keine groessere Dosis mehr zulaessig`() {
        val main = prediction({ 220.0 }, { 95.0 })
        val brake = prediction({ 220.0 }, { 74.0 })
        var firstRejectTicks = Int.MAX_VALUE
        for (ticks in 1..40) {
            val u = ticks * 0.05
            val r = CandidateSearch.verifyGuardFloor(main, kernel(), isfSlots, band, u, restraint = brake)
            if (r != null) {
                assertEquals(CandidateSearch.Reject.GUARD_FLOOR, r, "u=$u")
                if (ticks < firstRejectTicks) firstRejectTicks = ticks
            } else {
                assertTrue(ticks < firstRejectTicks) { "u=$u war nach dem ersten Reject wieder erlaubt" }
            }
        }
        // Konstruktion: schon der kleinste Schritt reisst die Bremsbahn.
        assertEquals(1, firstRejectTicks)
    }

    /**
     * J6: eine monoton pessimistischere Bremsbahn darf die Menge nur
     * verkleinern - nie vergroessern.
     */
    @Test
    fun `J6 eine pessimistischere Bremsbahn kann die Menge nur verkleinern`() {
        val main = prediction({ 400.0 }, { 200.0 })
        val without = CandidateSearch.search(main, kernel(), isfSlots, band, caps()).smbU
        assertTrue(without > 0.0)
        var previous = without
        for (lower in listOf(200.0, 180.0, 150.0, 120.0, 100.0, 80.0, 70.0)) {
            val r = CandidateSearch.search(
                main, kernel(), isfSlots, band, caps(),
                restraint = prediction({ 400.0 }, { lower }),
            )
            assertTrue(r.smbU <= previous + 1e-12) { "bei Bremsbahn $lower stieg die Menge: ${r.smbU} > $previous" }
            assertTrue(r.smbU <= without + 1e-12)
            previous = r.smbU
        }
        assertEquals(0.0, previous, 0.0, "bei Bremsbahn genau am Boden darf nichts mehr laufen")
    }

    /** Eine vorhandene, aber nicht passende Bremsbahn ist fail-closed - sonst
     *  waere eine kaputte Bremse durchlaessiger als eine funktionierende. */
    @Test
    fun `C1 eine unpassende Bremsbahn sperrt statt ignoriert zu werden`() {
        val main = prediction({ 220.0 }, { 300.0 })
        val shifted = main.copy(predictionAnchorTs = anchor + 60_000L)
        assertEquals(
            CandidateSearch.Reject.GRID_INCONSISTENT,
            CandidateSearch.search(main, kernel(), isfSlots, band, caps(), restraint = shifted).reject,
        )
        assertEquals(
            CandidateSearch.Reject.GRID_INCONSISTENT,
            CandidateSearch.verifyGuardFloor(main, kernel(), isfSlots, band, 0.05, restraint = shifted),
        )
        val tooShort = main.copy(points = main.points.take(60))
        assertEquals(
            CandidateSearch.Reject.GRID_INCONSISTENT,
            CandidateSearch.search(main, kernel(), isfSlots, band, caps(), restraint = tooShort).reject,
        )
    }

    // ---- C2: prior-freie Sicherheitsbahn ----------------------------------

    /**
     * J7 (Codex J-Tabelle, H2): der Marker-Prior darf das Sicherheitszeugnis
     * NICHT veraendern. Gegenprobe im selben Test: die ANGEZEIGTE untere Bahn
     * darf er sehr wohl heben - sonst pruefte der Test eine Bahn ohne Prior.
     */
    @Test
    fun `J7 der Marker-Prior aendert kein Sicherheitszeugnis`() {
        // Mittelbahn steigt (Bedarf da), untere Bahn faellt leicht - die Lage,
        // in der der Prior gebaut wurde.
        val plain = realPrediction(bgAtAnchor = 110.0, drive = 3.0, driveLower = -0.2)
        val lifted = realPrediction(
            bgAtAnchor = 110.0, drive = 3.0,
            driveLower = -0.2 + PrimeRelease.MARKER_PRIOR_MGDL_PER_MIN,
            driveLowerPriorFree = -0.2,
        )
        // Der Prior HEBT die untere Bahn - sonst waere der Test wertlos.
        assertTrue(lifted.minLowerBg > plain.minLowerBg + 1.0) {
            "prior wirkt nicht: ${lifted.minLowerBg} vs ${plain.minLowerBg}"
        }
        // Aber das Zeugnis ist identisch zur Bahn ohne Prior.
        assertEquals(plain.minLowerBg, lifted.minSafetyLowerBg, 1e-9)
        assertEquals(plain.bgAtHorizonLower, lifted.bgAtHorizonSafetyLower, 1e-9)
        for (u in listOf(0.05, 0.20, 0.50, 1.00)) {
            assertEquals(
                CandidateSearch.verifyGuardFloor(plain, kernel(anchor), isfSlots, band, u),
                CandidateSearch.verifyGuardFloor(lifted, kernel(anchor), isfSlots, band, u),
                "u=$u",
            )
        }
        val plainSmb = CandidateSearch.search(plain, kernel(anchor), isfSlots, band, caps()).smbU
        val liftedSmb = CandidateSearch.search(lifted, kernel(anchor), isfSlots, band, caps()).smbU
        // Nicht leerlaufen lassen: ohne Dosis wuerde der Test nichts zeigen.
        assertTrue(plainSmb > 0.0, "Testaufbau ohne Dosis")
        assertEquals(plainSmb, liftedSmb, 0.0)
    }
}
