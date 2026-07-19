package app.aaps.plugins.aps.openAPSAutoISF

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DynamicMealIobTH — SHADOW-Runner (Stufe 3): verdrahtet den puren Evaluator mit dem realen
 * Zyklus. Wird POST-Determine aus dem Export-Block aufgerufen (runCatching), Ergebnis geht
 * NUR in den Export. Keine Shadow-Groesse wird je vom Loop gelesen (R5 Gate 1).
 */
object DynMealIobThShadowRunner {

    private const val STATE_FILE = "dynmeal_shadow_state.json"
    @Volatile private var window: DynWindowState? = null
    @Volatile private var loaded = false
    @Volatile private var observedSinceLoad = false
    @Volatile private var lastCycleTs = 0L

    /** Exakte loop_smb-Branch-Rekonstruktion aus dem 0059-reason — Paritaet by construction:
     *  jeder reason beweist genau die Branches, die VOR ihm lagen (Reihenfolge loop_smb()):
     *  microbolus -> calibration -> even/odd-Feature -> odd-target -> max-iob-zero -> iobTH. */
    internal data class GateEligibility(
        val microBolusAllowed: Boolean?, val evenOddFeatureEnabled: Boolean?,
        val calibrationStopsSmb: Boolean?, val targetIsEven: Boolean?, val maxIobPositive: Boolean?,
    )

    internal fun eligibilityFromReason(reason: String?): GateEligibility = when (reason) {
        "microbolus-disabled" -> GateEligibility(false, null, null, null, null)
        "calibration"         -> GateEligibility(true, null, true, null, null)
        "even-odd-off"        -> GateEligibility(true, false, false, null, null)
        "odd-target"          -> GateEligibility(true, true, false, false, null)
        "max-iob-zero"        -> GateEligibility(true, true, false, true, false)
        "iobTH"               -> GateEligibility(true, true, false, true, true)
        "none"                -> GateEligibility(true, true, false, true, true)
        else                  -> GateEligibility(null, null, null, null, null)
    }

