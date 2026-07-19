package app.aaps.plugins.aps.openAPSAutoISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * DynMealIobTH Shadow — Spez-Tests (v1.1-v1.3 + R5 Bauauflagen). Nummern beziehen sich auf
 * die Review-Kette R2 §12 / R3 §10 / R4 §10 / R5. Pure Logik + Zustandsmaschine; Persistenz-/
 * Export-Pfade (org.json) werden im Live-Smoke + Diff-Test verifiziert.
 */
class DynMealIobThShadowTest {

    private val t0 = 1_800_000_000_000L
    private fun min(m: Int) = m * 60_000L

    /** Voll bestimmte Zeile: Wand des Floor-Rungs (40) gebunden, Bedarf dosierbar, alles safe. */
    private fun boundInputs(
        cycleTs: Long, glucoseTs: Long? = cycleTs - 5_000,
        mealActiveKnown: Boolean = true, mealActive: Boolean = true,
        capIob: Double = 3.5, insReq: Double = 1.0, shortAvg: Double = 4.0, delta: Double = 5.0,
        carbsReq: Int = 0, minGuard: Double = 120.0, eventual: Double = 140.0, bg: Double = 170.0,
    ) = DynShadowInputs(
        cycleTs = cycleTs, apsGlucoseTs = glucoseTs,
        mealActiveKnown = mealActiveKnown, mealActive = mealActive,
        actualUseIobTh = true, actualConfiguredPercent = 40,
        actualEffectiveGateU = 3.2, capIobU = capIob, netIobU = capIob, maxIobU = 8.0,
        profilePercent = 100, sensitivityRatio = 1.0,
        smbGateReason = "iobTH",
        microBolusAllowed = true, evenOddFeatureEnabled = true,
        calibrationStopsSmb = false, targetIsEven = true,
        loopBg = bg, loopBgNoise = 1.0, bgAgeMin = 1.0, flatBgsDetected = false,
        delta = delta, shortAvgDelta = shortAvg, maxDelta = delta,
        insReq = insReq, eventualBg = eventual, minGuardBg = minGuard, minBg = 90.0,
        thresholdBg = 65.0, targetBg = 98.0, carbsReq = carbsReq,
        smbRatioFix = 0.175, smbRatioMin = 0.175, smbRatioMax = 0.175, smbRatioBgRange = 0.0,
        mealCob = 0.0, carbRatio = 9.0, currentBasal = 0.55,
        maxSmbBasalMinutes = 90.0, maxUamSmbBasalMinutes = 60.0, smbMaxRangeExtension = 1.0,
        bolusIncrementU = 0.05, skipNeutralTemps = false, localMinute = 30,
        actualMaxBolusU = null,
    )

    // --- Test 16 (R3): strikter Grenzfall — capIob == Gate sperrt NICHT ---
    @Test fun strictOperatorAtGateBoundary() {
        val i = boundInputs(t0, capIob = 3.2)   // exakt == effectiveGateU
        assertThat(DynShadowLogic.actualGateBoundInferred(i)).isEqualTo(false)
        val above = boundInputs(t0, capIob = 3.2000001)
        assertThat(DynShadowLogic.actualGateBoundInferred(above)).isEqualTo(true)
    }

    // --- Test 25 (R4): actualUseIobTh=false -> Inferred false; virtuelle Kandidaten simulierbar ---
    @Test fun useIobThSeparatesActualAndVirtual() {
        val i = boundInputs(t0).copy(actualUseIobTh = false, capIobU = 7.0)
        assertThat(DynShadowLogic.actualGateBoundInferred(i)).isEqualTo(false)
        assertThat(DynShadowLogic.virtualGateBranchEligible(i, 40)).isEqualTo(true)
        assertThat(DynShadowLogic.virtualGateBranchEligible(i, 100)).isEqualTo(false)
    }

