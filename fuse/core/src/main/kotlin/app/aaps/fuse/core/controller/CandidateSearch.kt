package app.aaps.fuse.core.controller

import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.predictor.IsfSlot
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.profile.ProfileSlots
import kotlin.math.floor

/**
 * Kandidatensuche mit BEIDEN Bandkanten (K2-C v0.3 §5, R76-F6).
 *
 * v0.2 hatte nur die Unterkante. Damit konnte ein Pumpeninkrement noch
 * akzeptiert werden, obwohl die Ausgangsbahn bereits im Zielband lag —
 * `NO_DEMAND` war umbenannt, nicht geschlossen. Deshalb steht das
 * EINTRITTSTOR vor der Kandidatenschleife und prueft die BASELINE, nicht
 * den Kandidaten.
 *
 * Die Wirkung eines Kandidaten wird durch den Einheitskern propagiert, nicht
 * pauschal angesetzt: `deltaBg(t) = -doseU * INTEGRAL(activity * isf)`. Vor
 * `kernel.deliveryTs` ist dieses Integral exakt null (v0.3.1 C1) — sonst wuerde
 * eine noch nicht gelieferte Dosis die Bahn anheben und sich selbst
 * rechtfertigen.
 *
 * Rein: keine Uhr, keine Pumpe, kein Ledger-Zugriff. Was der Ledger beitraegt
 * (transportCommitment) steckt bereits in den uebergebenen Headrooms.
 */
object CandidateSearch {

    /** Zielband und Horizonte. ALLE Zahlen sind uebergeben, keine ist gelockt —
     *  R77: GO fuer Code mit validierten Konfigurationsobjekten, noch kein GO
     *  fuer Live-Defaults. */
    data class Band(
        val releaseTargetLowMgdl: Double,
        val releaseTargetHighMgdl: Double,
        val demandDeadbandMgdl: Double,
        val guardFloorMgdl: Double,
        val releaseHorizonMin: Int,
        val liabilityHorizonMin: Int,
    ) {

        init {
            require(releaseTargetLowMgdl <= releaseTargetHighMgdl) { "target band inverted" }
            require(demandDeadbandMgdl >= 0.0) { "deadband negative" }
            require(releaseHorizonMin > 0 && liabilityHorizonMin > 0) { "horizon not positive" }
        }
    }

    /** Mengengrenzen. `remainingReleaseBudgetU` wird INJIZIERT — die Budgetpolicy
     *  selbst ist bis KC2-53 ausdruecklich nicht entschieden. */
    data class Caps(
        val remainingReleaseBudgetU: Double,
        val effectiveIobThHeadroomU: Double,
        val effectiveMaxIobHeadroomU: Double,
        val pumpIncrementU: Double,
        val maxSmbU: Double,
    )

    enum class Reject {
        LEDGER_HOLD,             // Vertragsbruch im Ledger -> keine neue Dosis
        HORIZON_MISSING,         // Bahn deckt Release-/Liability-Horizont nicht
        MODEL_HORIZON_TOO_SHORT, // Einheitskern deckt das Bewertungsfenster nicht (KC2-37)
        ISF_SLOT_MISSING,        // Luecke in den ISF-Slots -> kein Ersatzwert
        DELIVERY_BEFORE_ANCHOR,  // Kandidat waere vor dem Bahnanfang geliefert
        NON_FINITE,
        NO_HEADROOM,             // eine Mengengrenze ist bereits ausgeschoepft
        NO_DEMAND,               // Eintrittstor: Baseline liegt im Zielband
        GUARD_FLOOR,             // schon das kleinste Inkrement verletzt den Guard
        BELOW_TARGET_BAND,       // jeder zulaessige Kandidat drueckt den Mittelwert unter targetLow
        BELOW_PUMP_INCREMENT,    // Spielraum kleiner als eine Pumpenstufe
    }

    data class Result(
        val smbU: Double,
        val reject: Reject?,
        val bindingLimit: String,
        val baselineMeanAtReleaseMgdl: Double?,
        val meanWithCandidateMgdl: Double?,
        val minLowerWithCandidateMgdl: Double?,
        val effectPerUAtReleaseMgdl: Double?,
        val candidatesEvaluated: Int,
    )