    /** Baut Inputs, fuehrt step() aus, persistiert atomar, liefert Export-JSON (oder null). */
    fun runShadow(
        engineMode: String,
        cycleTs: Long,
        mealActiveKnown: Boolean, mealActive: Boolean,
        apsGlucoseTs: Long?,
        smbGateReason: String?,
        actualUseIobTh: Boolean, actualConfiguredPercent: Int,
        actualEffectiveGateU: Double?, capIobU: Double?, netIobU: Double?, maxIobU: Double?,
        profilePercent: Int, sensitivityRatio: Double,
        loopBg: Double?, noise: Double?, bgAgeMin: Double?, flatBgs: Boolean?,
        delta: Double?, shortAvgDelta: Double?,
        raw: DetermineBasalAutoISF.ShadowCycleRaw?,
        insReq: Double?, carbsReq: Int?,
        minBg: Double?, targetBg: Double?,
        ratioFix: Double?, ratioMin: Double?, ratioMax: Double?, ratioBgRange: Double?,
        mealCob: Double?, carbRatio: Double?, currentBasal: Double?,
        maxSmbMinutes: Double?, maxUamSmbMinutes: Double?, smbMaxRangeExt: Double?,
        bolusIncrementU: Double?, skipNeutralTemps: Boolean?, localMinute: Int?,
        actualMaxBolusU: Double?,
        stateDir: File,
    ): JSONObject? {
        if (engineMode == "OFF") return null
        if (!loaded) { window = loadState(stateDir); loaded = true }
        if (cycleTs <= lastCycleTs) return null            // gleicher Calculate-Zyklus: idempotent (Test 12)

        val elig = eligibilityFromReason(smbGateReason)
        val inputs = DynShadowInputs(
            cycleTs = cycleTs, apsGlucoseTs = apsGlucoseTs,
            mealActiveKnown = mealActiveKnown, mealActive = mealActive,
            actualUseIobTh = actualUseIobTh, actualConfiguredPercent = actualConfiguredPercent,
            actualEffectiveGateU = actualEffectiveGateU, capIobU = capIobU, netIobU = netIobU,
            maxIobU = maxIobU, profilePercent = profilePercent, sensitivityRatio = sensitivityRatio,
            smbGateReason = smbGateReason,
            microBolusAllowed = elig.microBolusAllowed, evenOddFeatureEnabled = elig.evenOddFeatureEnabled,
            calibrationStopsSmb = elig.calibrationStopsSmb, targetIsEven = elig.targetIsEven,
            loopBg = loopBg, loopBgNoise = noise, bgAgeMin = bgAgeMin, flatBgsDetected = flatBgs,
            delta = delta, shortAvgDelta = shortAvgDelta, maxDelta = raw?.maxDelta,
            insReq = insReq, eventualBg = raw?.eventualBg, minGuardBg = raw?.minGuardBg,
            minBg = minBg, thresholdBg = raw?.thresholdBg, targetBg = targetBg, carbsReq = carbsReq,
            smbRatioFix = ratioFix, smbRatioMin = ratioMin, smbRatioMax = ratioMax, smbRatioBgRange = ratioBgRange,
            mealCob = mealCob, carbRatio = carbRatio, currentBasal = currentBasal,
            maxSmbBasalMinutes = maxSmbMinutes, maxUamSmbBasalMinutes = maxUamSmbMinutes,
            smbMaxRangeExtension = smbMaxRangeExt, bolusIncrementU = bolusIncrementU,
            skipNeutralTemps = skipNeutralTemps, localMinute = localMinute,
            actualMaxBolusU = actualMaxBolusU,
        )
        val freshProcessMidMeal = !observedSinceLoad && window == null && mealActiveKnown && mealActive
        observedSinceLoad = true
        var result = DynShadowStep.step(window, inputs)
        // Neustart mitten im Fenster ohne kompatiblen Snapshot: leftTruncated-Kohorte am Floor
        // (v1.2 §Fenster / R3 §3) — Anchor-/Sequenz-Auswertungen sind dann zensierbar.
        if (freshProcessMidMeal && result.windowEvent == "window-start") {
            result = result.copy(window = result.window?.copy(leftTruncated = true))
        }
        window = result.window
        lastCycleTs = cycleTs
        runCatching { saveState(stateDir) }

        return JSONObject().apply {
            put("specVersion", DynShadowSpec.SPEC_VERSION)
            put("policyHash", DynShadowSpec.policyHash())
            put("engineMode", engineMode)
            put("cycleTs", cycleTs)
            put("realAction", false)
            put("mealActiveKnown", mealActiveKnown)
            put("mealActive", mealActive)
            result.windowEvent?.let { put("windowEvent", it) }
            result.window?.let { w ->
                put("mealWindowId", w.windowId)
                put("leftTruncated", w.leftTruncated)
                put("actualRungChangeObserved", w.actualRungChangeObserved)
            }
            put("duplicateGlucose", result.duplicateGlucose)
            put("hardDown", result.hardDown)
            put("actual", JSONObject().apply {
                put("configuredIobThPercent", actualConfiguredPercent)
                put("useIobTh", actualUseIobTh)
                actualEffectiveGateU?.let { put("effectiveGateU", it) }
                actualEffectiveGateU?.let { put("virtualSingleSmbCapU", DynShadowSpec.VIRTUAL_CAP_TOLERANCE * it) }
                capIobU?.let { put("capIobU", it) }
                netIobU?.let { put("netIobU", it) }
                smbGateReason?.let { put("smbGateReason", it) }
                put3(this, "gateBoundExact", DynShadowLogic.actualGateBoundExact(inputs))
                put3(this, "gateBoundInferred", DynShadowLogic.actualGateBoundInferred(inputs))
                val ex = DynShadowLogic.actualGateBoundExact(inputs)
                val inf = DynShadowLogic.actualGateBoundInferred(inputs)
                if (ex != null && inf != null) put("gateAgreement", ex == inf)
                actualMaxBolusU?.let { put("maxBolusU", it) }
                raw?.let {
                    put("minGuardBg", it.minGuardBg); put("thresholdBg", it.thresholdBg)
                    put("maxDelta", it.maxDelta); put("eventualBg", it.eventualBg)
                }
                carbsReq?.let { put("carbsReq", it) }
            })
            put("safety", JSONObject().apply {
                put3(this, "eligible", result.safety.eligible)
                put("reasons", JSONArray(result.safety.reasons.map { it.name }))
                put("allInputsKnown", result.safety.allInputsKnown)
            })
            put("candidates", JSONArray().apply {
                result.decisions.forEach { d ->
                    put(JSONObject().apply {
                        put("id", d.candidateId)
                        put("direction", d.direction)
                        put("rungBefore", d.rungBefore); put("rungAfter", d.rungAfter)
                        put("upCoverage", 20 * d.upCount); put("downCoverage", -20 * d.downCount)
                        put3(this, "positiveRow", d.positiveRow); put3(this, "negativeRow", d.negativeRow)
                        put3(this, "virtualGateBound", d.virtualGateBound)
                        put3(this, "virtualSingleCapBound", d.virtualSingleCapBound)
                        d.requestedRung?.let { put("requestedRung", it) }
                        put("reason", d.reason)
                        put("cooldownRemainingMs", d.cooldownRemainingMs)
                        put("trajectoryConfounded", d.trajectoryConfounded)
                        put("counterfactualOutcomeUnknown", true)
                        if (d.missing.isNotEmpty()) put("missing", JSONArray(d.missing))
                    })
                }
            })
        }
    }