    // --- R3: Demand veraendert das Gate-Agreement nicht (Test 15) ---
    @Test fun demandDoesNotAffectGateAgreement() {
        val closedNoDemand = boundInputs(t0, capIob = 4.0, insReq = 0.0)
        assertThat(DynShadowLogic.actualGateBoundInferred(closedNoDemand)).isEqualTo(true)
        assertThat(DynShadowLogic.actualGateBoundExact(closedNoDemand)).isEqualTo(true)
    }

    // --- Safety-Helper (Tests 18/28/29 + v1.3 §1) ---
    @Test fun safetyHelperBranches() {
        // sauber -> eligible true
        assertThat(DynShadowLogic.evaluateSmbSafetyWithoutIobTh(boundInputs(t0)).eligible).isEqualTo(true)
        // minGuard unter threshold -> hart false
        val guard = DynShadowLogic.evaluateSmbSafetyWithoutIobTh(boundInputs(t0, minGuard = 60.0))
        assertThat(guard.eligible).isEqualTo(false)
        assertThat(guard.reasons).contains(DynSafetyReason.MIN_GUARD_BELOW_THRESHOLD)
        // Test 29: eventual >= minBG reicht — minPred existiert nicht als eigener Blocker
        assertThat(DynShadowLogic.evaluateSmbSafetyWithoutIobTh(boundInputs(t0, eventual = 95.0)).eligible).isEqualTo(true)
        val evLow = DynShadowLogic.evaluateSmbSafetyWithoutIobTh(boundInputs(t0, eventual = 80.0))
        assertThat(evLow.reasons).contains(DynSafetyReason.EVENTUAL_BELOW_MIN_BG)
        // Test 28: Top-of-hour
        val topHour = boundInputs(t0).copy(skipNeutralTemps = true, localMinute = 56)
        assertThat(DynShadowLogic.evaluateSmbSafetyWithoutIobTh(topHour).reasons)
            .contains(DynSafetyReason.TOP_OF_HOUR_NEUTRAL_RETURN)
        // Test 19: fullLoop (target<100) nutzt 30%-maxDelta — 0.25*bg blockt NICHT
        val d = boundInputs(t0).copy(maxDelta = 0.25 * 170.0)
        assertThat(DynShadowLogic.evaluateSmbSafetyWithoutIobTh(d).reasons)
            .doesNotContain(DynSafetyReason.MAX_DELTA_EXCEEDED)
        val d2 = boundInputs(t0).copy(maxDelta = 0.35 * 170.0)
        assertThat(DynShadowLogic.evaluateSmbSafetyWithoutIobTh(d2).reasons)
            .contains(DynSafetyReason.MAX_DELTA_EXCEEDED)
        // unknown Input -> eligible null (blockiert UP, Test 7)
        assertThat(DynShadowLogic.evaluateSmbSafetyWithoutIobTh(boundInputs(t0).copy(minGuardBg = null)).eligible).isNull()
    }

    // --- Test 30 (R4): Ratio-Parität fullLoop/enforced (Tabellenwerte) ---
    @Test fun hypotheticalRatioTable() {
        // fix ratio, range 0: beide Modi = fix
        assertThat(DynShadowLogic.hypotheticalSmbRatio(170, 98.0, "fullLoop", 0.175, 0.175, 0.175, 0.0)).isEqualTo(0.175)
        assertThat(DynShadowLogic.hypotheticalSmbRatio(170, 108.0, "enforced", 0.175, 0.175, 0.175, 0.0)).isEqualTo(0.175)
        // variable Range: fullLoop = max(fix, interp); enforced = begrenzte Interpolation
        val full = DynShadowLogic.hypotheticalSmbRatio(120, 100.0, "fullLoop", 0.3, 0.4, 0.6, 40.0)
        assertThat(full).isWithin(1e-9).of(0.5)          // interp 0.4+(0.2)*(20/40)=0.5 > fix
        val enf = DynShadowLogic.hypotheticalSmbRatio(90, 100.0, "enforced", 0.3, 0.4, 0.6, 40.0)
        assertThat(enf).isWithin(1e-9).of(0.4)           // bg<=target -> lower
        val enfHigh = DynShadowLogic.hypotheticalSmbRatio(200, 100.0, "enforced", 0.3, 0.4, 0.6, 40.0)
        assertThat(enfHigh).isWithin(1e-9).of(0.6)       // bg>=higher -> higher
    }

