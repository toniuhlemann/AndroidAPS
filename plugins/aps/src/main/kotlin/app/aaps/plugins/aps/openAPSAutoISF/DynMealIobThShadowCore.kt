package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.max
import kotlin.math.min

/**
 * DynamicMealIobTH — SHADOW-Evaluator-Kern (Spez v1.1/v1.2/v1.3 + Bauauflagen A/B, R5 Bau-GO).
 *
 * REINE Logik, keine Android-/Plugin-Abhaengigkeiten, KEINE Dosierwirkung: berechnet je
 * APS-Zyklus fuer 12 virtuelle Kandidaten, ob eine iobTH-Leiter im FCL-Meal-Fenster eine
 * Sprosse geoeffnet/geschlossen HAETTE. Ergebnisse sind ausschliesslich Telemetrie.
 * Kein Wert aus diesem Modul wird je von loop_smb/DetermineBasal/Pumpe gelesen (R5 Gate 1).
 */

object DynShadowSpec {
    const val SPEC_VERSION = 1
    const val MEAL_FLOOR_PERCENT = 40
    const val MEAL_CEILING_PERCENT = 90
    const val SCORE_WINDOW_CYCLES = 5
    const val SCORE_WINDOW_MAX_MS = 7 * 60_000L
    const val UP_THRESHOLD_ROWS = 3          // = +60 % der ±20er-Skala
    const val DOWN_THRESHOLD_ROWS = 3        // = −60 %
    const val POST_UP_DOWN_HYSTERESIS_MS = 10 * 60_000L
    const val TELEMETRY_EPS = 0.01
    const val VIRTUAL_CAP_TOLERANCE = 1.30

    /** Policy-Identitaet: jede verhaltenswirksame Konstante; Wechsel = neue Kohorte.
     *  R6 F8: kanonischer String wird MIT exportiert, Hash ist SHA-256. */
    fun policyCanonical(): String =
        "v=$SPEC_VERSION;floor=$MEAL_FLOOR_PERCENT;ceil=$MEAL_CEILING_PERCENT;" +
            "win=$SCORE_WINDOW_CYCLES/${SCORE_WINDOW_MAX_MS};up=$UP_THRESHOLD_ROWS;" +
            "down=$DOWN_THRESHOLD_ROWS;hyst=$POST_UP_DOWN_HYSTERESIS_MS;eps=$TELEMETRY_EPS;" +
            "cap=$VIRTUAL_CAP_TOLERANCE;sem=r6;cands=" + DYN_SHADOW_CANDIDATES.joinToString(",") { it.id }

    /** R7: voller SHA-256-Digest (64 Hexzeichen), kein Praefix-Truncate. */
    fun policyHash(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(policyCanonical().toByteArray()).joinToString("") { "%02x".format(it) }
}

data class DynShadowCandidateCfg(val id: String, val stepPercent: Int, val loopBgFloor: Int?, val upCooldownMin: Int) {
    /** Rung-Sequenz Floor..Ceiling; Ceiling wird erreicht bzw. geclampt, nie uebersprungen. */
    fun rungs(): List<Int> {
        val out = ArrayList<Int>()
        var r = DynShadowSpec.MEAL_FLOOR_PERCENT
        while (r < DynShadowSpec.MEAL_CEILING_PERCENT) { out += r; r += stepPercent }
        out += DynShadowSpec.MEAL_CEILING_PERCENT
        return out
    }

