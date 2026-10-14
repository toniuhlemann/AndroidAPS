package app.aaps.fuse.core.controller

import app.aaps.fuse.core.insulin.InsulinSample
import app.aaps.fuse.core.insulin.KernelOutcome
import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.insulin.UnitInsulinKernelBuilder
import app.aaps.fuse.core.insulin.UnitInsulinSampler
import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.fuse.core.predictor.IsfSlot
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kernverdrahtung des Erwartungskontos (Vorregistrierung E-034): Bedarf im
 * Regler, Zielband-Modus der Kandidatensuche, typisierter Guard-Grund und die
 * Guard-Ausnahme des Erwartungsanteils. Ohne Konto bleibt alles wie heute.
 * Alle Zahlen sind synthetisch.
 */
class ExpectationWiringTest {

    private val eps = 1e-9

    // ---- Regler ---------------------------------------------------------------

    private fun pred(bg: Double, minLower: Double = 120.0): PredictorResult {
        val pts = (1..60).map { TrajectoryPoint(it, it * 60_000L, bg, minLower, 0.0, 0.0, 0.0) }
        return PredictorResult(
            points = pts, predictionAnchorTs = 0L, bgAtAnchor = bg,
            minMeanBg = bg, minLowerBg = minOf(bg, minLower), timeToMinLowerMin = 30,
            bgAtHorizonMean = bg, bgAtHorizonLower = minLower,
            lineageKind = "ACTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 235.0, iobArrayGridMin = 5.0,
            modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
        )
    }

    private fun state(smbRatio: Double = 0.5, maxSmb: Double = 0.75, cap: Double? = null, iob: Double = 1.0) =
        FuseController.State(
            health = Health.READY, safetyHold = false, phase = Phase.REARMING,
            netIobU = iob, bolusIobU = iob, basalIobU = 0.0,
            iobThU = 4.0, maxIobU = 8.0, targetMgdl = 100.0, isfMgdlPerU = 50.0,
            smbRatioCorrection = smbRatio, smbRatioRise = smbRatio,
            rSignedMgdlPerMin = null, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
            pumpIncrementU = 0.05, maxSmbU = maxSmb, pumpBusy = false,
            contextDemandRatioCap = cap,
        )

    private fun decide(
        s: FuseController.State,
        p: PredictorResult,
        demand: Double?,
        tail: TailLiability.Report? = null,
    ) = FuseController.decide(
        s, p, tail = tail, evidenceCreditActive = false, evidenceMayOverrideRebound = false,
        lowThreat = LowThreatGate.Verdict.NONE, expectationDemandU = demand,
    )

    @Test
    fun `ohne Konto entscheidet der Regler wie heute`() {
        for (bg in listOf(90.0, 130.0, 250.0)) for (cap in listOf(null, 0.2)) {
            val heute = FuseController.decide(
                state(cap = cap), pred(bg), evidenceCreditActive = false, evidenceMayOverrideRebound = false,
                lowThreat = LowThreatGate.Verdict.NONE,
            )
            assertEquals(heute, decide(state(cap = cap), pred(bg), demand = null))
        }
    }

    @Test
    fun `der Kontoweg traegt seine Menge ohne Ratio-Kandidaten`() {
        // Heute: (250 - 100) / 50 = 3 U Bedarf, smbRatio 0,25 und Kontext-Cap 0,1 -> 0,30 U.
        val heute = decide(state(smbRatio = 0.25, cap = 0.1), pred(250.0), demand = null)
        assertEquals(0.30, heute.smbU, eps)
        // Mit Konto: der Bedarf ist die lieferbare Menge, je Zyklus hoechstens maxSMB.
        val klein = decide(state(smbRatio = 0.25, cap = 0.1), pred(250.0), demand = 0.6)
        assertEquals(0.6, klein.smbU, eps)
        assertEquals(0.6, klein.insulinReqU!!, eps)
        assertEquals("expectationDemand", klein.bindingLimit)
        val gross = decide(state(smbRatio = 0.25, cap = 0.1), pred(250.0), demand = 2.0)
        assertEquals(0.75, gross.smbU, eps)
        assertEquals("maxSmb", gross.bindingLimit)
        // Auch bei Bahn unter dem Ziel bestimmt das Konto den Bedarf.
        assertEquals(0.4, decide(state(), pred(95.0), demand = 0.4).smbU, eps)
    }