    // --- Test 31 (R4): simulatedMaxBolus UAM- vs SMB-Zweig inkl. Rundung ---
    @Test fun simulatedMaxBolusBranches() {
        // netIob(3.5) > mealInsulinReq(0) -> UAM-Zweig: 1.0*0.55*60/60 = 0.55 -> round1 0.6
        assertThat(DynShadowLogic.simulatedMaxBolusU(boundInputs(t0))).isWithin(1e-9).of(0.6)
        // COB gross -> SMB-Zweig: 0.55*90/60 = 0.825 -> round1 0.8
        val cob = boundInputs(t0).copy(mealCob = 60.0)
        assertThat(DynShadowLogic.simulatedMaxBolusU(cob)).isWithin(1e-9).of(0.8)
    }

    // --- Test 32 (R4): fehlendes reales maxBolusU verhindert Virtual-Single-Cap nicht ---
    @Test fun virtualSingleCapWithoutRealMaxBolus() {
        val i = boundInputs(t0, capIob = 4.15, insReq = 3.0)  // virtueller Cap 40%: 1.3*3.2=4.16
        assertThat(i.actualMaxBolusU).isNull()
        assertThat(DynShadowLogic.virtualSingleCapBound(i, 40, 0.175)).isEqualTo(true)
    }

    // --- Zustandsmaschine: 3/5-UP + Fenster leeren + naechste Sprosse neu verdienen ---
    @Test fun threeOfFiveUpAndReEarn() {
        var w: DynWindowState? = null
        var lastResult: DynStepResult? = null
        for (k in 0..2) {
            lastResult = DynShadowStep.step(w, boundInputs(t0 + min(k), glucoseTs = t0 + min(k)))
            w = lastResult.window
        }
        val cd5 = lastResult!!.decisions.first { it.candidateId == "s10_fnone_cd5" }
        assertThat(cd5.direction).isEqualTo("UP")
        assertThat(cd5.rungAfter).isEqualTo(50)
        // direkt danach: Fenster leer + Cooldown -> kein sofortiges zweites UP
        val next = DynShadowStep.step(w, boundInputs(t0 + min(3), glucoseTs = t0 + min(3)))
        assertThat(next.decisions.first { it.candidateId == "s10_fnone_cd5" }.direction).isNotEqualTo("UP")
    }

    // --- Tests 26/27/34 (R4/R5): gleiches apsGlucoseTs fuellt das Fenster nur einmal ---
    @Test fun duplicateGlucoseCountsOnce() {
        var w: DynWindowState? = null
        // 3 Calculate-Zyklen (verschiedene cycleTs!) auf DERSELBEN Sensorablesung
        for (k in 0..2) {
            val r = DynShadowStep.step(w, boundInputs(t0 + k * 10_000, glucoseTs = t0))
            w = r.window
            if (k > 0) assertThat(r.duplicateGlucose).isTrue()
        }
        val r4 = DynShadowStep.step(w, boundInputs(t0 + min(1), glucoseTs = t0 + min(1)))
        // erst 2 EINDEUTIGE Ablesungen -> kein UP moeglich
        assertThat(r4.decisions.none { it.direction == "UP" }).isTrue()
    }

    // --- Test 35 (R5): duplicate + neues Hard-Signal -> trotzdem virtueller Floor/Up-Latch ---
    @Test fun hardDownOverridesDedupe() {
        var w = DynShadowStep.step(null, boundInputs(t0, glucoseTs = t0)).window
        val dup = DynShadowStep.step(w, boundInputs(t0 + 10_000, glucoseTs = t0, carbsReq = 5))
        assertThat(dup.duplicateGlucose).isTrue()
        assertThat(dup.hardDown).isTrue()
        assertThat(dup.decisions.all { it.direction == "HARD_DOWN" }).isTrue()
        // Up-Latch: auch mit frischer Glukose + voller Evidenz kein UP mehr in diesem Fenster (Test 8)
        w = dup.window
        for (k in 1..4) {
            val r = DynShadowStep.step(w, boundInputs(t0 + min(k), glucoseTs = t0 + min(k)))
            w = r.window
            assertThat(r.decisions.none { it.direction == "UP" }).isTrue()
        }
    }

