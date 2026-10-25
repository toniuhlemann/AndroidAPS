package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HCL STUFE 2 (Toni 25.10.2026, Entwurf B) im Kern: der Guard prueft die Bahn MIT der bestaetigten Mahlzeit aus dem
 * Bolusrechner, der Bedarf bleibt auf der Bahn ohne sie. Kein zweites Einrechnen der Mahlzeit, kein zweiter IOB-Abzug,
 * keine Summe der Bedarfswege; ein Low-Befund laesst die Mahlzeitbahn nie gelten. Alle Zahlen sind synthetisch.
 */
class HclGuardTest {

    private val eps = 1e-9

    private fun bahn(mean: Double, lower: Double): PredictorResult {
        val pts = (1..60).map { TrajectoryPoint(it, it * 60_000L, mean, lower, 0.0, 0.0, 0.0) }
        return PredictorResult(
            points = pts, predictionAnchorTs = 0L, bgAtAnchor = mean,
            minMeanBg = mean, minLowerBg = minOf(mean, lower), timeToMinLowerMin = 30,
            bgAtHorizonMean = mean, bgAtHorizonLower = lower,
            lineageKind = "ACTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 235.0, iobArrayGridMin = 5.0,
            modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
        )
    }

    private fun state(iob: Double = 1.0) = FuseController.State(
        health = Health.READY, safetyHold = false, phase = Phase.REARMING,
        netIobU = iob, bolusIobU = iob, basalIobU = 0.0,
        iobThU = 4.0, maxIobU = 8.0, targetMgdl = 100.0, isfMgdlPerU = 50.0,
        smbRatioCorrection = 0.5, smbRatioRise = 0.5,
        rSignedMgdlPerMin = null, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = 0.05, maxSmbU = 0.75, pumpBusy = false,
    )

    private fun decide(
        p: PredictorResult,
        guard: PredictorResult? = null,
        restraint: PredictorResult? = null,
        guardRestraint: PredictorResult? = null,
        low: LowThreatGate.Verdict = LowThreatGate.Verdict.NONE,
        s: FuseController.State = state(),
        tail: TailLiability.Report? = null,
        konto: Double? = null,
    ) = FuseController.decide(
        s, p, tail = tail, restraint = restraint, evidenceCreditActive = false, evidenceMayOverrideRebound = false,
        lowThreat = low, expectationDemandU = konto, guardPrediction = guard, guardRestraint = guardRestraint,
    )

    // ---- Schalter aus / keine Anrechnung: bitgleich -----------------------------------

    @Test
    fun `ohne Mahlzeitbahn entscheidet der Regler wie heute`() {
        for (mean in listOf(90.0, 160.0, 250.0)) for (lower in listOf(60.0, 120.0)) for (konto in listOf(null, 0.4)) {
            val heute = FuseController.decide(
                state(), bahn(mean, lower), restraint = bahn(mean - 20.0, lower - 5.0),
                evidenceCreditActive = false, evidenceMayOverrideRebound = false,
                lowThreat = LowThreatGate.Verdict.NONE, expectationDemandU = konto,
            )
            assertEquals(heute, decide(bahn(mean, lower), restraint = bahn(mean - 20.0, lower - 5.0), konto = konto))
        }
    }

    @Test
    fun `dieselben Bahnen als Mahlzeitbahn aendern nichts`() {
        for (mean in listOf(90.0, 160.0, 250.0)) for (lower in listOf(60.0, 120.0)) {
            val p = bahn(mean, lower)
            val r = bahn(mean - 20.0, lower - 5.0)
            assertEquals(decide(p, restraint = r), decide(p, guard = p, restraint = r, guardRestraint = r)) { "$mean/$lower" }
        }
    }

    // ---- Die Anrechnung hebt nur den Guard --------------------------------------------

    @Test
    fun `die Mahlzeitbahn hebt den Guard, der Bedarf bleibt auf der Bahn ohne Mahlzeit`() {
        val ohne = decide(bahn(250.0, 60.0))
        assertEquals(FuseController.Block.GUARD_FLOOR, ohne.block)
        assertEquals(0.0, ohne.smbU, 0.0)
        val mit = decide(bahn(250.0, 60.0), guard = bahn(250.0, 120.0))
        assertEquals(FuseController.Block.NONE, mit.block)
        // (250 - 100) / 50 = 3 U Bedarf, Ratio 0,5 -> 1,5 U, maxSMB 0,75 U.
        assertEquals(3.0, mit.insulinReqU!!, eps)
        assertEquals(0.75, mit.smbU, eps)
        // Eine hoehere Mittelbahn in der Mahlzeitbahn erzeugt keinen Bedarf: die Mahlzeit zaehlt nicht zweimal.
        val hoch = decide(bahn(250.0, 60.0), guard = bahn(400.0, 120.0))
        assertEquals(mit.insulinReqU!!, hoch.insulinReqU!!, 0.0)
        assertEquals(mit.smbU, hoch.smbU, 0.0)
        assertEquals(120.0, mit.minLowerMgdl!!, 0.0)
    }