    @Test
    fun `Guard, Schwanz und IOB-Grenzen bleiben im Kontomodus`() {
        assertEquals(FuseController.Block.GUARD_FLOOR, decide(state(), pred(250.0, minLower = 60.0), demand = 1.0).block)
        val schwanz = TailLiability.Report(budgetU = 1.0, existingU = 1.2, headroomU = -0.2, completeness = "FULL", lowerBgAtHSource = "test")
        assertEquals(FuseController.Block.TAIL, decide(state(), pred(250.0), demand = 1.0, tail = schwanz).block)
        val knapp = TailLiability.Report(budgetU = 1.0, existingU = 0.8, headroomU = 0.2, completeness = "FULL", lowerBgAtHSource = "test")
        val gekappt = decide(state(), pred(250.0), demand = 1.0, tail = knapp)
        assertEquals(0.2, gekappt.smbU, eps)
        assertEquals("tailHeadroom", gekappt.bindingLimit)
        assertEquals(FuseController.Block.IOB_TH_REACHED, decide(state(iob = 4.0), pred(250.0), demand = 1.0).block)
    }

    @Test
    fun `kein oder nicht berechenbarer Kontobedarf traegt keine Menge - es bleibt der Normalbedarf`() {
        val ohneKonto = decide(state(), pred(250.0), demand = null)
        for (d in listOf(0.0, -0.3, Double.NaN, Double.POSITIVE_INFINITY)) {
            // Bahn unter dem Ziel: auch kein Normalbedarf - nichts.
            val r = decide(state(), pred(95.0), demand = d)
            assertEquals(0.0, r.smbU, 0.0) { "Bedarf $d" }
            assertEquals(FuseController.Block.NO_DEMAND, r.block) { "Bedarf $d" }
            // Bahn hoch: genau die Menge ohne Konto, aus dem Normalweg.
            val hoch = decide(state(), pred(250.0), demand = d)
            assertEquals(ohneKonto.smbU, hoch.smbU, 0.0) { "Bedarf $d" }
            assertTrue(hoch.normalDemandInAccountWindow) { "Bedarf $d" }
        }
    }

    /**
     * A (Toni): Anfangsgabe, Bedarfsrechnung und Pruefung muessen zusammen einen Versorgungsweg ergeben - auch nach
     * Ende des Vorgriffs. Im Kontofenster bleibt der Normalbedarf erhalten; es gilt der Weg mit der groesseren Menge.
     */
    @Test
    fun `im Kontofenster bleibt der Normalbedarf erhalten - es gilt der groessere Weg`() {
        // Normalweg: (250 - 100) / 50 = 3 U, smbRatio 0,25 und Kontext-Cap 0,1 -> 0,30 U.
        val s = state(smbRatio = 0.25, cap = 0.1)
        val leer = decide(s, pred(250.0), demand = 0.0)
        assertEquals(0.30, leer.smbU, eps)
        assertEquals("demandRatioCap", leer.bindingLimit)
        assertEquals(3.0, leer.insulinReqU!!, eps)
        assertTrue(leer.normalDemandInAccountWindow)
        // Der kleinere Kontoweg verdraengt den Normalweg nicht.
        val klein = decide(s, pred(250.0), demand = 0.2)
        assertEquals(0.30, klein.smbU, eps)
        assertTrue(klein.normalDemandInAccountWindow)
        // Traegt das Konto mehr, gilt es; Gleichstand in Pumpenschritten gehoert dem Konto.
        val mehr = decide(s, pred(250.0), demand = 0.6)
        assertEquals(0.6, mehr.smbU, eps)
        assertFalse(mehr.normalDemandInAccountWindow)
        val gleich = decide(s, pred(250.0), demand = 0.30)
        assertEquals("expectationDemand", gleich.bindingLimit)
        assertFalse(gleich.normalDemandInAccountWindow)
    }

    // ---- Kandidatensuche --------------------------------------------------------

    private val anchor = 1_700_000_000_000L
    private val model = InsulinModelProvenance("TEST_FLAT", 2.0, 60, "test")

    /** Konstante Aktivitaet ueber 120 min, 1 U -> 100 mg/dl (wie CandidateSearchTest). */
    private fun kernel(): UnitInsulinKernel {
        val sampler = UnitInsulinSampler { doseU, offsetMin ->
            if (offsetMin >= 120) InsulinSample(0.0, 0.0)
            else InsulinSample(doseU * (1.0 - offsetMin / 120.0), doseU / 120.0)
        }
        return (UnitInsulinKernelBuilder.build(sampler, anchor, model, "flat") as KernelOutcome.Ok).kernel
    }