    // --- Test 36 (R5): duplicate + MEAL_ACTIVE-Flanke schliesst das Fenster trotzdem ---
    @Test fun windowEdgeOverridesDedupe() {
        val w = DynShadowStep.step(null, boundInputs(t0, glucoseTs = t0)).window
        val end = DynShadowStep.step(w, boundInputs(t0 + 10_000, glucoseTs = t0, mealActive = false))
        assertThat(end.windowEvent).isEqualTo("window-end")
        assertThat(end.window).isNull()
    }

    // --- Test 37 (R5): duplicate + realer Rung-Wechsel setzt Confounder ---
    @Test fun rungChangeLatchedOnDuplicate() {
        var w = DynShadowStep.step(null, boundInputs(t0, glucoseTs = t0)).window
        val r = DynShadowStep.step(w, boundInputs(t0 + 10_000, glucoseTs = t0).copy(actualConfiguredPercent = 60))
        assertThat(r.window!!.actualRungChangeObserved).isTrue()
        // Test 22 (R3): numerisches Alignment loescht nichts
        val r2 = DynShadowStep.step(r.window, boundInputs(t0 + min(1), glucoseTs = t0 + min(1)).copy(actualConfiguredPercent = 40))
        assertThat(r2.window!!.actualRungChangeObserved).isTrue()
    }

    // --- Test 21 (R4): MEAL_ACTIVE unknown ist nicht false ---
    @Test fun unknownMealActiveNeitherOpensNorCloses() {
        val open = DynShadowStep.step(null, boundInputs(t0, mealActiveKnown = false))
        assertThat(open.window).isNull()
        val w = DynShadowStep.step(null, boundInputs(t0, glucoseTs = t0)).window
        val hold = DynShadowStep.step(w, boundInputs(t0 + min(1), glucoseTs = t0 + min(1), mealActiveKnown = false))
        assertThat(hold.window).isNotNull()
        assertThat(hold.windowEvent).isNull()
    }

    // --- Konfliktregel (Addendum §4): beide Schwellen -> HOLD wird strukturell erzwungen ---
    @Test fun mixedWindowHolds() {
        // upCount>=3 verlangt downCount<3 und umgekehrt — bei 5 Slots schliessen sich 3+3 aus;
        // hier: 3 pos + 2 neg-artige unknown-Zeilen ergeben KEIN SOFT_DOWN.
        var w: DynWindowState? = null
        for (k in 0..2) { w = DynShadowStep.step(w, boundInputs(t0 + min(k), glucoseTs = t0 + min(k))).window }
        val r = DynShadowStep.step(w, boundInputs(t0 + min(3), glucoseTs = t0 + min(3), insReq = 0.0, shortAvg = -1.0, delta = -1.0, capIob = 1.0))
        assertThat(r.decisions.none { it.direction == "SOFT_DOWN" }).isTrue()
    }

    // --- SOFT_DOWN: budgetUnused-Semantik (kein Binding, kein Bedarf, untere Wand frei, Trend weg) ---
    @Test fun softDownAfterDemandGone() {
        // Erst auf 50 hochverdienen, dann 3 budgetUnused-Zeilen (Hysterese beachten: cd5 + >10min)
        var w: DynWindowState? = null
        for (k in 0..2) { w = DynShadowStep.step(w, boundInputs(t0 + min(k), glucoseTs = t0 + min(k))).window }
        var ts = t0 + min(15)   // nach Up-Hysterese (10) + Cooldown (5)
        var sawDown = false
        for (k in 0..2) {
            val r = DynShadowStep.step(w, boundInputs(ts + min(k), glucoseTs = ts + min(k), insReq = 0.0, shortAvg = -1.0, delta = -1.0, capIob = 1.0))
            w = r.window
            if (r.decisions.first { it.candidateId == "s10_fnone_cd5" }.direction == "SOFT_DOWN") sawDown = true
        }
        assertThat(sawDown).isTrue()
        assertThat(w!!.candidates["s10_fnone_cd5"]!!.currentRung).isEqualTo(40)
    }

