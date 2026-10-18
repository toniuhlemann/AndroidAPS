package app.aaps.plugins.aps.openAPSAutoISF

import androidx.compose.runtime.Composable
import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.core.ui.compose.preference.PreferenceRowSummaries
import app.aaps.core.ui.compose.preference.rememberPreferenceBooleanState
import app.aaps.core.ui.compose.preference.rememberPreferenceDoubleState
import app.aaps.core.ui.compose.preference.rememberPreferenceIntState
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.aps.ApsStrings
import kotlin.math.max
import kotlin.math.min

/*
 * Fork: live lines for the autoISF settings. The row shows only what the value does now; the explanation shows where
 * the value is entered (`PreferenceSubScreenDef.liveLinesOnly`).
 *
 * The rules are a plain function of the setting values, so they can be tested without Compose. They describe the
 * BASE settings: a value lease of the IOB-Action viewer can still change a weight or the fixed ratio for one run.
 */

/** What one row says, before it becomes text. */
internal sealed interface AutoIsfLiveLine {

    /** The setting does nothing while [switch] is off. */
    data class NoEffectWhileOff(val switch: BooleanKey) : AutoIsfLiveLine

    /** A weight of 0 switches its part of autoISF off. */
    data object WeightOff : AutoIsfLiveLine

    /** min and max are both 1: autoISF cannot change ISF. */
    data object RangeNone : AutoIsfLiveLine

    data class RangeStronger(val stronger: Double) : AutoIsfLiveLine
    data class RangeWeaker(val weaker: Double) : AutoIsfLiveLine
    data class RangeBoth(val stronger: Double, val weaker: Double) : AutoIsfLiveLine

    /** At 100 % the iobTH method is off. */
    data class IobThOff(val percent: Int) : AutoIsfLiveLine

    /** The iobTH before profile % and sensitivity scale it in the run. */
    data class IobTh(val baseU: Double, val percent: Int, val maxIobU: Double) : AutoIsfLiveLine

    data class RampOff(val fixedRatio: Double) : AutoIsfLiveLine
    data class Ramp(val lowRatio: Double, val highRatio: Double, val rangeMgdl: Double) : AutoIsfLiveLine
    data object NotUsedRampOff : AutoIsfLiveLine
    data object NotUsedRampOn : AutoIsfLiveLine

    /** With the ramp on, the fixed ratio counts only in full loop, as the lowest ratio. */
    data class FullLoopFloor(val belowMgdl: Double) : AutoIsfLiveLine
}

/** The settings the lines are made from. */
internal data class AutoIsfLiveInput(
    val weightsSwitch: Boolean,
    val autoIsfMin: Double,
    val autoIsfMax: Double,
    val weights: Map<DoubleKey, Double>,
    val iobThPercent: Int,
    val maxIobU: Double,
    val smbOnEvenTarget: Boolean,
    val fixedRatio: Double,
    val ratioMin: Double,
    val ratioMax: Double,
    val bgRangeSetting: Double
)

internal object AutoIsfLiveLines {

    val weightKeys: List<DoubleKey> = listOf(
        DoubleKey.ApsAutoIsfBgAccelWeight,
        DoubleKey.ApsAutoIsfBgBrakeWeight,
        DoubleKey.ApsAutoIsfLowBgWeight,
        DoubleKey.ApsAutoIsfHighBgWeight,
        DoubleKey.ApsAutoIsfPpWeight,
        DoubleKey.ApsAutoIsfDuraWeight
    )

    /** An even target below this is full loop (`loop_smb`), where the SMB ratio is max(fixed, ramp). */
    const val FULL_LOOP_BELOW_MGDL = 100.0