    fun nextRungAbove(current: Int): Int? = rungs().firstOrNull { it > current }
    fun nextRungBelow(current: Int): Int? = rungs().lastOrNull { it < current }
}

/** 12 Kandidaten: step {5,10} × loopBgFloor {none,155} × upCooldown {5,10,15} (v1.3 §8). */
val DYN_SHADOW_CANDIDATES: List<DynShadowCandidateCfg> = buildList {
    for (step in listOf(5, 10)) for (floor in listOf<Int?>(null, 155)) for (cd in listOf(5, 10, 15))
        add(DynShadowCandidateCfg("s${step}_f${floor ?: "none"}_cd$cd", step, floor, cd))
}

/** Zyklusatomare Eingaben; null = unknown (erzeugt NIE positive Evidenz). */
data class DynShadowInputs(
    val cycleTs: Long,
    val apsGlucoseTs: Long?,                 // Evidenz-Schluessel (v1.3 §3)
    val mealActiveKnown: Boolean, val mealActive: Boolean,
    // reale Wand + IOB
    val actualUseIobTh: Boolean?, val actualConfiguredPercent: Int?,
    val actualEffectiveGateU: Double?, val capIobU: Double?, val netIobU: Double?,
    val maxIobU: Double?, val profilePercent: Int?, val sensitivityRatio: Double?,
    val smbGateReason: String?,              // 0059, nur reale Wand
    // Loop-/Gate-Eligibility (vor dem iobTH-Branch liegende loop_smb-Bedingungen)
    val microBolusAllowed: Boolean?, val evenOddFeatureEnabled: Boolean?,
    val calibrationStopsSmb: Boolean?, val targetIsEven: Boolean?,
    // BG/Trend
    val loopBg: Double?, val loopBgNoise: Double?, val bgAgeMin: Double?, val flatBgsDetected: Boolean?,
    val delta: Double?, val shortAvgDelta: Double?, val maxDelta: Double?,
    // Bedarf/Prediction
    val insReq: Double?, val eventualBg: Double?, val minGuardBg: Double?, val minBg: Double?,
    val thresholdBg: Double?, val targetBg: Double?, val carbsReq: Int?,
    // hypothetische Simulation (v1.3 §5/§6)
    val smbRatioFix: Double?, val smbRatioMin: Double?, val smbRatioMax: Double?, val smbRatioBgRange: Double?,
    val mealCob: Double?, val carbRatio: Double?, val currentBasal: Double?,
    val maxSmbBasalMinutes: Double?, val maxUamSmbBasalMinutes: Double?, val smbMaxRangeExtension: Double?,
    val bolusIncrementU: Double?,
    val skipNeutralTemps: Boolean?, val localMinute: Int?,
    val actualMaxBolusU: Double?,            // nur Paritaetskontrolle
)

// ---------------------------------------------------------------------------------------------
// Safety-Helper (v1.3 §1 / R4 §2) — DUPLIZIERT aus DetermineBasalAutoISF; Sync-Pflicht:
// glucoseValid  ~ DetermineBasalAutoISF CGM-Checks (bg>10, !=38, noise<3, age -5..12, flat>60)
// minGuard/maxDelta/bg>threshold/eventual>=minBG ~ enableSMB-Disable-Branches
// topOfHour     ~ skip_neutral_temps && minute >= 55 Return
// AENDERUNGEN DORT MUESSEN HIER NACHGEZOGEN WERDEN (Table-Tests je Branch, R3 §4).
// ---------------------------------------------------------------------------------------------
enum class DynSafetyReason {
    INVALID_OR_STALE_GLUCOSE, MICROBOLUS_NOT_ALLOWED, SMB_BASE_MODE_DISABLED,
    BG_AT_OR_BELOW_THRESHOLD, MIN_GUARD_BELOW_THRESHOLD, MAX_DELTA_EXCEEDED,
    EVENTUAL_BELOW_MIN_BG, MAX_IOB_REACHED, TOP_OF_HOUR_NEUTRAL_RETURN, UNKNOWN_INPUT, NONE
}

data class DynSafetyEligibility(val eligible: Boolean?, val reasons: Set<DynSafetyReason>, val allInputsKnown: Boolean)

object DynShadowLogic {

    fun hypotheticalLoopMode(targetBg: Double): String = if (targetBg < 100) "fullLoop" else "enforced"