    // --- Eligibility aus 0059-reason: Paritaet by construction ---
    @Test fun eligibilityFromReasonBranchOrder() {
        val e1 = DynMealIobThShadowRunner.eligibilityFromReason("odd-target")
        assertThat(e1.targetIsEven).isEqualTo(false)
        assertThat(e1.calibrationStopsSmb).isEqualTo(false)   // Branch davor bewiesen
        assertThat(e1.maxIobPositive).isNull()                // Branch danach unbekannt
        val e2 = DynMealIobThShadowRunner.eligibilityFromReason("iobTH")
        assertThat(e2.targetIsEven).isEqualTo(true)
        assertThat(e2.maxIobPositive).isEqualTo(true)
        val e3 = DynMealIobThShadowRunner.eligibilityFromReason("microbolus-disabled")
        assertThat(e3.microBolusAllowed).isEqualTo(false)
        assertThat(e3.calibrationStopsSmb).isNull()
        val e4 = DynMealIobThShadowRunner.eligibilityFromReason("calibration")
        assertThat(e4.calibrationStopsSmb).isEqualTo(true)
    }

    // --- Test 24 (R3): Rung-Sequenzen erreichen das Ceiling exakt ---
    @Test fun rungSequencesReachCeiling() {
        assertThat(DynShadowCandidateCfg("t", 10, null, 5).rungs()).containsExactly(40, 50, 60, 70, 80, 90).inOrder()
        assertThat(DynShadowCandidateCfg("t", 5, null, 5).rungs().last()).isEqualTo(90)
        assertThat(DYN_SHADOW_CANDIDATES).hasSize(12)
    }

    // --- R6 F1: MEAL_ACTIVE unknown im laufenden Fenster -> kein Score/UP/SOFT_DOWN ---
    @Test fun unknownMealActiveBlocksScoreInOpenWindow() {
        var w = DynShadowStep.step(null, boundInputs(t0, glucoseTs = t0)).window
        for (k in 1..3) {
            val r = DynShadowStep.step(w, boundInputs(t0 + min(k), glucoseTs = t0 + min(k), mealActiveKnown = false))
            w = r.window
            assertThat(r.scoreBlocked).isTrue()
            assertThat(r.decisions.none { it.direction == "UP" || it.direction == "SOFT_DOWN" }).isTrue()
            assertThat(r.decisions.first().reason).isEqualTo("meal-active-unknown")
        }
        assertThat(w!!.candidates.values.all { it.currentRung == 40 }).isTrue()
        // Hard-Safety wirkt trotz unknown weiter (R6 F1-Ausnahme)
        val hard = DynShadowStep.step(w, boundInputs(t0 + min(4), glucoseTs = t0 + min(4), mealActiveKnown = false, carbsReq = 5))
        assertThat(hard.decisions.all { it.direction == "HARD_DOWN" }).isTrue()
    }

    // --- R6 F2: A,B,A out-of-order zaehlt nicht; >7-min-Spanne wird beschnitten ---
    @Test fun outOfOrderAndWindowSpan() {
        var w = DynShadowStep.step(null, boundInputs(t0, glucoseTs = t0)).window
        w = DynShadowStep.step(w, boundInputs(t0 + min(1), glucoseTs = t0 + min(1))).window
        // A,B,A: aelterer Timestamp wieder -> outOfOrder, keine dritte Evidenz, kein UP
        val aba = DynShadowStep.step(w, boundInputs(t0 + min(2), glucoseTs = t0))
        assertThat(aba.outOfOrderGlucose).isTrue()
        assertThat(aba.decisions.none { it.direction == "UP" }).isTrue()
        // >7-min-Luecke auf der GLUKOSE-Achse: alte Rows fallen raus -> 2 alte + 1 neue reichen nicht
        val late = DynShadowStep.step(aba.window, boundInputs(t0 + min(10), glucoseTs = t0 + min(10)))
        assertThat(late.decisions.none { it.direction == "UP" }).isTrue()
        assertThat(late.decisions.first { it.candidateId == "s10_fnone_cd5" }.upCount).isEqualTo(1)
    }