    private fun put3(o: JSONObject, key: String, v: Boolean?) {
        when (v) { null -> o.put(key, "unknown"); else -> o.put(key, v) }
    }

    // --- Persistenz: atomarer Snapshot (tmp + rename), Kohorten-Check via policyHash ---
    private fun stateFile(dir: File) = File(dir, STATE_FILE)

    private fun saveState(dir: File) {
        val w = window
        val o = JSONObject().put("v", DynShadowSpec.SPEC_VERSION).put("policyHash", DynShadowSpec.policyHash())
            .put("lastCycleTs", lastCycleTs)
        w?.let { win ->
            o.put("window", JSONObject().apply {
                put("id", win.windowId); put("start", win.startCycleTs); put("leftTrunc", win.leftTruncated)
                put("rungChange", win.actualRungChangeObserved)
                win.lastActualConfiguredPercent?.let { put("lastPct", it) }
                put("lastGlucoseTs", win.lastEvidenceGlucoseTs)
                put("cands", JSONObject().apply {
                    win.candidates.forEach { (id, c) ->
                        put(id, JSONObject().apply {
                            put("rung", c.currentRung); put("upLatch", c.upLatchedForWindow)
                            put("lastT", c.lastTransitionTs); put("lastUp", c.lastTransitionWasUp)
                            put("commits", c.virtualCommitCountInWindow)
                            put("rows", JSONArray().apply {
                                c.rows.forEach { r ->
                                    put(JSONObject().put("t", r.glucoseTs)
                                        .put("p", tri(r.pos)).put("n", tri(r.neg)))
                                }
                            })
                        })
                    }
                })
            })
        }
        val f = stateFile(dir)
        val tmp = File(dir, "$STATE_FILE.tmp")
        tmp.writeText(o.toString())
        try {
            java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
        }
    }

    private fun loadState(dir: File): DynWindowState? = runCatching {
        val f = stateFile(dir)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        // Kohorten-/Versions-Check: inkompatibel -> leftTruncated-Neustart am Floor (Test 10/11)
        if (o.optInt("v", -1) != DynShadowSpec.SPEC_VERSION ||
            o.optString("policyHash") != DynShadowSpec.policyHash()
        ) return null
        lastCycleTs = o.optLong("lastCycleTs", 0L)
        val jw = o.optJSONObject("window") ?: return null
        val cands = HashMap<String, DynCandidateState>()
        jw.optJSONObject("cands")?.let { jc ->
            jc.keys().forEach { id ->
                val c = jc.getJSONObject(id)
                val rows = ArrayList<DynEvidenceRow>()
                c.optJSONArray("rows")?.let { arr ->
                    for (k in 0 until arr.length()) arr.optJSONObject(k)?.let { r ->
                        rows += DynEvidenceRow(r.getLong("t"), unTri(r.optInt("p", -1)), unTri(r.optInt("n", -1)))
                    }
                }
                cands[id] = DynCandidateState(
                    candidateId = id, currentRung = c.optInt("rung", DynShadowSpec.MEAL_FLOOR_PERCENT),
                    upLatchedForWindow = c.optBoolean("upLatch"),
                    lastTransitionTs = c.optLong("lastT", 0L), lastTransitionWasUp = c.optBoolean("lastUp"),
                    rows = rows, virtualCommitCountInWindow = c.optInt("commits", 0),
                )
            }
        }
        DynWindowState(
            windowId = jw.getString("id"), startCycleTs = jw.getLong("start"),
            leftTruncated = jw.optBoolean("leftTrunc"),
            actualRungChangeObserved = jw.optBoolean("rungChange"),
            lastActualConfiguredPercent = jw.optInt("lastPct", -1).takeIf { it >= 0 },
            lastEvidenceGlucoseTs = jw.optLong("lastGlucoseTs", 0L),
            candidates = if (cands.isEmpty()) DYN_SHADOW_CANDIDATES.associate { it.id to DynCandidateState(it.id) } else cands,
        )
    }.getOrNull()

    private fun tri(v: Boolean?): Int = when (v) { true -> 1; false -> 0; null -> -1 }
    private fun unTri(v: Int): Boolean? = when (v) { 1 -> true; 0 -> false; else -> null }
}
