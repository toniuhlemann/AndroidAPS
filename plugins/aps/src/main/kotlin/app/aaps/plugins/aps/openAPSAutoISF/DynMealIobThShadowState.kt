package app.aaps.plugins.aps.openAPSAutoISF

/**
 * DynamicMealIobTH — SHADOW-Zustandsmaschine (Stufe 2; Spez v1.1-v1.3 + Bauauflage A).
 * Pure, deterministisch, dosierungsneutral. Verbindliche Verarbeitungsreihenfolge je Zyklus
 * (R5 Bauauflage A): 1. Fensterflanken IMMER, 2. Rung-Change/Confounder IMMER monoton,
 * 3. Hard-Safety IMMER (auch bei bekanntem apsGlucoseTs), 4.-6. erst dann Glukose-Dedupe
 * fuer Score-Evidenz. "Dedupe schuetzt vor kuenstlichem 3/5-Aufbau; Safety hat Vorrang."
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
    val trajectoryConfounded: Boolean,
    val missing: List<String>,
)

data class DynStepResult(
    val window: DynWindowState?,         // null = kein aktives Fenster
    val decisions: List<DynCandidateDecision>,
    val windowEvent: String?,            // window-start | window-end | null
    val duplicateGlucose: Boolean,
    val hardDown: Boolean,
    val safety: DynSafetyEligibility,
)

object DynShadowStep {

    fun step(prev: DynWindowState?, i: DynShadowInputs): DynStepResult {
        val safety = DynShadowLogic.evaluateSmbSafetyWithoutIobTh(i)

        // (1) Fensterflanken — IMMER, unabhaengig von Dedupe (Test 36)
        var windowEvent: String? = null
        var w: DynWindowState? = prev
        if (!i.mealActiveKnown) {
            // unknown != false: Fenster weder oeffnen noch schliessen (Test 21)
            if (w == null) return DynStepResult(null, emptyList(), null, false, false, safety)
        } else if (i.mealActive && w == null) {
            w = DynWindowState("meal-${i.cycleTs}", i.cycleTs, leftTruncated = false)
            windowEvent = "window-start"
        } else if (!i.mealActive && w != null) {
            return DynStepResult(null, emptyList(), "window-end", false, false, safety)
        } else if (!i.mealActive) {
            return DynStepResult(null, emptyList(), null, false, false, safety)
        }
        var window = w!!

        // (2) Actual-Rung-Change monoton latchen (Test 37); numerisches Alignment loescht nie.
        if (i.actualConfiguredPercent != null) {
            val last = window.lastActualConfiguredPercent
            window = window.copy(
                actualRungChangeObserved = window.actualRungChangeObserved ||
                    (last != null && last != i.actualConfiguredPercent),
                lastActualConfiguredPercent = i.actualConfiguredPercent,
            )
        }

        // (3) Hard-Safety IMMER — VOR dem Dedupe (Test 35). Policy-Safety carbsReq>0 (v1.3 §1)
        // + native harte SMB-Blocker; unknown ist NIE hard (nur HOLD/UNKNOWN).
        val hardReasons = setOf(
            DynSafetyReason.MIN_GUARD_BELOW_THRESHOLD,
            DynSafetyReason.EVENTUAL_BELOW_MIN_BG,
            DynSafetyReason.BG_AT_OR_BELOW_THRESHOLD,
        )
        val hardDown = (i.carbsReq != null && i.carbsReq > 0) ||
            safety.reasons.any { it in hardReasons }

        // (4) Glukose-Dedupe NUR fuer Score-Evidenz (Tests 26/27/34)
        val glucoseTs = i.apsGlucoseTs
        val duplicate = glucoseTs != null && glucoseTs == window.lastEvidenceGlucoseTs

        val decisions = ArrayList<DynCandidateDecision>()
        val newCands = HashMap<String, DynCandidateState>()
        for (cfg in DYN_SHADOW_CANDIDATES) {
            val st = window.candidates[cfg.id] ?: DynCandidateState(cfg.id)
            val (next, dec) = stepCandidate(cfg, st, window, i, safety, hardDown, duplicate)
            newCands[cfg.id] = next
            decisions += dec
        }
        window = window.copy(
            candidates = newCands,
            lastEvidenceGlucoseTs = if (!duplicate && glucoseTs != null) glucoseTs else window.lastEvidenceGlucoseTs,
        )
        return DynStepResult(window, decisions, windowEvent, duplicate, hardDown, safety)
    }

    private fun stepCandidate(
        cfg: DynShadowCandidateCfg, st: DynCandidateState, w: DynWindowState,
        i: DynShadowInputs, safety: DynSafetyEligibility, hardDown: Boolean, duplicate: Boolean,
    ): Pair<DynCandidateState, DynCandidateDecision> {
        val missing = ArrayList<String>()
        val confounded = st.virtualCommitCountInWindow > 0 || w.actualRungChangeObserved

        // (3) Hard-Down wirkt IMMER (virtuell): Floor + Up-Latch, auch bei duplicate.
        if (hardDown) {
            val next = st.copy(
                currentRung = DynShadowSpec.MEAL_FLOOR_PERCENT,
                upLatchedForWindow = true, rows = emptyList(),
                lastTransitionTs = i.cycleTs, lastTransitionWasUp = false,
            )
            return next to DynCandidateDecision(
                cfg.id, "HARD_DOWN", st.currentRung, next.currentRung, 0, 0, null, null,
                null, null, DynShadowSpec.MEAL_FLOOR_PERCENT,
                "hard-safety", 0L, confounded, missing,
            )
        }

        // Zeilen-Evidenz (nur bei neuer Glukose; Berechnung selbst ist dedupe-frei)
        val nextUp = cfg.nextRungAbove(st.currentRung)
        val gateBound = if (nextUp == null) false
        else DynShadowLogic.and3(
            DynShadowLogic.virtualGateBranchEligible(i, st.currentRung),
            if (i.capIobU != null) DynShadowLogic.simulatedEffectiveGateU(i, st.currentRung)
                ?.let { i.capIobU > it } else null,
        )
        val hypRatio = if (i.loopBg != null && i.targetBg != null &&
            i.smbRatioFix != null && i.smbRatioMin != null && i.smbRatioMax != null && i.smbRatioBgRange != null
        ) DynShadowLogic.hypotheticalSmbRatio(
            i.loopBg.toInt(), i.targetBg, DynShadowLogic.hypotheticalLoopMode(i.targetBg),
            i.smbRatioFix, i.smbRatioMin, i.smbRatioMax, i.smbRatioBgRange,
        ) else null
        if (hypRatio == null) missing += "hypotheticalSmbRatio"
        val capBound = hypRatio?.let { DynShadowLogic.virtualSingleCapBound(i, st.currentRung, it) }
        val binding = DynShadowLogic.or3(gateBound, capBound)
        val deliverable = hypRatio?.let { DynShadowLogic.deliverableWantedU(i, it) }
        val demand = deliverable?.let { d -> i.bolusIncrementU?.let { d >= it } }
        val trendUp = DynShadowLogic.and3(i.delta?.let { it > 0 }, i.shortAvgDelta?.let { it > 0 })
        val floorOk: Boolean? = cfg.loopBgFloor?.let { f -> i.loopBg?.let { it >= f } } ?: true
        val posRow = DynShadowLogic.and3(binding, demand)
        // negativeRow (Score-Addendum §3): kein Binding, kein Bedarf, naechstniedrigere Wand
        // haette >= 1 Inkrement Headroom, Trend traegt nicht mehr.
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

        // (5) duplicate: keine Fensterzeile, kein UP/SOFT_DOWN (Test 34) — nur deskriptiv.
        if (duplicate || i.apsGlucoseTs == null) {
            if (i.apsGlucoseTs == null) missing += "apsGlucoseTs"
            return st to DynCandidateDecision(
                cfg.id, "HOLD", st.currentRung, st.currentRung,
                st.rows.count { it.pos == true }, st.rows.count { it.neg == true },
                posRow, negRow, gateBound, capBound, null,
                if (duplicate) "duplicate-glucose" else "unknown-glucose-ts",
                cooldownRemaining(cfg, st, i.cycleTs), confounded, missing,
            )
        }

        // (6) Fenster fortschreiben (5 eindeutige glucoseTs, 7-min-Gap leert)
        var rows = st.rows
        if (rows.isNotEmpty() && i.cycleTs - windowNewestTs(rows, i) > DynShadowSpec.SCORE_WINDOW_MAX_MS) rows = emptyList()
        rows = (rows + DynEvidenceRow(i.apsGlucoseTs, posRow, negRow)).takeLast(DynShadowSpec.SCORE_WINDOW_CYCLES)
        val upCount = rows.count { it.pos == true }
        val downCount = rows.count { it.neg == true }

        val cooldownMs = cfg.upCooldownMin * 60_000L
        val cooldownOk = st.lastTransitionTs == 0L || i.cycleTs - st.lastTransitionTs >= cooldownMs
        val hysteresisBlocksDown = st.lastTransitionWasUp &&
            i.cycleTs - st.lastTransitionTs < DynShadowSpec.POST_UP_DOWN_HYSTERESIS_MS

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
                next to DynCandidateDecision(
                    cfg.id, "UP", st.currentRung, nextUp, upCount, downCount, posRow, negRow,
                    gateBound, capBound, nextUp, "up-earned-3of5", 0L, confounded, missing,
                )
            }
            downAllowed -> {
                val next = st.copy(
                    currentRung = lowerRung!!, rows = emptyList(),
                    lastTransitionTs = i.cycleTs, lastTransitionWasUp = false,
                    virtualCommitCountInWindow = st.virtualCommitCountInWindow + 1,
                )
                next to DynCandidateDecision(
                    cfg.id, "SOFT_DOWN", st.currentRung, lowerRung, upCount, downCount, posRow, negRow,
                    gateBound, capBound, lowerRung, "budget-unused-3of5", 0L, confounded, missing,
                )
            }
            else -> {
                val dir = if (missing.isNotEmpty() && posRow == null && negRow == null) "UNKNOWN" else "HOLD"
                st.copy(rows = rows) to DynCandidateDecision(
                    cfg.id, dir, st.currentRung, st.currentRung, upCount, downCount, posRow, negRow,
                    gateBound, capBound, null,
                    if (dir == "UNKNOWN") "unknown-inputs" else "hold",
                    cooldownRemaining(cfg, st, i.cycleTs), confounded, missing,
                )
            }
        }
    }

    private fun windowNewestTs(rows: List<DynEvidenceRow>, i: DynShadowInputs): Long =
        // Fenster-Gap wird auf Zyklus-Zeitbasis geprueft; rows tragen glucoseTs — fuer den
        // Gap reicht der juengste glucoseTs als Naeherung derselben Zeitachse.
        rows.maxOf { it.glucoseTs }

    private fun cooldownRemaining(cfg: DynShadowCandidateCfg, st: DynCandidateState, nowTs: Long): Long {
        if (st.lastTransitionTs == 0L) return 0L
        return (cfg.upCooldownMin * 60_000L - (nowTs - st.lastTransitionTs)).coerceAtLeast(0L)
    }
}