    // --- R6 F7: Binding wird auch am Ceiling gemessen; erster Commit meldet Confounder ---
    @Test fun ceilingStillMeasuresBindingAndCommitConfounds() {
        val atCeiling = DynWindowState(
            "meal-$t0", t0, leftTruncated = false,
            candidates = DYN_SHADOW_CANDIDATES.associate {
                it.id to DynCandidateState(it.id, currentRung = 90)
            },
        )
        // capIob 7.5 > simGate(90%) = 7.2 -> Binding MUSS am Ceiling sichtbar sein, aber kein UP
        val r = DynShadowStep.step(atCeiling, boundInputs(t0 + min(1), glucoseTs = t0 + min(1), capIob = 7.5))
        val d = r.decisions.first { it.candidateId == "s10_fnone_cd5" }
        assertThat(d.virtualGateBound).isEqualTo(true)
        assertThat(d.simulatedEffectiveGateU).isWithin(1e-6).of(7.2)
        assertThat(d.direction).isNotEqualTo("UP")
        // erster virtueller Commit traegt Confounder + CommitCount sofort (Post-State)
        var w: DynWindowState? = null
        var upDecision: DynCandidateDecision? = null
        for (k in 0..2) {
            val rr = DynShadowStep.step(w, boundInputs(t0 + min(k), glucoseTs = t0 + min(k)))
            w = rr.window
            rr.decisions.first { it.candidateId == "s10_fnone_cd5" }
                .takeIf { it.direction == "UP" }?.let { upDecision = it }
        }
        assertThat(upDecision!!.trajectoryConfounded).isTrue()
        assertThat(upDecision!!.commitCountAfter).isEqualTo(1)
    }

