package app.aaps.plugins.aps.openAPSAutoISF

/**
 * DynamicMealIobTH — SHADOW-Zustandsmaschine (Spez v1.1-v1.3, Bauauflage A, R6-Korrekturen).
 * Pure, deterministisch, dosierungsneutral. Reihenfolge je Zyklus: 1. Fensterflanken IMMER,
 * 2. Rung-Change/Confounder IMMER monoton, 3. Hard-Safety IMMER, 4.-6. Glukose-Dedupe nur
 * fuer Score-Evidenz. R6: MEAL_ACTIVE-unknown blockt Score/UP/SOFT_DOWN im laufenden Fenster
 * (F1); Evidenz strikt MONOTON auf der apsGlucoseTs-Achse mit echtem 7-min-Fenster (F2);
 * Binding wird auch am Ceiling gemessen, Confounder/CommitCount post-transition (F7);
 * numerische Waende/Headrooms/Ratio/MaxBolus je Kandidat fuer den Export (F8).
 */

data class DynEvidenceRow(val glucoseTs: Long, val pos: Boolean?, val neg: Boolean?)

data class DynCandidateState(
    val candidateId: String,
    val currentRung: Int = DynShadowSpec.MEAL_FLOOR_PERCENT,
    val upLatchedForWindow: Boolean = false,
    val lastTransitionTs: Long = 0L,
    val lastTransitionWasUp: Boolean = false,
    val rows: List<DynEvidenceRow> = emptyList(),
    val virtualCommitCountInWindow: Int = 0,
)

data class DynWindowState(
    val windowId: String,
    val startCycleTs: Long,
    val leftTruncated: Boolean,
    val actualRungChangeObserved: Boolean = false,
    val lastActualConfiguredPercent: Int? = null,
    val lastEvidenceGlucoseTs: Long = 0L,
    val candidates: Map<String, DynCandidateState> =
        DYN_SHADOW_CANDIDATES.associate { it.id to DynCandidateState(it.id) },
)

data class DynCandidateDecision(
    val candidateId: String,
    val direction: String,               // UP | SOFT_DOWN | HARD_DOWN | HOLD | UNKNOWN
    val rungBefore: Int, val rungAfter: Int,
    val upCount: Int, val downCount: Int,
    val positiveRow: Boolean?, val negativeRow: Boolean?,
    val virtualGateBound: Boolean?, val virtualSingleCapBound: Boolean?,
    val requestedRung: Int?,
    val reason: String,
    val cooldownRemainingMs: Long,
    val trajectoryConfounded: Boolean,   // R6 F7: aus dem POST-Transition-State
    val commitCountAfter: Int,
    val upLatched: Boolean,
    // R6 F8: numerische Kalibrier-Groessen
    val simulatedEffectiveGateU: Double?, val simulatedVirtualCapU: Double?,
    val entryGateHeadroomU: Double?, val singleSmbCapHeadroomU: Double?,
    val hypotheticalSmbRatio: Double?, val deliverableWantedU: Double?,
    val simulatedMaxBolusU: Double?,
    val missing: List<String>,
)

data class DynStepResult(
    val window: DynWindowState?,
    val decisions: List<DynCandidateDecision>,
    val windowEvent: String?,            // window-start | window-end | null
    val duplicateGlucose: Boolean,       // apsGlucoseTs == lastEvidenceGlucoseTs
    val outOfOrderGlucose: Boolean,      // apsGlucoseTs <  lastEvidenceGlucoseTs (Backfill)
    val scoreBlocked: Boolean,           // R6 F1: meal-active-unknown im laufenden Fenster
    val hardDown: Boolean,
    val safety: DynSafetyEligibility,
)

object DynShadowStep {