    /** Log-freie pure Kopie von determine_varSMBratio() (Sync: OpenAPSAutoISFPlugin.kt). */
    fun hypotheticalSmbRatio(bg: Int, targetBg: Double, mode: String, fix: Double, rMin: Double, rMax: Double, bgRange: Double): Double {
        val lower = min(rMin, rMax)
        val higher = max(rMin, rMax)
        val higherBg = targetBg + bgRange
        var newSmb = fix
        if (bgRange > 0) {
            newSmb = lower + (higher - lower) * (bg - targetBg) / bgRange
            newSmb = max(lower, min(higher, newSmb))
        }
        if (mode == "fullLoop") return max(fix, newSmb)
        if (bgRange == 0.0) return fix
        if (bg <= targetBg) return lower
        if (bg >= higherBg) return higher
        return newSmb
    }

    /** Quellcodegleiche MaxBolus-Simulation (v1.3 §6 / R4 §7, inkl. round(...,1)). */
    fun simulatedMaxBolusU(i: DynShadowInputs): Double? {
        val cob = i.mealCob ?: return null
        val cr = i.carbRatio?.takeIf { it > 0 } ?: return null
        val basal = i.currentBasal ?: return null
        val ext = i.smbMaxRangeExtension ?: return null
        val net = i.netIobU ?: return null
        val mealInsulinReq = Math.round(cob / cr * 1000.0) / 1000.0
        val minutes = if (net > mealInsulinReq && net > 0) i.maxUamSmbBasalMinutes else i.maxSmbBasalMinutes
        minutes ?: return null
        return Math.round(ext * basal * minutes / 60 * 10.0) / 10.0
    }

    /** v1.3 §1: hypothetisch aktives SMB — einziger unbedingter Prediction-Blocker eventual<minBG. */
    fun evaluateSmbSafetyWithoutIobTh(i: DynShadowInputs): DynSafetyEligibility {
        val reasons = HashSet<DynSafetyReason>()
        var unknown = false
        fun known(vararg vs: Any?): Boolean = vs.all { it != null }.also { if (!it) unknown = true }

        if (known(i.loopBg, i.loopBgNoise, i.bgAgeMin, i.flatBgsDetected)) {
            val bg = i.loopBg!!
            val valid = bg > 10 && bg != 38.0 && i.loopBgNoise!! < 3 &&
                i.bgAgeMin!! <= 12 && i.bgAgeMin >= -5 && !(bg > 60 && i.flatBgsDetected!!)
            if (!valid) reasons += DynSafetyReason.INVALID_OR_STALE_GLUCOSE
        }
        if (i.microBolusAllowed == false) reasons += DynSafetyReason.MICROBOLUS_NOT_ALLOWED
        if (i.evenOddFeatureEnabled == false) reasons += DynSafetyReason.SMB_BASE_MODE_DISABLED
        if (known(i.loopBg, i.thresholdBg) && i.loopBg!! <= i.thresholdBg!!) reasons += DynSafetyReason.BG_AT_OR_BELOW_THRESHOLD
        if (known(i.minGuardBg, i.thresholdBg) && i.minGuardBg!! < i.thresholdBg!!) reasons += DynSafetyReason.MIN_GUARD_BELOW_THRESHOLD
        if (known(i.maxDelta, i.loopBg, i.targetBg)) {
            val pct = if (hypotheticalLoopMode(i.targetBg!!) == "fullLoop") 0.30 else 0.20
            if (i.maxDelta!! > pct * i.loopBg!!) reasons += DynSafetyReason.MAX_DELTA_EXCEEDED
        }
        if (known(i.eventualBg, i.minBg) && i.eventualBg!! < i.minBg!!) reasons += DynSafetyReason.EVENTUAL_BELOW_MIN_BG
        if (known(i.netIobU, i.maxIobU) && i.netIobU!! > i.maxIobU!!) reasons += DynSafetyReason.MAX_IOB_REACHED
        if (known(i.skipNeutralTemps, i.localMinute) && i.skipNeutralTemps!! && i.localMinute!! >= 55)
            reasons += DynSafetyReason.TOP_OF_HOUR_NEUTRAL_RETURN
        if (i.microBolusAllowed == null || i.evenOddFeatureEnabled == null) unknown = true

        return when {
            reasons.isNotEmpty() -> DynSafetyEligibility(false, reasons, !unknown)
            unknown -> DynSafetyEligibility(null, setOf(DynSafetyReason.UNKNOWN_INPUT), false)
            else -> DynSafetyEligibility(true, setOf(DynSafetyReason.NONE), true)
        }
    }