    // --- R6 F5: OFF (und alles ausser SHADOW) tut nichts ---
    @Test fun offModeDoesNothing() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "dynshadow-${System.nanoTime()}").apply { mkdirs() }
        try {
            val r = DynMealIobThShadowRunner.runShadow(
                engineMode = "OFF", cycleTs = t0, mealActiveKnown = true, mealActive = true,
                apsGlucoseTs = t0, smbGateReason = "none", actualUseIobTh = true,
                actualConfiguredPercent = 40, actualEffectiveGateU = 3.2, capIobU = 1.0,
                netIobU = 1.0, maxIobU = 8.0, profilePercent = 100, sensitivityRatio = 1.0,
                loopBg = 120.0, noise = 1.0, bgAgeMin = 1.0, flatBgs = false, delta = 1.0,
                shortAvgDelta = 1.0, raw = null, insReq = 0.0, minBg = 90.0, targetBg = 98.0,
                ratioFix = 0.175, ratioMin = 0.175, ratioMax = 0.175, ratioBgRange = 0.0,
                mealCob = 0.0, carbRatio = 9.0, currentBasal = 0.55, maxSmbMinutes = 90.0,
                maxUamSmbMinutes = 60.0, smbMaxRangeExt = 1.0, bolusIncrementU = 0.05,
                skipNeutralTemps = false, localMinute = 30, actualMaxBolusU = null, stateDir = dir,
            )
            assertThat(r).isNull()
            assertThat(java.io.File(dir, "dynmeal_shadow_state.json").exists()).isFalse()
            // invalid mode wird wie OFF behandelt
            assertThat(DynMealIobThShadowRunner.eligibilityFromReason("garbage").microBolusAllowed).isNull()
        } finally { dir.deleteRecursively() }
    }

    // --- R6 F4: SHADOW schreibt atomaren Snapshot; gleicher Zyklus ist idempotent ---
    @Test fun shadowPersistsAndIsIdempotent() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "dynshadow-${System.nanoTime()}").apply { mkdirs() }
        try {
            fun call(ts: Long) = DynMealIobThShadowRunner.runShadow(
                engineMode = "SHADOW", cycleTs = ts, mealActiveKnown = true, mealActive = true,
                apsGlucoseTs = ts, smbGateReason = "iobTH", actualUseIobTh = true,
                actualConfiguredPercent = 40, actualEffectiveGateU = 3.2, capIobU = 3.5,
                netIobU = 3.5, maxIobU = 8.0, profilePercent = 100, sensitivityRatio = 1.0,
                loopBg = 170.0, noise = 1.0, bgAgeMin = 1.0, flatBgs = false, delta = 5.0,
                shortAvgDelta = 4.0,
                raw = DetermineBasalAutoISF.ShadowCycleRaw(ts, 120.0, 65.0, 5.0, 140.0, 0.0),
                insReq = 1.0, minBg = 90.0, targetBg = 98.0,
                ratioFix = 0.175, ratioMin = 0.175, ratioMax = 0.175, ratioBgRange = 0.0,
                mealCob = 0.0, carbRatio = 9.0, currentBasal = 0.55, maxSmbMinutes = 90.0,
                maxUamSmbMinutes = 60.0, smbMaxRangeExt = 1.0, bolusIncrementU = 0.05,
                skipNeutralTemps = false, localMinute = 30, actualMaxBolusU = 0.6, stateDir = dir,
            )
            val ts = System.nanoTime()          // eindeutig ggü. anderen Tests (Runner-Singleton)
            val first = call(ts)
            assertThat(first).isNotNull()
            assertThat(first!!.getString("engineMode")).isEqualTo("SHADOW")
            assertThat(first.getJSONObject("actual").getBoolean("maxBolusAgreement")).isTrue()
            assertThat(java.io.File(dir, "dynmeal_shadow_state.json").exists()).isTrue()
            assertThat(java.io.File(dir, "dynmeal_shadow_state.json.tmp").exists()).isFalse()
            assertThat(call(ts)).isNull()       // gleicher Calculate-Zyklus: idempotent (Test 12)
        } finally { dir.deleteRecursively() }
    }

    // --- eligibilityFromReason: vollstaendige Matrix ueber alle Reasons (R6 Attention 1) ---
    @Test fun eligibilityMatrixComplete() {
        for (reason in listOf("microbolus-disabled", "calibration", "even-odd-off", "odd-target", "max-iob-zero", "iobTH", "none", null, "future-reason")) {
            val e = DynMealIobThShadowRunner.eligibilityFromReason(reason)
            when (reason) {
                "none", "iobTH" -> {
                    assertThat(e.microBolusAllowed).isEqualTo(true)
                    assertThat(e.targetIsEven).isEqualTo(true)
                    assertThat(e.maxIobPositive).isEqualTo(true)
                }
                null, "future-reason" -> assertThat(e.microBolusAllowed).isNull()
                else -> assertThat(e.microBolusAllowed != null || e.calibrationStopsSmb != null).isTrue()
            }
        }
    }

    // --- loopBgFloor (Test 33): Kandidat mit Floor 155 blockt UP unter 155 ---
    @Test fun loopBgFloorBlocksUp() {
        var w: DynWindowState? = null
        for (k in 0..2) { w = DynShadowStep.step(w, boundInputs(t0 + min(k), glucoseTs = t0 + min(k), bg = 150.0)).window }
        val last = DynShadowStep.step(w, boundInputs(t0 + min(3), glucoseTs = t0 + min(3), bg = 150.0))
        assertThat(last.decisions.first { it.candidateId == "s10_f155_cd5" }.direction).isNotEqualTo("UP")
        assertThat(last.decisions.first { it.candidateId == "s10_fnone_cd5" }.rungAfter).isEqualTo(50)
    }
}