    fun step(prev: DynWindowState?, i: DynShadowInputs): DynStepResult {
        val safety = DynShadowLogic.evaluateSmbSafetyWithoutIobTh(i)

        // (1) Fensterflanken — IMMER (unknown oeffnet/schliesst NICHT, Tests 21/36)
        var windowEvent: String? = null
        var w: DynWindowState? = prev
        var scoreBlocked = false
        if (!i.mealActiveKnown) {
            if (w == null) return DynStepResult(null, emptyList(), null, false, false, true, false, safety)
            scoreBlocked = true          // R6 F1: Fenster besteht, aber Score/UP/DOWN gesperrt
        } else if (i.mealActive && w == null) {
            w = DynWindowState("meal-${i.cycleTs}", i.cycleTs, leftTruncated = false)
            windowEvent = "window-start"
        } else if (!i.mealActive && w != null) {
            return DynStepResult(null, emptyList(), "window-end", false, false, false, false, safety)
        } else if (!i.mealActive) {
            return DynStepResult(null, emptyList(), null, false, false, false, false, safety)
        }
        var window = w!!

        // (2) Actual-Rung-Change monoton latchen (Test 37; Alignment loescht nie, Test 22)
        if (i.actualConfiguredPercent != null) {
            val last = window.lastActualConfiguredPercent
            window = window.copy(
                actualRungChangeObserved = window.actualRungChangeObserved ||
                    (last != null && last != i.actualConfiguredPercent),
                lastActualConfiguredPercent = i.actualConfiguredPercent,
            )
        }

        // (3) Hard-Safety IMMER — vor Dedupe UND vor Score-Block (Test 35; F1-Ausnahme)
        val hardReasons = setOf(
            DynSafetyReason.MIN_GUARD_BELOW_THRESHOLD,
            DynSafetyReason.EVENTUAL_BELOW_MIN_BG,
            DynSafetyReason.BG_AT_OR_BELOW_THRESHOLD,
        )
        val hardDown = (i.carbsReq != null && i.carbsReq > 0) ||
            safety.reasons.any { it in hardReasons }

        // (4) R6 F2: strikt MONOTONE Glukose-Achse — <= zaehlt nie (A,B,A + Backfill)
        val glucoseTs = i.apsGlucoseTs
        val duplicate = glucoseTs != null && glucoseTs == window.lastEvidenceGlucoseTs
        val outOfOrder = glucoseTs != null && window.lastEvidenceGlucoseTs > 0L &&
            glucoseTs < window.lastEvidenceGlucoseTs
        val countsForScore = glucoseTs != null && glucoseTs > window.lastEvidenceGlucoseTs && !scoreBlocked

        val decisions = ArrayList<DynCandidateDecision>()
        val newCands = HashMap<String, DynCandidateState>()
        for (cfg in DYN_SHADOW_CANDIDATES) {
            val st = window.candidates[cfg.id] ?: DynCandidateState(cfg.id)
            val (next, dec) = stepCandidate(cfg, st, window, i, safety, hardDown, countsForScore, duplicate, outOfOrder, scoreBlocked)
            newCands[cfg.id] = next
            decisions += dec
        }
        window = window.copy(
            candidates = newCands,
            lastEvidenceGlucoseTs = if (countsForScore) glucoseTs!! else window.lastEvidenceGlucoseTs,
        )
        return DynStepResult(window, decisions, windowEvent, duplicate, outOfOrder, scoreBlocked, hardDown, safety)
    }

