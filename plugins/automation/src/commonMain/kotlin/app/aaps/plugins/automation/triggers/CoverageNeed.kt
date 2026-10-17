package app.aaps.plugins.automation.triggers

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.IobTotal
import kotlin.math.max

/**
 * Fork (IOB-Action patches 0041 / 0048): the shared part of [TriggerCoverage] and
 * [TriggerBolusCoverage] - the insulin NEED and the IOB it is compared with.
 *
 *   need = max(0.2 U, (RAW bg - PROFILE target) / PROFILE ISF + COB / PROFILE IC)
 *
 * Deliberate inputs (Toni, 3.4 fork):
 *  - RAW bg (the last stored value), not the smoothed loop value: it leads at turns. Older than
 *    9 minutes counts as missing; then the loop's glucose status is used, if there is one.
 *  - PROFILE target, never the active TT: the rules SET TTs, reading them back would be circular.
 *  - PROFILE ISF / IC, not the per-cycle autoISF ISF: a stable denominator.
 */
internal class CoverageNeed(val need: Double, val iob: IobTotal) {

    companion object {

        /** null without a profile, without glucose, or with a profile ISF / IC that is not positive. */
        suspend fun of(deps: TriggerDeps, caller: String): CoverageNeed? {
            val profile = deps.profileFunction.getProfile() ?: return null
            val now = deps.dateUtil.now()
            val rawBg = deps.persistenceLayer.getLastGlucoseValue()
                ?.takeIf { now - it.timestamp <= T.mins(9).msecs() }?.value
                ?: deps.glucoseStatusProvider.glucoseStatusData?.glucose
                ?: return null
            val isf = profile.getProfileIsfMgdl()
            val ic = profile.getIc()
            if (isf <= 0.0 || ic <= 0.0) return null
            val target = profile.getTargetMgdl()
            val cob = deps.iobCobCalculator.getCobInfo(caller).displayCob ?: 0.0
            val iob = deps.iobCobCalculator.calculateFromTreatmentsAndTemps(now, profile)
            return CoverageNeed(max(MIN_NEED, (rawBg - target) / isf + cob / ic), iob)
        }

        /** The floor that keeps the percentage finite. */
        const val MIN_NEED = 0.2
    }
}