    private fun bahn(mean: Double, lower: Double): PredictorResult {
        val pts = (1..120).map { TrajectoryPoint(it, anchor + it * 60_000L, mean, lower, 0.0, 0.0, 0.0) }
        return PredictorResult(
            points = pts, predictionAnchorTs = anchor, bgAtAnchor = mean,
            minMeanBg = mean, minLowerBg = minOf(mean, lower),
            timeToMinLowerMin = 0, bgAtHorizonMean = mean, bgAtHorizonLower = lower,
            lineageKind = "VIRTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 240.0, iobArrayGridMin = 1.0, modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
        )
    }

    private val isfSlots = listOf(IsfSlot(anchor - 3_600_000L, anchor + 10 * 3_600_000L, 100.0))

    private fun band(zielband: Boolean) = CandidateSearch.Band(
        releaseTargetLowMgdl = 100.0, releaseTargetHighMgdl = 140.0, demandDeadbandMgdl = 10.0,
        guardFloorMgdl = 70.0, releaseHorizonMin = 30, liabilityHorizonMin = 120, targetChecks = zielband,
    )

    private fun caps(maxSmb: Double = 1.0) = CandidateSearch.Caps(5.0, 5.0, 5.0, 0.05, maxSmb)

    @Test
    fun `ohne Zielband-Pruefung entfallen Eintrittstor und Zielband, der Guard bleibt`() {
        // Bahn im Band: heute kein Bedarf.
        val heute = CandidateSearch.search(bahn(110.0, 400.0), kernel(), isfSlots, band(true), caps())
        assertEquals(CandidateSearch.Reject.NO_DEMAND, heute.reject)
        // Ohne Zielband: die Mengengrenze bindet, obwohl der Mittelwert unter das Ziel faellt.
        val ek = CandidateSearch.search(bahn(110.0, 400.0), kernel(), isfSlots, band(false), caps())
        assertNull(ek.reject)
        assertEquals(1.0, ek.smbU, eps)
        assertEquals("maxSmb", ek.bindingLimit)
        assertNull(ek.cutReason)
        // Der Guard schneidet weiter: Unterkante 101 traegt hoechstens 0,30 U.
        val guard = CandidateSearch.search(bahn(110.0, 101.0), kernel(), isfSlots, band(false), caps())
        assertEquals(0.30, guard.smbU, eps)
        assertEquals(CandidateSearch.Reject.GUARD_FLOOR, guard.cutReason)
        assertEquals("guardFloor", guard.bindingLimit)
        // Und er sperrt ganz, wenn schon der Anker unter dem Boden liegt.
        val zu = CandidateSearch.search(bahn(110.0, 60.0), kernel(), isfSlots, band(false), caps())
        assertEquals(CandidateSearch.Reject.GUARD_FLOOR, zu.reject)
    }

    @Test
    fun `der Kuerzungsgrund ist typisiert und deckt sich mit dem Text`() {
        // Zielband kuerzt: 161 - u * 25 >= 100 -> 2,40 U; der Guard traegt mehr.
        val ziel = CandidateSearch.search(bahn(161.0, 400.0), kernel(), isfSlots, band(true), caps(maxSmb = 5.0))
        assertEquals(2.40, ziel.smbU, eps)
        assertEquals(CandidateSearch.Reject.BELOW_TARGET_BAND, ziel.cutReason)
        assertEquals("releaseTargetLow", ziel.bindingLimit)
        // Guard kuerzt bei eingeschaltetem Zielband genauso typisiert.
        val guard = CandidateSearch.search(bahn(200.0, 101.0), kernel(), isfSlots, band(true), caps())
        assertEquals(CandidateSearch.Reject.GUARD_FLOOR, guard.cutReason)
        assertEquals("guardFloor", guard.bindingLimit)
    }

    // ---- CandidateGate ------------------------------------------------------------

    private fun basis(smb: Double = 0.30, block: FuseController.Block = FuseController.Block.NONE) =
        FuseController.Decision(smb, FuseController.TbrAction.KEEP_CURRENT, block, 1.0, 150.0, 110.0, "expectationDemand")

    private fun ergebnis(smb: Double, reject: CandidateSearch.Reject? = null, cut: CandidateSearch.Reject? = null) =
        CandidateSearch.Result(smb, reject, cut?.let { "guardFloor" } ?: "maxSmb", 150.0, 140.0, 90.0, 20.0, 5, cutReason = cut)