    @Test
    fun `liegt auch die Mahlzeitbahn unter dem Boden, sperrt der Guard weiter`() {
        val d = decide(bahn(250.0, 60.0), guard = bahn(250.0, 65.0))
        assertEquals(FuseController.Block.GUARD_FLOOR, d.block)
        assertEquals(FuseController.TbrAction.KEEP_CURRENT, d.tbr)
        assertEquals(0.0, d.smbU, 0.0)
    }

    @Test
    fun `die Bremsbahn mit Mahlzeit gilt fuer den Guard, der Bedarf bleibt auf der Bremse`() {
        val p = bahn(250.0, 120.0)
        val r = bahn(200.0, 60.0)
        assertEquals(FuseController.Block.GUARD_FLOOR, decide(p, restraint = r).block)
        val mit = decide(p, guard = bahn(250.0, 130.0), restraint = r, guardRestraint = bahn(200.0, 125.0))
        assertEquals(FuseController.Block.NONE, mit.block)
        // Der Bedarf kommt von der Bremse ohne Mahlzeit: (200 - 100) / 50 = 2 U.
        assertEquals(2.0, mit.insulinReqU!!, eps)
        // Fehlt die Bremsbahn mit Mahlzeit, prueft der Guard die ohne - die strengere.
        assertEquals(
            FuseController.Block.GUARD_FLOOR,
            decide(p, guard = bahn(250.0, 130.0), restraint = r).block,
        )
    }

    @Test
    fun `im Kontofenster bleibt es beim Maximum der Bedarfswege, keine Summe`() {
        val p = bahn(250.0, 60.0)
        val g = bahn(250.0, 120.0)
        // Normalweg 3 U, Konto 0,4 U: es gilt der groessere, nicht 3,4 U.
        val d = decide(p, guard = g, konto = 0.4)
        assertEquals(3.0, d.insulinReqU!!, eps)
        assertTrue(d.normalDemandInAccountWindow)
        // Konto groesser als der Normalweg: das Konto, nicht die Summe.
        val k = decide(bahn(110.0, 60.0), guard = bahn(110.0, 120.0), konto = 0.6)
        assertEquals(0.6, k.insulinReqU!!, eps)
        assertEquals(0.6, k.smbU, eps)
    }

    // ---- Was die Anrechnung nie aufhebt ---------------------------------------------

    @Test
    fun `bei einem Low-Befund gilt die Mahlzeitbahn nicht - die Null bleibt`() {
        for (low in listOf(LowThreatGate.Verdict.MEASURED_LOW, LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE)) {
            val ohne = decide(bahn(250.0, 60.0), low = low)
            val mit = decide(bahn(250.0, 60.0), guard = bahn(250.0, 120.0), low = low)
            assertEquals(ohne, mit) { low.name }
            assertEquals(FuseController.TbrAction.ZERO_TEMP, mit.tbr) { low.name }
            assertEquals(FuseController.Block.GUARD_FLOOR, mit.block) { low.name }
        }
    }

    @Test
    fun `iobTH, maxIOB und maxSMB binden auch mit Mahlzeitbahn`() {
        val g = bahn(250.0, 120.0)
        assertEquals(FuseController.Block.IOB_TH_REACHED, decide(bahn(250.0, 60.0), guard = g, s = state(iob = 4.0)).block)
        val maxIob = state(iob = 1.0).copy(maxIobU = 1.0, iobThU = 8.0)
        assertEquals(FuseController.Block.MAX_IOB_REACHED, decide(bahn(250.0, 60.0), guard = g, s = maxIob).block)
        assertEquals(0.75, decide(bahn(250.0, 60.0), guard = g).smbU, eps)
    }

    @Test
    fun `der Schwanz kommt vom Aufrufer - mit Mahlzeit sein Spielraum, ohne seine Sperre`() {
        val g = bahn(250.0, 120.0)
        val gesperrt = TailLiability.Report(budgetU = 1.0, existingU = 1.2, headroomU = -0.2, completeness = "FULL", lowerBgAtHSource = "test")
        assertEquals(FuseController.Block.TAIL, decide(bahn(250.0, 120.0), guard = g, tail = gesperrt).block)
        val mitMahlzeit = TailLiability.Report(budgetU = 1.5, existingU = 1.2, headroomU = 0.3, completeness = "FULL", lowerBgAtHSource = "test")
        val d = decide(bahn(250.0, 120.0), guard = g, tail = mitMahlzeit)
        assertEquals(0.3, d.smbU, eps)
        assertEquals("tailHeadroom", d.bindingLimit)
    }

    // ---- Skalierer und Anrechnung ---------------------------------------------------

    /** Synthetisch: gleichmaessig 1 U je 30 min bis 120 min (Endwert 4 U). */
    private val kurve = requireNotNull(ExpectationAccount.Curve.ausEckwerten(1.0, 2.0, 3.0, 4.0))