    private fun stepCandidate(
        cfg: DynShadowCandidateCfg, st: DynCandidateState, w: DynWindowState,
        i: DynShadowInputs, safety: DynSafetyEligibility, hardDown: Boolean,
        countsForScore: Boolean, duplicate: Boolean, outOfOrder: Boolean, scoreBlocked: Boolean,
    ): Pair<DynCandidateState, DynCandidateDecision> {
        val missing = ArrayList<String>()

        // R6 F7: Binding IMMER fuer die AKTUELLE Sprosse messen — auch am Ceiling.
        val simGate = DynShadowLogic.simulatedEffectiveGateU(i, st.currentRung)
        val simCap = simGate?.let { DynShadowSpec.VIRTUAL_CAP_TOLERANCE * it }
        val gateBound = DynShadowLogic.and3(
            DynShadowLogic.virtualGateBranchEligible(i, st.currentRung),
            if (i.capIobU != null && simGate != null) i.capIobU > simGate else null,
        )
        val hypRatio = if (i.loopBg != null && i.targetBg != null &&
            i.smbRatioFix != null && i.smbRatioMin != null && i.smbRatioMax != null && i.smbRatioBgRange != null
        ) DynShadowLogic.hypotheticalSmbRatio(
            i.loopBg.toInt(), i.targetBg, DynShadowLogic.hypotheticalLoopMode(i.targetBg),
            i.smbRatioFix, i.smbRatioMin, i.smbRatioMax, i.smbRatioBgRange,
        ) else null
        if (hypRatio == null) missing += "hypotheticalSmbRatio"
        val simMaxBolus = DynShadowLogic.simulatedMaxBolusU(i)
        val capBound = hypRatio?.let { DynShadowLogic.virtualSingleCapBound(i, st.currentRung, it) }
        val binding = DynShadowLogic.or3(gateBound, capBound)
        val deliverable = hypRatio?.let { DynShadowLogic.deliverableWantedU(i, it) }
        val demand = deliverable?.let { d -> i.bolusIncrementU?.let { d >= it } }
        val trendUp = DynShadowLogic.and3(i.delta?.let { it > 0 }, i.shortAvgDelta?.let { it > 0 })
        val floorOk: Boolean? = cfg.loopBgFloor?.let { f -> i.loopBg?.let { it >= f } } ?: true
        val posRow = DynShadowLogic.and3(binding, demand)
        val lowerRung = cfg.nextRungBelow(st.currentRung)
        val lowerHeadroomOk: Boolean? = if (lowerRung == null) false
        else DynShadowLogic.simulatedEffectiveGateU(i, lowerRung)?.let { lg ->
            i.capIobU?.let { cap -> i.bolusIncrementU?.let { incr -> lg - cap >= incr } }
        }
        val negRow = DynShadowLogic.and3(
            binding?.let { !it }, demand?.let { !it }, lowerHeadroomOk,
            i.shortAvgDelta?.let { it <= 0 },
        )
        if (binding == null) missing += "binding"
        if (demand == null) missing += "demand"
        if (safety.eligible == null) missing += "safety"
        val entryHeadroom = if (simGate != null && i.capIobU != null) simGate - i.capIobU else null
        val singleCapHeadroom = if (simCap != null && i.capIobU != null) simCap - i.capIobU else null

        fun decision(
            next: DynCandidateState, direction: String, requested: Int?, reason: String,
            upCount: Int, downCount: Int,
        ) = DynCandidateDecision(
            cfg.id, direction, st.currentRung, next.currentRung, upCount, downCount,
            posRow, negRow, gateBound, capBound, requested, reason,
            cooldownRemaining(cfg, next, i.cycleTs),
            // R6 F7: Confounder aus dem POST-Transition-State
            next.virtualCommitCountInWindow > 0 || w.actualRungChangeObserved,
            next.virtualCommitCountInWindow, next.upLatchedForWindow,
            simGate, simCap, entryHeadroom, singleCapHeadroom,
            hypRatio, deliverable, simMaxBolus, missing,
        )

        // (3) Hard-Down wirkt IMMER virtuell — auch bei duplicate/scoreBlocked (Test 35)
        if (hardDown) {
            val next = st.copy(
                currentRung = DynShadowSpec.MEAL_FLOOR_PERCENT,
                upLatchedForWindow = true, rows = emptyList(),
                lastTransitionTs = i.cycleTs, lastTransitionWasUp = false,
            )
            return next to decision(next, "HARD_DOWN", DynShadowSpec.MEAL_FLOOR_PERCENT, "hard-safety", 0, 0)
        }

        // (5) R6 F1/F2: keine Score-Zeile ohne neue monotone Glukose bzw. bei unknown-Meal
        if (!countsForScore) {
            val reason = when {
                scoreBlocked -> "meal-active-unknown"
                duplicate -> "duplicate-glucose"
                outOfOrder -> "out-of-order-glucose"
                else -> "unknown-glucose-ts"
            }
            if (i.apsGlucoseTs == null) missing += "apsGlucoseTs"
            val dir = if (scoreBlocked || i.apsGlucoseTs == null) "UNKNOWN" else "HOLD"
            return st to decision(st, dir, null, reason,
                st.rows.count { it.pos == true }, st.rows.count { it.neg == true })
        }

        // (6) R6 F2: echtes 5-in-<=7-min-Fenster auf der GLUKOSE-Zeitachse
        val gTs = i.apsGlucoseTs!!
        var rows = st.rows.filter { it.glucoseTs >= gTs - DynShadowSpec.SCORE_WINDOW_MAX_MS }
        rows = (rows + DynEvidenceRow(gTs, posRow, negRow)).takeLast(DynShadowSpec.SCORE_WINDOW_CYCLES)
        val upCount = rows.count { it.pos == true }
        val downCount = rows.count { it.neg == true }

        val cooldownMs = cfg.upCooldownMin * 60_000L
        val cooldownOk = st.lastTransitionTs == 0L || i.cycleTs - st.lastTransitionTs >= cooldownMs
        val hysteresisBlocksDown = st.lastTransitionWasUp &&
            i.cycleTs - st.lastTransitionTs < DynShadowSpec.POST_UP_DOWN_HYSTERESIS_MS
        val nextUp = cfg.nextRungAbove(st.currentRung)

        val upAllowed = upCount >= DynShadowSpec.UP_THRESHOLD_ROWS &&
            downCount < DynShadowSpec.DOWN_THRESHOLD_ROWS &&
            trendUp == true && floorOk == true && safety.eligible == true &&
            !st.upLatchedForWindow && nextUp != null && cooldownOk
        val downAllowed = downCount >= DynShadowSpec.DOWN_THRESHOLD_ROWS &&
            upCount < DynShadowSpec.UP_THRESHOLD_ROWS &&
            !hysteresisBlocksDown && lowerRung != null && cooldownOk

        return when {
            upAllowed -> {
                val next = st.copy(
                    currentRung = nextUp!!, rows = emptyList(),
                    lastTransitionTs = i.cycleTs, lastTransitionWasUp = true,
                    virtualCommitCountInWindow = st.virtualCommitCountInWindow + 1,
                )
                next to decision(next, "UP", nextUp, "up-earned-3of5", upCount, downCount)
            }
            downAllowed -> {
                val next = st.copy(
                    currentRung = lowerRung!!, rows = emptyList(),
                    lastTransitionTs = i.cycleTs, lastTransitionWasUp = false,
                    virtualCommitCountInWindow = st.virtualCommitCountInWindow + 1,
                )
                next to decision(next, "SOFT_DOWN", lowerRung, "budget-unused-3of5", upCount, downCount)
            }
            else -> {
                val dir = if (missing.isNotEmpty() && posRow == null && negRow == null) "UNKNOWN" else "HOLD"
                val next = st.copy(rows = rows)
                next to decision(next, dir, null, if (dir == "UNKNOWN") "unknown-inputs" else "hold", upCount, downCount)
            }
        }
    }

    private fun cooldownRemaining(cfg: DynShadowCandidateCfg, st: DynCandidateState, nowTs: Long): Long {
        if (st.lastTransitionTs == 0L) return 0L
        return (cfg.upCooldownMin * 60_000L - (nowTs - st.lastTransitionTs)).coerceAtLeast(0L)
    }
}