    @Test
    fun `das Gate traegt den Grund typisiert weiter`() {
        val sperre = CandidateGate.apply(basis(), ergebnis(0.0, CandidateSearch.Reject.GUARD_FLOOR), 0.05)
        assertEquals(FuseController.Block.CANDIDATE, sperre.block)
        assertEquals(CandidateSearch.Reject.GUARD_FLOOR, sperre.candidateReason)
        val kuerzung = CandidateGate.apply(basis(), ergebnis(0.15, cut = CandidateSearch.Reject.GUARD_FLOOR), 0.05)
        assertEquals(FuseController.Block.NONE, kuerzung.block)
        assertEquals(CandidateSearch.Reject.GUARD_FLOOR, kuerzung.candidateReason)
        val kappe = CandidateGate.apply(basis(), ergebnis(0.15), 0.05)
        assertNull(kappe.candidateReason)
        val anderer = CandidateGate.apply(basis(), ergebnis(0.0, CandidateSearch.Reject.NO_HEADROOM), 0.05)
        assertEquals(CandidateSearch.Reject.NO_HEADROOM, anderer.candidateReason)
        // Keine Kuerzung, kein Grund.
        assertNull(CandidateGate.apply(basis(0.30), ergebnis(0.60), 0.05).candidateReason)
        assertNull(CandidateGate.apply(basis(0.30), null, 0.05).candidateReason)
    }

    // ---- Autorisierungstabelle ------------------------------------------------------

    @Test
    fun `die Guard-Ausnahme hebt nur das Guard-Urteil und nur mit Autorisierung`() {
        val gf = CandidateSearch.Reject.GUARD_FLOOR
        val mitGrund = FuseController.Block.entries.filter { MarkerAuthorization.liftsExpectation(it, gf, true) }.toSet()
        assertEquals(setOf(FuseController.Block.GUARD_FLOOR, FuseController.Block.CANDIDATE, FuseController.Block.NONE), mitGrund)
        val ohneGrund = FuseController.Block.entries.filter { MarkerAuthorization.liftsExpectation(it, null, true) }.toSet()
        assertEquals(setOf(FuseController.Block.GUARD_FLOOR), ohneGrund)
        for (r in CandidateSearch.Reject.entries.filter { it != gf })
            assertFalse(MarkerAuthorization.liftsExpectation(FuseController.Block.CANDIDATE, r, true)) { "CANDIDATE mit $r" }
        for (b in FuseController.Block.entries) for (r in listOf(null, gf))
            assertFalse(MarkerAuthorization.liftsExpectation(b, r, false)) { "$b ohne Autorisierung" }
        // TAIL bleibt fuer d_erw hart - fuer die Direktdosis-Familie unveraendert hebbar.
        assertFalse(MarkerAuthorization.liftsExpectation(FuseController.Block.TAIL, gf, true))
        assertTrue(MarkerAuthorization.lifts(FuseController.Block.TAIL, true))
    }

    // ---- Lift des Erwartungsanteils --------------------------------------------------

    private fun liftState(maxSmb: Double = 0.75) = FuseController.State(
        health = Health.READY, safetyHold = false, phase = Phase.RISE_ACTIVE,
        netIobU = 0.5, bolusIobU = 0.5, basalIobU = 0.0,
        iobThU = 8.0, maxIobU = 8.0, targetMgdl = 100.0, isfMgdlPerU = 50.0,
        smbRatioCorrection = 0.5, smbRatioRise = 0.5,
        rSignedMgdlPerMin = null, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = 0.05, maxSmbU = maxSmb, pumpBusy = false,
    )

    private fun ekLift(
        base: FuseController.Decision,
        floor: Double = 0.6,
        authorized: Boolean = true,
        tail: Double? = null,
        maxSmb: Double = 0.75,
        source: AuthorizedLift.Source = AuthorizedLift.Source.EXPECTATION,
    ) = AuthorizedLift.lift(
        base = base, source = source, floorU = floor, remainingU = floor, state = liftState(maxSmb),
        authorized = authorized, tailHeadroomU = tail, tickEps = 1e-9,
    )

    private fun entscheidung(block: FuseController.Block, smb: Double = 0.0, grund: CandidateSearch.Reject? = null) =
        FuseController.Decision(
            smbU = smb, tbr = FuseController.TbrAction.KEEP_CURRENT, block = block,
            insulinReqU = 0.6, predAtReleaseMgdl = 150.0, minLowerMgdl = 65.0,
            bindingLimit = block.name, candidateReason = grund,
        )