    @Test
    fun `der HCL-Skalierer streckt die Kurve auf KH durch IC, hoechstens MAX_SCALE`() {
        assertEquals(1.5, EarlySupply.hclScale(6.0, kurve), eps)
        assertEquals(0.5, EarlySupply.hclScale(2.0, kurve), eps)
        assertEquals(EarlySupply.MAX_SCALE, EarlySupply.hclScale(20.0, kurve), 0.0)
        for (x in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertEquals(0.0, EarlySupply.hclScale(x, kurve), 0.0) { "$x" }
    }

    @Test
    fun `die HCL-Anrechnung liegt nie ueber dem beobachteten Anteil der Kurve`() {
        // Bei 40 min erwartet die Kurve 4/3 U. Beobachtet ist die Haelfte.
        val erwartet = kurve.at(40.0)
        val halb = 0.5 * erwartet
        // Mit Skalierer 2 rechnete die allgemeine Regel die ganze Kurve an (2 x 0,5 = 1) - die HCL-Regel nur die Haelfte.
        assertEquals(1.0, EarlySupply.confirmedGuardScale(2.0, 40.0, halb, true, kurve), eps)
        assertEquals(0.5, EarlySupply.hclGuardScale(2.0, 40.0, halb, true, kurve), eps)
        // Beobachtet mehr als die Kurve: hoechstens die Kurve.
        assertEquals(1.0, EarlySupply.hclGuardScale(2.0, 40.0, 3.0 * erwartet, true, kurve), eps)
        // Unter 1 dieselbe Regel wie sonst.
        for (s in listOf(0.0, 0.3, 0.8, 1.0)) for (o in listOf(0.0, halb, erwartet)) assertEquals(
            EarlySupply.confirmedGuardScale(s, 40.0, o, true, kurve), EarlySupply.hclGuardScale(s, 40.0, o, true, kurve), 0.0,
        ) { "$s/$o" }
        // Vor +20 min und ohne gueltige Beobachtung nichts.
        assertEquals(0.0, EarlySupply.hclGuardScale(1.5, 19.0, erwartet, true, kurve), 0.0)
        assertEquals(0.0, EarlySupply.hclGuardScale(1.5, 40.0, erwartet, false, kurve), 0.0)
        assertEquals(0.0, EarlySupply.hclGuardScale(Double.NaN, 40.0, erwartet, true, kurve), 0.0)
    }

    @Test
    fun `ein HCL-Konto hat kein Budget, keine Portion und keinen Nachschlag`() {
        val h = EarlySupply.Settings.forHcl(1.5)
        assertEquals(1.5, h.scaleStable, 0.0)
        assertEquals(1.5, h.scaleAfterLow, 0.0)
        assertEquals(0.0, h.budgetMaxU, 0.0)
        assertEquals(0.0, h.initialShare, 0.0)
        assertEquals(0.0, h.maxPortionU, 0.0)
        assertEquals(0.0, h.maxPerPeriodU, 0.0)
        assertEquals(0.0, h.topUpShare, 0.0)
        for (x in listOf(-0.1, 2.5, Double.NaN)) assertEquals(0.0, EarlySupply.Settings.forHcl(x).scaleStable, 0.0) { "$x" }
        // Gebucht: Budget 0 - in keinem Zyklus fliesst ein Erwartungsanteil, auch nicht bei steigendem Zucker.
        val wirkung: (Double) -> Double = { t -> EarlySupply.orefActionFraction(t, 45.0, 540.0) }
        val params = requireNotNull(ExpectationAccount.Params.of(kurve.values(), 120, 90.0, 0, 0.3))
        val t0 = 1_700_000_000_000L
        var s = ExpectationAccount.armEarly(
            authId = t0, windowEndTs = t0 + 180 * 60_000L, params = params, settings = h,
            switches = EarlySupply.Switches.NONE, startStable = true, nowTs = t0, bgMgdl = 120.0, isfMgdlPerU = 50.0,
            unitAction = wirkung,
        )
        assertEquals(0.0, s.a0U, 0.0)
        assertEquals(1.5, s.phi0, 0.0)
        for (m in 0..150) {
            val bg = 120.0 + 1.5 * m
            val (s2, e) = ExpectationAccount.step(
                s,
                ExpectationAccount.Inputs(
                    nowTs = t0 + m * 60_000L, ageMin = m.toDouble(), bgMgdl = bg, targetMgdl = 100.0, isfMgdlPerU = 50.0,
                    iobTotalU = 4.0, activityUPerMin = 0.02, paused = false, stableNow = true, rising = true,
                    early = ExpectationAccount.EarlyInputs(4.0, 4.0, { t -> 4.0 * wirkung(t) }, wirkung),
                ),
            )
            assertEquals(0.0, e.restEffU, 0.0) { "Minute $m" }
            assertEquals(0.0, ExpectationAccount.expectationShareU(e), 0.0) { "Minute $m" }
            // Lieferbar ist nur der gemessene Anteil (BG - Ziel) / ISF - IOB - nie ein Rest bis KH/IC.
            assertEquals(ExpectationAccount.measuredShareU(e), ExpectationAccount.deliverableU(e), eps) { "Minute $m" }
            s = s2
        }
        assertNotEquals(0.0, s.disturbanceU)
    }
}