    // --- Gate (v1.3 §1/§2, Demand NIE im Agreement) ---
    fun actualGateBranchEligible(i: DynShadowInputs): Boolean? = and3(
        i.actualUseIobTh, i.microBolusAllowed, i.evenOddFeatureEnabled,
        i.calibrationStopsSmb?.let { !it }, i.targetIsEven, i.maxIobU?.let { it > 0 },
    )

    fun virtualGateBranchEligible(i: DynShadowInputs, rungPercent: Int): Boolean? = and3(
        rungPercent < 100, i.microBolusAllowed, i.evenOddFeatureEnabled,
        i.calibrationStopsSmb?.let { !it }, i.targetIsEven, i.maxIobU?.let { it > 0 },
    )

    fun actualGateBoundInferred(i: DynShadowInputs): Boolean? {
        val elig = actualGateBranchEligible(i)
        val crossed = if (i.capIobU != null && i.actualEffectiveGateU != null)
            i.capIobU > i.actualEffectiveGateU else null                  // STRIKT >, wie Fork
        return and3(elig, crossed)
    }

    fun actualGateBoundExact(i: DynShadowInputs): Boolean? =
        i.smbGateReason?.let { it == "iobTH" }

    /** Direkte Skalierung (v1.2 §6) — funktioniert auch bei global iobTH=100. */
    fun scaleUPerPercent(i: DynShadowInputs): Double? {
        val maxIob = i.maxIobU ?: return null
        val prof = i.profilePercent ?: return null
        val sens = i.sensitivityRatio ?: return null
        return maxIob * prof / 100.0 * sens / 100.0
    }

    fun simulatedEffectiveGateU(i: DynShadowInputs, rungPercent: Int): Double? =
        scaleUPerPercent(i)?.let { rungPercent * it }

    fun deliverableWantedU(i: DynShadowInputs, ratio: Double): Double? {
        val incr = i.bolusIncrementU?.takeIf { it.isFinite() && it > 0 } ?: return null
        val ins = i.insReq ?: return null
        return Math.floor((max(0.0, ins) * ratio + 1e-9) / incr) * incr
    }

    /** virtualSingleCapBound mit maxBolus + Pumpenrundung (v1.3 §5/§6). */
    fun virtualSingleCapBound(i: DynShadowInputs, rungPercent: Int, hypRatio: Double): Boolean? {
        val incr = i.bolusIncrementU?.takeIf { it.isFinite() && it > 0 } ?: return null
        val ins = i.insReq ?: return null
        val cap = i.capIobU ?: return null
        val gate = simulatedEffectiveGateU(i, rungPercent) ?: return null
        val maxBolus = simulatedMaxBolusU(i) ?: return null
        val virtualCapU = DynShadowSpec.VIRTUAL_CAP_TOLERANCE * gate
        val rawWanted = max(0.0, ins) * hypRatio
        val preCap = min(rawWanted, maxBolus)
        val virtualHeadroom = max(0.0, virtualCapU - cap)
        val deliveredPot = Math.floor((min(preCap, virtualHeadroom) + 1e-9) / incr) * incr
        val uncappedPot = Math.floor((preCap + 1e-9) / incr) * incr
        return uncappedPot > deliveredPot
    }

    fun and3(vararg vs: Boolean?): Boolean? = when {
        vs.any { it == false } -> false
        vs.any { it == null } -> null
        else -> true
    }

    fun or3(a: Boolean?, b: Boolean?): Boolean? = when {
        a == true || b == true -> true
        a == false && b == false -> false
        else -> null
    }
}
