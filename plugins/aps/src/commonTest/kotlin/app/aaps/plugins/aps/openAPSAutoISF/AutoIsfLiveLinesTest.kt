package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.configuration.Constants
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.TextRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: the live lines of the autoISF settings, one test per rule. Synthetic values only. */
class AutoIsfLiveLinesTest {

    private val base = AutoIsfLiveInput(
        weightsSwitch = true,
        autoIsfMin = 1.0,
        autoIsfMax = 2.0,
        weights = AutoIsfLiveLines.weightKeys.associateWith { 0.5 },
        iobThPercent = 40,
        maxIobU = 5.0,
        smbOnEvenTarget = false,
        fixedRatio = 0.5,
        ratioMin = 0.4,
        ratioMax = 0.8,
        bgRangeSetting = 0.0
    )

    private fun lines(input: AutoIsfLiveInput) = AutoIsfLiveLines.lines(input)

    @Test
    fun `with the weights switch off no weight and no limit does anything`() {
        val l = lines(base.copy(weightsSwitch = false))
        val off = AutoIsfLiveLine.NoEffectWhileOff(BooleanKey.ApsUseAutoIsfWeights)
        for (key in AutoIsfLiveLines.weightKeys + DoubleKey.ApsAutoIsfMin + DoubleKey.ApsAutoIsfMax) assertEquals(off, l[key.key], key.key)
    }

    @Test
    fun `a weight of 0 is off and a used weight keeps its explanation`() {
        val l = lines(base.copy(weights = base.weights + (DoubleKey.ApsAutoIsfBgAccelWeight to 0.0)))
        assertEquals(AutoIsfLiveLine.WeightOff, l[DoubleKey.ApsAutoIsfBgAccelWeight.key])
        assertFalse(DoubleKey.ApsAutoIsfPpWeight.key in l)
    }

    @Test
    fun `min and max show the same range`() {
        val l = lines(base.copy(autoIsfMin = 0.5, autoIsfMax = 2.0))
        assertEquals(AutoIsfLiveLine.RangeBoth(2.0, 2.0), l[DoubleKey.ApsAutoIsfMin.key])
        assertEquals(l[DoubleKey.ApsAutoIsfMin.key], l[DoubleKey.ApsAutoIsfMax.key])
    }

    @Test
    fun `the range says which way autoISF may move ISF`() {
        assertEquals(AutoIsfLiveLine.RangeNone, AutoIsfLiveLines.range(1.0, 1.0))
        assertEquals(AutoIsfLiveLine.RangeStronger(1.5), AutoIsfLiveLines.range(1.0, 1.5))
        assertEquals(AutoIsfLiveLine.RangeWeaker(1.0 / 0.8), AutoIsfLiveLines.range(0.8, 1.0))
        assertEquals(AutoIsfLiveLine.RangeBoth(3.0, 1.0 / 0.5), AutoIsfLiveLines.range(0.5, 3.0))
    }

    @Test
    fun `iobTH is off at 100 percent`() {
        assertEquals(AutoIsfLiveLine.IobThOff(100), lines(base.copy(iobThPercent = 100, smbOnEvenTarget = true))[IntKey.ApsAutoIsfIobThPercent.key])
    }

    @Test
    fun `iobTH does nothing without SMB on even target`() {
        assertEquals(
            AutoIsfLiveLine.NoEffectWhileOff(BooleanKey.ApsAutoIsfSmbOnEvenTarget),
            lines(base.copy(smbOnEvenTarget = false))[IntKey.ApsAutoIsfIobThPercent.key]
        )
    }

    @Test
    fun `iobTH shows its base in units`() {
        assertEquals(AutoIsfLiveLine.IobTh(2.0, 40, 5.0), lines(base.copy(smbOnEvenTarget = true))[IntKey.ApsAutoIsfIobThPercent.key])
    }