    @Test
    fun `der Erwartungsanteil hebt das Guard-Urteil in allen drei Gestalten`() {
        val gf = CandidateSearch.Reject.GUARD_FLOOR
        for (b in listOf(
            entscheidung(FuseController.Block.GUARD_FLOOR),
            entscheidung(FuseController.Block.CANDIDATE, grund = gf),
            entscheidung(FuseController.Block.NONE, smb = 0.2, grund = gf),
        )) {
            val d = ekLift(b)
            assertEquals(0.6, d.smbU, eps) { "${b.block}" }
            assertEquals(FuseController.Block.NONE, d.block)
            assertEquals("expectationAccount", d.bindingLimit)
            assertEquals(FuseController.STAGE_EXPECTATION, d.capsStage)
            assertEquals(AuthorizedLift.Source.EXPECTATION, d.grant!!.source)
            assertEquals(0.6, d.grant!!.amountU, eps)
            // Der typisierte Grund ueberlebt die Anhebung.
            assertEquals(b.candidateReason, d.candidateReason)
        }
    }

    @Test
    fun `ohne Guard-Urteil oder ohne Autorisierung hebt der Erwartungsanteil nichts`() {
        val gf = CandidateSearch.Reject.GUARD_FLOOR
        for (b in listOf(
            entscheidung(FuseController.Block.NONE, smb = 0.2),
            entscheidung(FuseController.Block.NO_DEMAND),
            entscheidung(FuseController.Block.CANDIDATE, grund = CandidateSearch.Reject.NO_HEADROOM),
            entscheidung(FuseController.Block.CANDIDATE),
            entscheidung(FuseController.Block.TAIL),
            entscheidung(FuseController.Block.MEASURED_DESCENT_RISK, grund = gf),
        )) assertSame(b, ekLift(b)) { "${b.block}/${b.candidateReason}" }
        val gesperrt = entscheidung(FuseController.Block.GUARD_FLOOR)
        assertSame(gesperrt, ekLift(gesperrt, authorized = false))
    }

    @Test
    fun `fuer den Erwartungsanteil bindet die Schwanzkappe auch mit Autorisierung`() {
        val b = entscheidung(FuseController.Block.GUARD_FLOOR)
        assertEquals(0.25, ekLift(b, tail = 0.25).smbU, eps)
        assertEquals(0.25, ekLift(b, tail = 0.25).grant!!.amountU, eps)
        // Die Direktdosis-Familie bleibt, wie sie ist: autorisiert ohne Schwanzkappe.
        assertEquals(0.6, ekLift(b, tail = 0.25, source = AuthorizedLift.Source.PRIME).smbU, eps)
        // maxSMB begrenzt die Einzeldosis.
        assertEquals(0.75, ekLift(b, floor = 2.0).smbU, eps)
    }

    // ---- Kappen der Anfangsportion ---------------------------------------------------
    //
    // Die Anfangsportion ist nicht maxSMB-geteilt; genau deshalb muessen die Bestandsgrenzen in der Grant-Bildung sitzen.
    // Bis zur Konsolidierung prueften das nur die Lift-Tests der Direktdosis-Familie.

    private fun anfangLift(
        base: FuseController.Decision = entscheidung(FuseController.Block.GUARD_FLOOR),
        maxIob: Double = 8.0,
        iob: Double = 0.5,
        transport: Double = 0.0,
        kontext: Double? = null,
    ) = AuthorizedLift.lift(
        base = base, source = AuthorizedLift.Source.EXPECTATION, floorU = 3.0, remainingU = 3.0,
        state = liftState().copy(maxIobU = maxIob, netIobU = iob, bolusIobU = iob),
        authorized = true, transportCommitmentU = transport, contextExposureLimitU = kontext, tickEps = 1e-9,
        early = AuthorizedLift.EarlyLift(guardLift = true, tailLift = true),
    )

    @Test
    fun `die Anfangsportion steht unter maxIOB, Transporthaftung und Kontextgrenze`() {
        // Kontrolle: frei hebt der Lift die ganze Portion - maxSMB teilt sie nicht.
        assertEquals(3.0, anfangLift().smbU, eps)
        assertEquals(1.5, anfangLift(maxIob = 2.0).smbU, eps)
        // Was schon unterwegs ist, finanziert die Portion nicht erneut.
        assertEquals(0.9, anfangLift(maxIob = 2.0, transport = 0.6).smbU, eps)
        // Die Kontextgrenze steht schon in der Grant-Bildung: kein Grant oberhalb des Raums.
        val k = anfangLift(iob = 0.4, kontext = 2.5)
        assertEquals(2.1, k.smbU, eps)
        assertEquals(2.1, k.grant!!.amountU, eps)
    }

    @Test
    fun `ein winziger Ratio-Anteil der Basis kappt die Anfangsportion nicht`() {
        val d = anfangLift(base = entscheidung(FuseController.Block.NONE, smb = 0.05))
        assertEquals(3.0, d.smbU, eps)
        assertEquals(AuthorizedLift.Source.EXPECTATION, d.grant!!.source)
    }
}
