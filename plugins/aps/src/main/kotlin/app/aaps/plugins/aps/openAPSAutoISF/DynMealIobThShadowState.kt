package app.aaps.plugins.aps.openAPSAutoISF

/**
 * DynamicMealIobTH — SHADOW-Zustandsmaschine (Spez v2: Basis/Delta-Semantik, 19.07.2026).
 * Pure, deterministisch, dosierungsneutral. Reihenfolge je Zyklus: 1. Fensterflanken IMMER,
 * 2. Rung-Change/Confounder IMMER monoton + Basis-Drop-Reset, 3.-5. Glukose-Dedupe fuer
 * ALLE Evidenz (auch rot). v2-Kernaenderungen gegenueber v1 (R6/R7):
 *  - Die Leiter startet auf der LIVE-BASIS (realer User-iobTH%) statt auf einem 40er-Floor;
 *    die Engine haelt nur ein verdientes DELTA daruber (einseitiger Fehler-Korrektor: sie
 *    repariert Unterschaetzung der Basis, nie Ueberschaetzung).
 *  - Hard-Down braucht PERSISTENZ (RED_THRESHOLD_ROWS rote Zyklen im 5/7-min-Fenster,
 *    symmetrisch zur Up-Seite) statt Einzel-Zyklus-Trigger — FCL-evBG flackert (COB=0).
 *  - Hard-Down setzt das Delta auf 0 (zurueck zur Basis), NIE unter die Basis; der
 *    window-permanente Up-Latch entfaellt ersatzlos (Cooldown + Persistenz genuegen).
 *  - Basis-Bewegungen: aufwaerts (User eskaliert) faehrt das Delta mit; ABWAERTS
 *    (Brake/manuelle Korrektur) resettet das verdiente Delta aller Kandidaten auf 0 —
 *    die Engine darf eine bewusste Abwaerts-Korrektur nie ueberstimmen.
 */

data class DynEvidenceRow(val glucoseTs: Long, val pos: Boolean?, val neg: Boolean?, val red: Boolean = false)

