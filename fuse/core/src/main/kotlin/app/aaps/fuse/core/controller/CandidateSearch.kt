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

        /** Kein `require`: eine Ausnahme aus dem Regelpfad wuerde im Loop
         *  landen. Ungueltige Konfiguration ergibt einen benannten
         *  Ablehnungsgrund (R79-F7). */
        fun violation(): String? = when {
            !releaseTargetLowMgdl.isFinite() || !releaseTargetHighMgdl.isFinite() -> "target not finite"
            !demandDeadbandMgdl.isFinite() || !guardFloorMgdl.isFinite()          -> "deadband/guard not finite"
            releaseTargetLowMgdl > releaseTargetHighMgdl                          -> "target band inverted"
            demandDeadbandMgdl < 0.0                                              -> "deadband negative"
            guardFloorMgdl <= 0.0                                                 -> "guardFloor not positive"
            releaseHorizonMin <= 0 || liabilityHorizonMin <= 0                    -> "horizon not positive"
            // Der Guard muss mindestens so weit reichen wie das Freigabeziel —
            // sonst wuerde gegen einen Zeitpunkt dosiert, den er nie prueft.
            releaseHorizonMin > liabilityHorizonMin                               -> "releaseHorizon beyond liabilityHorizon"
            else                                                                  -> null
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
    ) {

        fun violation(): String? = when {
            !pumpIncrementU.isFinite() || pumpIncrementU <= 0.0 -> "pumpIncrement=$pumpIncrementU"
            !remainingReleaseBudgetU.isFinite()                 -> "releaseBudget not finite"
            !effectiveIobThHeadroomU.isFinite()                 -> "iobThHeadroom not finite"
            !effectiveMaxIobHeadroomU.isFinite()                -> "maxIobHeadroom not finite"
            !maxSmbU.isFinite() || maxSmbU < 0.0                -> "maxSmb=$maxSmbU"
            else                                                -> null
        }
    }

    enum class Reject {
        LEDGER_HOLD,             // Vertragsbruch im Ledger -> keine neue Dosis
        HORIZON_MISSING,         // Bahn deckt Release-/Liability-Horizont nicht
        MODEL_HORIZON_TOO_SHORT, // Einheitskern deckt das Bewertungsfenster nicht (KC2-37)
        ISF_SLOT_MISSING,        // Luecke in den ISF-Slots -> kein Ersatzwert
        GRID_INCONSISTENT,       // Punktraster passt nicht zum Anker (R81-F1)
        DELIVERY_BEFORE_ANCHOR,  // Kandidat waere vor dem Bahnanfang geliefert
        DELIVERY_AFTER_RELEASE,  // Lieferung liegt hinter dem Freigabehorizont (R79-F5)
        NO_EFFECT_IN_WINDOW,     // Kandidat waere im Schutzfenster wirkungslos
        INVALID_BAND,
        INVALID_CAPS,
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

    /**
     * FIX 6b (Re-Audit c750169, 6.4): finale WIRKUNGSPRUEFUNG einer bereits
     * beschlossenen Menge - fuer die Sofort-Freigabe, die eine
     * kandidatengekappte Basis wieder anheben kann.
     *
     * Bewusst OHNE das Eintrittstor der Suche (NO_DEMAND wuerde die
     * Sofort-Freigabe strukturell toeten - genau deshalb sass sie hinter dem
     * CandidateGate), aber MIT der harten Sicherheitsachse: die Guardbahn
     * inklusive der Wirkung dieser Menge darf den Boden ueber den gesamten
     * Haftungshorizont nicht reissen. Alle technischen Ausfaelle sind hier
     * FAIL-CLOSED - `null` heisst sicher, JEDER Reject verweigert die
     * Anhebung. Eine Anhebung ist ein Extra, kein Grundbedarf.
     *
     * Die Vorpruefungen und die Wirkungsintegration sind bewusst dieselben
     * Regeln wie in [search] (rechte Integrationsregel, Anker-Minimum R83-F1,
     * Lieferintervall-Anteil) - Aenderungen dort muessen hier nachgezogen
     * werden.
     */
    fun verifyGuardFloor(
        prediction: PredictorResult,
        kernel: UnitInsulinKernel,
        isfSlots: List<IsfSlot>,
        band: Band,
        candidateU: Double,
    ): Reject? {
        if (!candidateU.isFinite() || candidateU <= 0.0) return Reject.NON_FINITE
        if (band.violation() != null) return Reject.INVALID_BAND
        val points = prediction.points
        if (points.isEmpty()) return Reject.HORIZON_MISSING
        val releaseIdx = points.indexOfFirst { it.offsetMin == band.releaseHorizonMin }
        val liabilityIdx = points.indexOfFirst { it.offsetMin == band.liabilityHorizonMin }
        if (releaseIdx < 0 || liabilityIdx < 0) return Reject.HORIZON_MISSING
        val anchorTs = prediction.predictionAnchorTs
        if (!prediction.bgAtAnchor.isFinite()) return Reject.GRID_INCONSISTENT
        for (i in points.indices) {
            val p = points[i]
            if (p.offsetMin != i + 1 || p.tsMs != anchorTs + (i + 1) * 60_000L) return Reject.GRID_INCONSISTENT
        }
        if (kernel.deliveryTs < anchorTs) return Reject.DELIVERY_BEFORE_ANCHOR
        if (kernel.deliveryTs >= points[releaseIdx].tsMs) return Reject.DELIVERY_AFTER_RELEASE
        if (!kernel.covers(points[maxOf(releaseIdx, liabilityIdx)].tsMs)) return Reject.MODEL_HORIZON_TOO_SHORT

        var acc = 0.0
        var minLower = prediction.bgAtAnchor
        for (i in 0..liabilityIdx) {
            val p = points[i]
            val isf = ProfileSlots.isfAt(isfSlots, p.tsMs) ?: return Reject.ISF_SLOT_MISSING
            val previousTs = if (i == 0) anchorTs else points[i - 1].tsMs
            val intervalStart = maxOf(previousTs, kernel.deliveryTs)
            val overlapMin = if (p.tsMs > intervalStart) (p.tsMs - intervalStart) / 60_000.0 else 0.0
            if (overlapMin > 0.0) acc += kernel.activityAt(p.tsMs, 1.0) * isf * overlapMin
            if (!acc.isFinite()) return Reject.NON_FINITE
            val v = p.lowerBg - candidateU * acc
            if (v < minLower) minLower = v
        }
        return if (minLower < band.guardFloorMgdl) Reject.GUARD_FLOOR else null
    }

    fun search(
        prediction: PredictorResult,
        kernel: UnitInsulinKernel,
        isfSlots: List<IsfSlot>,
        band: Band,
        caps: Caps,
        ledgerHold: Boolean = false,
    ): Result {
        if (ledgerHold) return no(Reject.LEDGER_HOLD, "ledgerHold")
        band.violation()?.let { return no(Reject.INVALID_BAND, it) }
        caps.violation()?.let { return no(Reject.INVALID_CAPS, it) }

        val points = prediction.points
        if (points.isEmpty()) return no(Reject.HORIZON_MISSING, "no points")
        val releaseIdx = points.indexOfFirst { it.offsetMin == band.releaseHorizonMin }
        val liabilityIdx = points.indexOfFirst { it.offsetMin == band.liabilityHorizonMin }
        if (releaseIdx < 0 || liabilityIdx < 0) return no(Reject.HORIZON_MISSING, "release/liability offset absent")

        // R81-F1: Der Anker kommt aus dem Predictor, NICHT aus dem ersten Punkt.
        // Das echte Zukunftsarray beginnt bei offsetMin = 1; wer den ersten
        // Punkt fuer den Anker haelt, weist eine Lieferung am Anker als
        // "vor dem Bahnanfang" ab und verliert die erste Wirkminute.
        val anchorTs = prediction.predictionAnchorTs
        if (!prediction.bgAtAnchor.isFinite())
            return no(Reject.GRID_INCONSISTENT, "bgAtAnchor=${prediction.bgAtAnchor}")
        // R83-F6: Es genuegt NICHT, jeden Punkt einzeln gegen seinen eigenen
        // Offset zu pruefen — die Offsets 1,3,2 erfuellen das und werden danach
        // indexbasiert als Minute 1,2,3 gelesen. Verlangt wird das lueckenlose,
        // geordnete Raster, das der echte Predictor ohnehin erzeugt.
        for (i in points.indices) {
            val p = points[i]
            if (p.offsetMin != i + 1 || p.tsMs != anchorTs + (i + 1) * 60_000L)
                return no(Reject.GRID_INCONSISTENT, "index=$i offset=${p.offsetMin} ts=${p.tsMs} anchor=$anchorTs")
        }

        val windowEndTs = points[maxOf(releaseIdx, liabilityIdx)].tsMs
        if (kernel.deliveryTs < anchorTs)
            return no(Reject.DELIVERY_BEFORE_ANCHOR, "deliveryTs=${kernel.deliveryTs} anchor=$anchorTs")
        // R79-F5: liegt die Lieferung hinter dem Freigabehorizont, ist die
        // Wirkung im ganzen Bewertungsfenster null — Bedarf, Guard und Zielband
        // wuerden sich nicht verschlechtern, und die Suche haette den groessten
        // durch die Kappen erlaubten Kandidaten genehmigt, ohne dass eine
        // einzige Minute seiner Wirkung geprueft worden waere.
        if (kernel.deliveryTs >= points[releaseIdx].tsMs)
            return no(Reject.DELIVERY_AFTER_RELEASE, "deliveryTs=${kernel.deliveryTs} releaseTs=${points[releaseIdx].tsMs}")
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
            // Das erste Intervall beginnt am ANKER, nicht am ersten Punkt.
            val previousTs = if (i == 0) anchorTs else points[i - 1].tsMs
            val intervalStart = maxOf(previousTs, kernel.deliveryTs)
            val overlapMin = if (p.tsMs > intervalStart) (p.tsMs - intervalStart) / 60_000.0 else 0.0
            if (overlapMin > 0.0) acc += kernel.activityAt(p.tsMs, 1.0) * isf * overlapMin
            effectPerU[i] = acc
            if (!acc.isFinite()) return no(Reject.NON_FINITE, "effect at offset ${p.offsetMin}")
        }

        // Ein Kandidat ohne nachweisbare Wirkung am Freigabehorizont darf nicht
        // genehmigt werden — er waere im Schutzfenster unsichtbar.
        if (!(effectPerU[releaseIdx] > 0.0))
            return no(Reject.NO_EFFECT_IN_WINDOW, "effectPerU@release=${effectPerU[releaseIdx]}")

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
        // R83-F1: Das Minimum laeuft ueber [0..H] und beginnt deshalb AM ANKER.
        // Die Punkte starten bei Minute 1; wer nur ueber sie laeuft, prueft
        // [1..H] und laesst genau den Zeitpunkt aus, an dem der Predictor sein
        // eigenes Minimum initialisiert. Ein BG von 68 am Anker haette so einen
        // SMB durchgelassen, weil Minute 1 schon wieder ueber dem Floor lag.
        // Die Kandidatenwirkung ist am Anker null (er liegt nie nach der
        // Lieferung), deshalb geht u hier nicht ein.
        fun minLowerWith(u: Double): Double {
            var m = prediction.bgAtAnchor
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