    fun search(
        prediction: PredictorResult,
        kernel: UnitInsulinKernel,
        isfSlots: List<IsfSlot>,
        band: Band,
        caps: Caps,
        ledgerHold: Boolean = false,
    ): Result {
        if (ledgerHold) return no(Reject.LEDGER_HOLD, "ledgerHold")
        if (!caps.pumpIncrementU.isFinite() || caps.pumpIncrementU <= 0.0) return no(Reject.NON_FINITE, "pumpIncrement")
        if (listOf(
                caps.remainingReleaseBudgetU, caps.effectiveIobThHeadroomU,
                caps.effectiveMaxIobHeadroomU, caps.maxSmbU
            ).any { !it.isFinite() }
        ) return no(Reject.NON_FINITE, "caps")

        val points = prediction.points
        if (points.isEmpty()) return no(Reject.HORIZON_MISSING, "no points")
        val releaseIdx = points.indexOfFirst { it.offsetMin == band.releaseHorizonMin }
        val liabilityIdx = points.indexOfFirst { it.offsetMin == band.liabilityHorizonMin }
        if (releaseIdx < 0 || liabilityIdx < 0) return no(Reject.HORIZON_MISSING, "release/liability offset absent")

        val windowEndTs = points[maxOf(releaseIdx, liabilityIdx)].tsMs
        if (kernel.deliveryTs < points.first().tsMs)
            return no(Reject.DELIVERY_BEFORE_ANCHOR, "deliveryTs=${kernel.deliveryTs}")
        // KC2-37: Deckt der Modellhorizont das Bewertungsfenster nicht ab, wird
        // NICHT mit einer stillen Null weitergerechnet.
        if (!kernel.covers(windowEndTs)) return no(Reject.MODEL_HORIZON_TOO_SHORT, "supportEnd=${kernel.supportEndTs}")

        // Wirkung je Einheit, aufsummiert mit der rechten Integrationsregel auf
        // dem Punktraster der Bahn. Das Intervall, in das die Lieferung faellt,
        // zaehlt NUR mit seinem Teil NACH `deliveryTs` — sonst bekaeme die
        // Minute vor der Abgabe schon Wirkung zugerechnet.
        val effectPerU = DoubleArray(points.size)
        var acc = 0.0
        for (i in points.indices) {
            val p = points[i]
            val isf = ProfileSlots.isfAt(isfSlots, p.tsMs) ?: return no(Reject.ISF_SLOT_MISSING, "ts=${p.tsMs}")
            val intervalStart = maxOf(if (i == 0) p.tsMs else points[i - 1].tsMs, kernel.deliveryTs)
            val overlapMin = if (p.tsMs > intervalStart) (p.tsMs - intervalStart) / 60_000.0 else 0.0
            if (overlapMin > 0.0) acc += kernel.activityAt(p.tsMs, 1.0) * isf * overlapMin
            effectPerU[i] = acc
            if (!acc.isFinite()) return no(Reject.NON_FINITE, "effect at offset ${p.offsetMin}")
        }

        val baselineMean = points[releaseIdx].meanBg

        // 1. EINTRITTSTOR ohne Kandidat: liegt die Ausgangsbahn schon im Band,
        //    gibt es keinen Bedarf — unabhaengig davon, was ein Inkrement taete.
        if (baselineMean <= band.releaseTargetHighMgdl + band.demandDeadbandMgdl)
            return Result(
                0.0, Reject.NO_DEMAND, "baselineMean<=targetHigh+deadband",
                baselineMean, baselineMean, prediction.minLowerBg, effectPerU[releaseIdx], 0
            )

        // 2. Mengenraum
        val limits = listOf(
            "releaseBudget" to caps.remainingReleaseBudgetU,
            "iobThHeadroom" to caps.effectiveIobThHeadroomU,
            "maxIobHeadroom" to caps.effectiveMaxIobHeadroomU,
            "maxSmb" to caps.maxSmbU,
        )
        val binding = limits.minByOrNull { it.second }!!
        if (binding.second <= 0.0)
            return Result(0.0, Reject.NO_HEADROOM, binding.first, baselineMean, null, null, effectPerU[releaseIdx], 0)
        val maxTicks = floor(binding.second / caps.pumpIncrementU + 1e-9).toInt()
        if (maxTicks < 1)
            return Result(0.0, Reject.BELOW_PUMP_INCREMENT, binding.first, baselineMean, null, null, effectPerU[releaseIdx], 0)

        // 3. Groesster Kandidat, der BEIDE Bedingungen besteht. Absteigend, weil
        //    die zulaessige Menge nach oben begrenzt ist: mehr Insulin senkt
        //    Guardbahn UND Mittelwert monoton.
        fun minLowerWith(u: Double): Double {
            var m = Double.MAX_VALUE
            for (i in 0..liabilityIdx) {
                val v = points[i].lowerBg - u * effectPerU[i]
                if (v < m) m = v
            }
            return m
        }

        var evaluated = 0
        var guardFailedAtSmallest = false
        for (ticks in maxTicks downTo 1) {
            val u = ticks * caps.pumpIncrementU
            evaluated++
            val minLower = minLowerWith(u)
            val meanWith = baselineMean - u * effectPerU[releaseIdx]
            val guardOk = minLower >= band.guardFloorMgdl
            val targetOk = meanWith >= band.releaseTargetLowMgdl
            if (guardOk && targetOk) {
                // Was die Menge WIRKLICH begrenzt hat. Beim groessten
                // zulaessigen Kandidaten ist es die Mengengrenze; sonst die
                // Bedingung, an der die naechste Stufe scheitert. Ein Export,
                // der immer nur die kleinste Kappe nennt, wuerde die Ursache
                // verschweigen.
                val reason = if (ticks == maxTicks) binding.first else {
                    val next = (ticks + 1) * caps.pumpIncrementU
                    if (minLowerWith(next) < band.guardFloorMgdl) "guardFloor" else "releaseTargetLow"
                }
                return Result(u, null, reason, baselineMean, meanWith, minLower, effectPerU[releaseIdx], evaluated)
            }
            if (ticks == 1) guardFailedAtSmallest = !guardOk
        }

        val reject = if (guardFailedAtSmallest) Reject.GUARD_FLOOR else Reject.BELOW_TARGET_BAND
        return Result(0.0, reject, binding.first, baselineMean, null, null, effectPerU[releaseIdx], evaluated)
    }

    private fun no(reject: Reject, detail: String) =
        Result(0.0, reject, detail, null, null, null, null, 0)
}