data class DynCandidateState(
    val candidateId: String,
    val deltaPercent: Int = 0,
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
    val rungBefore: Int, val rungAfter: Int,   // ABSOLUT: Basis + Delta
    val deltaAfter: Int,
    val upCount: Int, val downCount: Int, val redCount: Int,
    val positiveRow: Boolean?, val negativeRow: Boolean?,
    val virtualGateBound: Boolean?, val virtualSingleCapBound: Boolean?,
    val requestedRung: Int?,
    val reason: String,
    val deltaBeforeClamp: Int?,          // R9 P1: gesetzt, wenn die Ceiling-Normalisierung griff
    val cooldownRemainingMs: Long,
    val trajectoryConfounded: Boolean,   // R6 F7: aus dem POST-Transition-State
    val commitCountAfter: Int,
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
    val redSignal: Boolean,              // Safety-rot in DIESEM Zyklus (Persistenz entscheidet)
    val hardDown: Boolean,               // R9 P1: mindestens EIN Kandidat hat WIRKLICH abgestuft
    val basePercent: Int?,               // Live-Basis dieses Zyklus (realer User-iobTH%)
    val baseDropReset: Boolean,          // Abwaerts-Basisaenderung hat alle Deltas resettet
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
            if (w == null) return DynStepResult(null, emptyList(), null, false, false, true, false, false, i.actualConfiguredPercent, false, safety)
            scoreBlocked = true          // R6 F1: Fenster besteht, aber Score/UP/DOWN gesperrt
        } else if (i.mealActive && w == null) {
            w = DynWindowState("meal-${i.cycleTs}", i.cycleTs, leftTruncated = false)
            windowEvent = "window-start"
        } else if (!i.mealActive && w != null) {
            return DynStepResult(null, emptyList(), "window-end", false, false, false, false, false, i.actualConfiguredPercent, false, safety)
        } else if (!i.mealActive) {
            return DynStepResult(null, emptyList(), null, false, false, false, false, false, i.actualConfiguredPercent, false, safety)
        }
        var window = w!!

        // (2) Actual-Rung-Change monoton latchen (Test 37; Alignment loescht nie, Test 22).
        //     v2: eine ABWAERTS-Basisaenderung (Brake/manuelle Korrektur) resettet zusaetzlich
        //     das verdiente Delta ALLER Kandidaten — die Engine ueberstimmt nie nach unten.
        var baseDropReset = false
        if (i.actualConfiguredPercent != null) {
            val last = window.lastActualConfiguredPercent
            baseDropReset = last != null && i.actualConfiguredPercent < last
            window = window.copy(
                actualRungChangeObserved = window.actualRungChangeObserved ||
                    (last != null && last != i.actualConfiguredPercent),
                lastActualConfiguredPercent = i.actualConfiguredPercent,
            )
            if (baseDropReset) {
                window = window.copy(candidates = window.candidates.mapValues { (_, c) ->
                    c.copy(deltaPercent = 0, rows = emptyList(), lastTransitionTs = i.cycleTs, lastTransitionWasUp = false)
                })
            }
        }
        // Live-Basis: aktueller realer Wert, sonst letzter im Fenster gesehener.
        val basePercent = i.actualConfiguredPercent ?: window.lastActualConfiguredPercent

        // R9 P1: Ceiling-Normalisierung VOR jeder Kandidaten-Auswertung — ein bereits
        // verdientes (oder persistiertes) Delta darf mit einer hoeher gesetzten Basis nie
        // ueber das Ceiling schieben: delta := min(delta, max(0, CEIL - Basis)). Der
        // normalisierte Wert landet im Window-State und damit in der Persistenz.
        val clampedFrom = HashMap<String, Int>()
        if (basePercent != null) {
            val maxDelta = (DynShadowSpec.MEAL_CEILING_PERCENT - basePercent).coerceAtLeast(0)
            window = window.copy(candidates = window.candidates.mapValues { (_, c) ->
                if (c.deltaPercent > maxDelta) {
                    clampedFrom[c.candidateId] = c.deltaPercent
                    c.copy(deltaPercent = maxDelta)
                } else c
            })
        }

        // (3) Safety-rot DIESES Zyklus — wirkt als Up-Blocker sofort, als Abstufung erst
        //     mit Persistenz (RED_THRESHOLD_ROWS im Evidenz-Fenster).
        val hardReasons = setOf(
            DynSafetyReason.MIN_GUARD_BELOW_THRESHOLD,
            DynSafetyReason.EVENTUAL_BELOW_MIN_BG,
            DynSafetyReason.BG_AT_OR_BELOW_THRESHOLD,
        )
        val redSignal = (i.carbsReq != null && i.carbsReq > 0) ||
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
            val (next, dec) = stepCandidate(cfg, st, window, i, safety, redSignal, basePercent, clampedFrom[cfg.id], countsForScore, duplicate, outOfOrder, scoreBlocked)
            newCands[cfg.id] = next
            decisions += dec
        }
        window = window.copy(
            candidates = newCands,
            lastEvidenceGlucoseTs = if (countsForScore) glucoseTs!! else window.lastEvidenceGlucoseTs,
        )
        // R9 P1: hardDown = mindestens ein Kandidat hat WIRKLICH abgestuft (nicht das rote Signal).
        val hardDown = decisions.any { it.direction == "HARD_DOWN" }
        return DynStepResult(window, decisions, windowEvent, duplicate, outOfOrder, scoreBlocked, redSignal, hardDown, basePercent, baseDropReset, safety)
    }

    private fun stepCandidate(
        cfg: DynShadowCandidateCfg, st: DynCandidateState, w: DynWindowState,
        i: DynShadowInputs, safety: DynSafetyEligibility, redSignal: Boolean, basePercent: Int?,
        deltaBeforeClamp: Int?,
        countsForScore: Boolean, duplicate: Boolean, outOfOrder: Boolean, scoreBlocked: Boolean,
    ): Pair<DynCandidateState, DynCandidateDecision> {
        val missing = ArrayList<String>()

        // v2: effektive Sprosse = Basis + verdientes Delta. Ohne bekannte Basis keine
        // Simulation und keine Transition (alles unknown).
        if (basePercent == null) missing += "basePercent"
        val effRung = basePercent?.plus(st.deltaPercent)

        // R6 F7: Binding IMMER fuer die AKTUELLE Sprosse messen — auch am Ceiling.
        val simGate = effRung?.let { DynShadowLogic.simulatedEffectiveGateU(i, it) }
        val simCap = simGate?.let { DynShadowSpec.VIRTUAL_CAP_TOLERANCE * it }
        val gateBound = DynShadowLogic.and3(
            effRung?.let { DynShadowLogic.virtualGateBranchEligible(i, it) },
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
        val capBound = if (hypRatio != null && effRung != null)
            DynShadowLogic.virtualSingleCapBound(i, effRung, hypRatio) else null
        val binding = DynShadowLogic.or3(gateBound, capBound)
        val deliverable = hypRatio?.let { DynShadowLogic.deliverableWantedU(i, it) }
        val demand = deliverable?.let { d -> i.bolusIncrementU?.let { d >= it } }
        val trendUp = DynShadowLogic.and3(i.delta?.let { it > 0 }, i.shortAvgDelta?.let { it > 0 })
        val floorOk: Boolean? = cfg.loopBgFloor?.let { f -> i.loopBg?.let { it >= f } } ?: true
        val posRow = DynShadowLogic.and3(binding, demand)
        // v2: SOFT_DOWN nur bis zur Basis (Delta-Floor 0) — nextDeltaBelow(0) = null.
        val lowerDelta = cfg.nextDeltaBelow(st.deltaPercent)
        val lowerHeadroomOk: Boolean? = if (lowerDelta == null || basePercent == null) false
        else DynShadowLogic.simulatedEffectiveGateU(i, basePercent + lowerDelta)?.let { lg ->
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
            upCount: Int, downCount: Int, redCount: Int,
        ) = DynCandidateDecision(
            cfg.id, direction,
            basePercent?.plus(st.deltaPercent) ?: st.deltaPercent,
            basePercent?.plus(next.deltaPercent) ?: next.deltaPercent,
            next.deltaPercent,
            upCount, downCount, redCount,
            posRow, negRow, gateBound, capBound, requested, reason,
            deltaBeforeClamp,
            cooldownRemaining(cfg, next, i.cycleTs),
            // R6 F7: Confounder aus dem POST-Transition-State
            next.virtualCommitCountInWindow > 0 || w.actualRungChangeObserved,
            next.virtualCommitCountInWindow,
            simGate, simCap, entryHeadroom, singleCapHeadroom,
            hypRatio, deliverable, simMaxBolus, missing,
        )

        // (5) R6 F1/F2: keine Evidenz-Zeile (auch keine rote) ohne neue monotone Glukose
        //     bzw. bei unknown-Meal. Ein roter Zyklus ohne frische Glukose blockt UP
        //     (redSignal unten), stuft aber nicht ab — Persistenz braucht echte Ablesungen.
        if (!countsForScore) {
            val reason = when {
                scoreBlocked -> "meal-active-unknown"
                redSignal -> "safety-red"
                duplicate -> "duplicate-glucose"
                outOfOrder -> "out-of-order-glucose"
                else -> "unknown-glucose-ts"
            }
            if (i.apsGlucoseTs == null) missing += "apsGlucoseTs"
            val dir = if (scoreBlocked || i.apsGlucoseTs == null) "UNKNOWN" else "HOLD"
            return st to decision(st, dir, null, reason,
                st.rows.count { it.pos == true }, st.rows.count { it.neg == true }, st.rows.count { it.red })
        }

        // (6) R6 F2: echtes 5-in-<=7-min-Fenster auf der GLUKOSE-Zeitachse (pos/neg/rot gemeinsam)
        val gTs = i.apsGlucoseTs!!
        var rows = st.rows.filter { it.glucoseTs >= gTs - DynShadowSpec.SCORE_WINDOW_MAX_MS }
        rows = (rows + DynEvidenceRow(gTs, posRow, negRow, redSignal)).takeLast(DynShadowSpec.SCORE_WINDOW_CYCLES)
        val upCount = rows.count { it.pos == true }
        val downCount = rows.count { it.neg == true }
        val redCount = rows.count { it.red }

        val cooldownMs = cfg.upCooldownMin * 60_000L
        val cooldownOk = st.lastTransitionTs == 0L || i.cycleTs - st.lastTransitionTs >= cooldownMs
        val hysteresisBlocksDown = st.lastTransitionWasUp &&
            i.cycleTs - st.lastTransitionTs < DynShadowSpec.POST_UP_DOWN_HYSTERESIS_MS
        val nextDelta = basePercent?.let { cfg.nextDeltaAbove(it, st.deltaPercent) }

        // (7) v2 Hard-Down: nur mit Persistenz (3/5 rote Zyklen) und nur bis zur Basis
        //     (Delta 0). Kein Latch — nach Cooldown darf neue Evidenz wieder oeffnen.
        //     Ignoriert Hysterese/Cooldown (Safety schlaegt Formalia), aber nie unter Basis.
        if (redCount >= DynShadowSpec.RED_THRESHOLD_ROWS && st.deltaPercent > 0) {
            val next = st.copy(
                deltaPercent = 0, rows = emptyList(),
                lastTransitionTs = i.cycleTs, lastTransitionWasUp = false,
            )
            return next to decision(next, "HARD_DOWN", basePercent, "hard-safety-3of5", 0, 0, 0)
        }

        val upAllowed = upCount >= DynShadowSpec.UP_THRESHOLD_ROWS &&
            downCount < DynShadowSpec.DOWN_THRESHOLD_ROWS &&
            redCount < DynShadowSpec.RED_THRESHOLD_ROWS &&
            trendUp == true && floorOk == true && safety.eligible == true &&
            !redSignal && nextDelta != null && cooldownOk
        val downAllowed = downCount >= DynShadowSpec.DOWN_THRESHOLD_ROWS &&
            upCount < DynShadowSpec.UP_THRESHOLD_ROWS &&
            !hysteresisBlocksDown && lowerDelta != null && cooldownOk

        return when {
            upAllowed -> {
                val next = st.copy(
                    deltaPercent = nextDelta!!, rows = emptyList(),
                    lastTransitionTs = i.cycleTs, lastTransitionWasUp = true,
                    virtualCommitCountInWindow = st.virtualCommitCountInWindow + 1,
                )
                next to decision(next, "UP", basePercent!! + nextDelta, "up-earned-3of5", upCount, downCount, redCount)
            }
            downAllowed -> {
                val next = st.copy(
                    deltaPercent = lowerDelta!!, rows = emptyList(),
                    lastTransitionTs = i.cycleTs, lastTransitionWasUp = false,
                    virtualCommitCountInWindow = st.virtualCommitCountInWindow + 1,
                )
                next to decision(next, "SOFT_DOWN", basePercent?.plus(lowerDelta), "budget-unused-3of5", upCount, downCount, redCount)
            }
            else -> {
                val dir = if (missing.isNotEmpty() && posRow == null && negRow == null) "UNKNOWN" else "HOLD"
                val reason = when {
                    dir == "UNKNOWN" -> "unknown-inputs"
                    redSignal -> "safety-red"
                    else -> "hold"
                }
                val next = st.copy(rows = rows)
                next to decision(next, dir, null, reason, upCount, downCount, redCount)
            }
        }
    }

    private fun cooldownRemaining(cfg: DynShadowCandidateCfg, st: DynCandidateState, nowTs: Long): Long {
        if (st.lastTransitionTs == 0L) return 0L
        return (cfg.upCooldownMin * 60_000L - (nowTs - st.lastTransitionTs)).coerceAtLeast(0L)
    }
}