    /** Row key -> line. Rows without a line keep their explanation. */
    fun lines(input: AutoIsfLiveInput): Map<String, AutoIsfLiveLine> = buildMap {
        // autoISF() skips all weights, and with them the limits, while the switch is off
        // (usingDynamicIsf() is always true in this plugin).
        if (!input.weightsSwitch) {
            val off = AutoIsfLiveLine.NoEffectWhileOff(BooleanKey.ApsUseAutoIsfWeights)
            (weightKeys + DoubleKey.ApsAutoIsfMin + DoubleKey.ApsAutoIsfMax).forEach { put(it.key, off) }
        } else {
            weightKeys.filter { (input.weights[it] ?: 0.0) <= 0.0 }.forEach { put(it.key, AutoIsfLiveLine.WeightOff) }
            val range = range(input.autoIsfMin, input.autoIsfMax)
            put(DoubleKey.ApsAutoIsfMin.key, range)
            put(DoubleKey.ApsAutoIsfMax.key, range)
        }

        // iobTH caps or blocks SMBs only in the even target modes (fullLoop, enforced).
        put(
            IntKey.ApsAutoIsfIobThPercent.key, when {
                input.iobThPercent >= 100 -> AutoIsfLiveLine.IobThOff(input.iobThPercent)
                !input.smbOnEvenTarget    -> AutoIsfLiveLine.NoEffectWhileOff(BooleanKey.ApsAutoIsfSmbOnEvenTarget)
                else                      -> AutoIsfLiveLine.IobTh(input.iobThPercent / 100.0 * input.maxIobU, input.iobThPercent, input.maxIobU)
            }
        )

        // determine_varSMBratio: a range of 0 means the fixed ratio, otherwise the ramp from the lower to the higher
        // of min and max; in full loop the larger of the fixed ratio and the ramp.
        val rangeMgdl = bgRangeMgdl(input.bgRangeSetting)
        if (rangeMgdl > 0.0) {
            put(
                DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange.key,
                AutoIsfLiveLine.Ramp(min(input.ratioMin, input.ratioMax), max(input.ratioMin, input.ratioMax), rangeMgdl)
            )
            put(
                DoubleKey.ApsAutoIsfSmbDeliveryRatio.key,
                if (input.smbOnEvenTarget) AutoIsfLiveLine.FullLoopFloor(FULL_LOOP_BELOW_MGDL) else AutoIsfLiveLine.NotUsedRampOn
            )
        } else {
            put(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange.key, AutoIsfLiveLine.RampOff(input.fixedRatio))
            put(DoubleKey.ApsAutoIsfSmbDeliveryRatioMin.key, AutoIsfLiveLine.NotUsedRampOff)
            put(DoubleKey.ApsAutoIsfSmbDeliveryRatioMax.key, AutoIsfLiveLine.NotUsedRampOff)
        }
    }

    /** The autoISF factor stays between min and max (`withinISFlimits`); ISF is divided by it. */
    fun range(min: Double, max: Double): AutoIsfLiveLine {
        val stronger = max > 1.0
        val weaker = min > 0.0 && min < 1.0
        return when {
            stronger && weaker -> AutoIsfLiveLine.RangeBoth(max, 1.0 / min)
            stronger           -> AutoIsfLiveLine.RangeStronger(max)
            weaker             -> AutoIsfLiveLine.RangeWeaker(1.0 / min)
            else               -> AutoIsfLiveLine.RangeNone
        }
    }

    /** As `smb_delivery_ratio_bg_range` reads the setting: below 10 it is taken as mmol/l. */
    fun bgRangeMgdl(setting: Double): Double = if (setting < 10.0) setting * Constants.MMOLL_TO_MGDL else setting
}

/**
 * The text of a line. An argument that is a [TextRef] is a text of its own (a value with its unit, a switch title)
 * and is resolved before the line.
 *
 * @param bg a glucose value in mg/dl as text with unit, in the units of the user
 */