    @Test
    fun `without a BG range every SMB uses the fixed ratio`() {
        val l = lines(base.copy(bgRangeSetting = 0.0))
        assertEquals(AutoIsfLiveLine.RampOff(0.5), l[DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange.key])
        assertEquals(AutoIsfLiveLine.NotUsedRampOff, l[DoubleKey.ApsAutoIsfSmbDeliveryRatioMin.key])
        assertEquals(AutoIsfLiveLine.NotUsedRampOff, l[DoubleKey.ApsAutoIsfSmbDeliveryRatioMax.key])
        assertFalse(DoubleKey.ApsAutoIsfSmbDeliveryRatio.key in l)
    }

    @Test
    fun `with a BG range the ramp goes from the lower to the higher ratio`() {
        val l = lines(base.copy(bgRangeSetting = 30.0, ratioMin = 0.9, ratioMax = 0.3))
        assertEquals(AutoIsfLiveLine.Ramp(0.3, 0.9, 30.0), l[DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange.key])
        assertEquals(AutoIsfLiveLine.NotUsedRampOn, l[DoubleKey.ApsAutoIsfSmbDeliveryRatio.key])
        assertFalse(DoubleKey.ApsAutoIsfSmbDeliveryRatioMin.key in l)
        assertFalse(DoubleKey.ApsAutoIsfSmbDeliveryRatioMax.key in l)
    }

    @Test
    fun `with SMB on even target the fixed ratio is the floor in full loop`() {
        val l = lines(base.copy(bgRangeSetting = 30.0, smbOnEvenTarget = true))
        assertEquals(AutoIsfLiveLine.FullLoopFloor(AutoIsfLiveLines.FULL_LOOP_BELOW_MGDL), l[DoubleKey.ApsAutoIsfSmbDeliveryRatio.key])
    }

    @Test
    fun `a BG range below 10 is read as mmol per liter`() {
        assertEquals(2.0 * Constants.MMOLL_TO_MGDL, AutoIsfLiveLines.bgRangeMgdl(2.0))
        assertEquals(10.0, AutoIsfLiveLines.bgRangeMgdl(10.0))
        assertEquals(0.0, AutoIsfLiveLines.bgRangeMgdl(0.0))
    }

    @Test
    fun `every line has its own text and values with a unit are texts of their own`() {
        val bg: (Double) -> String = { "bg$it" }
        val iobTh = AutoIsfLiveLine.IobTh(2.0, 40, 5.0).textRef(bg) as TextRef.Named
        assertEquals("autoisf_live_iob_th", iobTh.name)
        assertEquals(listOf("format_insulin_units", "format_insulin_units"), iobTh.args.filterIsInstance<TextRef.Named>().map { it.name })
        assertEquals(40, iobTh.args[1])

        val ramp = AutoIsfLiveLine.Ramp(0.3, 0.9, 30.0).textRef(bg) as TextRef.Named
        assertEquals(listOf<Any?>(0.3, 0.9, "bg30.0"), ramp.args)

        val off = AutoIsfLiveLine.NoEffectWhileOff(BooleanKey.ApsUseAutoIsfWeights).textRef(bg) as TextRef.Named
        assertEquals(BooleanKey.ApsUseAutoIsfWeights.title, off.args.single())

        val all = listOf(
            AutoIsfLiveLine.NoEffectWhileOff(BooleanKey.ApsUseAutoIsfWeights), AutoIsfLiveLine.WeightOff, AutoIsfLiveLine.RangeNone,
            AutoIsfLiveLine.RangeStronger(2.0), AutoIsfLiveLine.RangeWeaker(2.0), AutoIsfLiveLine.RangeBoth(2.0, 2.0),
            AutoIsfLiveLine.IobThOff(100), AutoIsfLiveLine.IobTh(2.0, 40, 5.0), AutoIsfLiveLine.RampOff(0.5),
            AutoIsfLiveLine.Ramp(0.3, 0.9, 30.0), AutoIsfLiveLine.NotUsedRampOff, AutoIsfLiveLine.NotUsedRampOn,
            AutoIsfLiveLine.FullLoopFloor(100.0)
        ).map { (it.textRef(bg) as TextRef.Named).name }
        assertEquals(all.size, all.toSet().size, "two lines share a text: $all")
        assertTrue(all.all { it.startsWith("autoisf_live_") })
    }
}