internal fun AutoIsfLiveLine.textRef(bg: (Double) -> String): TextRef = when (this) {
    is AutoIsfLiveLine.NoEffectWhileOff -> ApsStrings.autoisf_live_no_effect_while_off.withArgs(switch.title)
    AutoIsfLiveLine.WeightOff           -> ApsStrings.autoisf_live_weight_off
    AutoIsfLiveLine.RangeNone           -> ApsStrings.autoisf_live_range_none
    is AutoIsfLiveLine.RangeStronger    -> ApsStrings.autoisf_live_range_stronger.withArgs(stronger)
    is AutoIsfLiveLine.RangeWeaker      -> ApsStrings.autoisf_live_range_weaker.withArgs(weaker)
    is AutoIsfLiveLine.RangeBoth        -> ApsStrings.autoisf_live_range_both.withArgs(stronger, weaker)
    is AutoIsfLiveLine.IobThOff         -> ApsStrings.autoisf_live_iob_th_off.withArgs(percent)
    is AutoIsfLiveLine.IobTh            -> ApsStrings.autoisf_live_iob_th.withArgs(insulin(baseU), percent, insulin(maxIobU))
    is AutoIsfLiveLine.RampOff          -> ApsStrings.autoisf_live_smb_ramp_off.withArgs(fixedRatio)
    is AutoIsfLiveLine.Ramp             -> ApsStrings.autoisf_live_smb_ramp.withArgs(lowRatio, highRatio, bg(rangeMgdl))
    AutoIsfLiveLine.NotUsedRampOff      -> ApsStrings.autoisf_live_smb_not_used_ramp_off
    AutoIsfLiveLine.NotUsedRampOn       -> ApsStrings.autoisf_live_smb_not_used_ramp_on
    is AutoIsfLiveLine.FullLoopFloor    -> ApsStrings.autoisf_live_smb_full_loop_floor.withArgs(bg(belowMgdl))
}

private fun insulin(units: Double): TextRef = InterfacesStrings.format_insulin_units.withArgs(units)

/**
 * The live lines of the autoISF screens. It reads every value a line depends on, so a change shows at once.
 */
internal class AutoIsfLiveSummaries(private val profileUtil: ProfileUtil) : PreferenceRowSummaries {

    @Composable
    override fun lines(): Map<String, String> {
        val input = AutoIsfLiveInput(
            weightsSwitch = rememberPreferenceBooleanState(BooleanKey.ApsUseAutoIsfWeights).value,
            autoIsfMin = rememberPreferenceDoubleState(DoubleKey.ApsAutoIsfMin).value,
            autoIsfMax = rememberPreferenceDoubleState(DoubleKey.ApsAutoIsfMax).value,
            weights = AutoIsfLiveLines.weightKeys.associateWith { rememberPreferenceDoubleState(it).value },
            iobThPercent = rememberPreferenceIntState(IntKey.ApsAutoIsfIobThPercent).value,
            maxIobU = rememberPreferenceDoubleState(DoubleKey.ApsSmbMaxIob).value,
            smbOnEvenTarget = rememberPreferenceBooleanState(BooleanKey.ApsAutoIsfSmbOnEvenTarget).value,
            fixedRatio = rememberPreferenceDoubleState(DoubleKey.ApsAutoIsfSmbDeliveryRatio).value,
            ratioMin = rememberPreferenceDoubleState(DoubleKey.ApsAutoIsfSmbDeliveryRatioMin).value,
            ratioMax = rememberPreferenceDoubleState(DoubleKey.ApsAutoIsfSmbDeliveryRatioMax).value,
            bgRangeSetting = rememberPreferenceDoubleState(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange).value
        )
        return AutoIsfLiveLines.lines(input).mapValues { (_, line) -> resolve(line.textRef { profileUtil.fromMgdlToStringWithUnits(it) }) }
    }
}

@Composable
private fun resolve(ref: TextRef): String =
    if (ref is TextRef.Named && ref.args.any { it is TextRef }) stringResource(ref.copy(args = ref.args.map { if (it is TextRef) resolve(it) else it }))
    else stringResource(ref)
