package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.interfaces.InterfacesStringsValues
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.KeysStringsValues
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStringsValues
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Fork: Android formats a line with `java.util.Formatter`, which throws when a placeholder does not fit its
 * argument - in the settings screen that is a crash. So every line is formatted here the same strict way, with the
 * English texts from strings.xml.
 */
class AutoIsfLiveLinesFormatTest {

    private val bg: (Double) -> String = { "${it.toInt()} mg/dl" }

    private val allLines = listOf(
        AutoIsfLiveLine.NoEffectWhileOff(BooleanKey.ApsUseAutoIsfWeights),
        AutoIsfLiveLine.NoEffectWhileOff(BooleanKey.ApsAutoIsfSmbOnEvenTarget),
        AutoIsfLiveLine.WeightOff,
        AutoIsfLiveLine.RangeNone,
        AutoIsfLiveLine.RangeStronger(2.0),
        AutoIsfLiveLine.RangeWeaker(1.25),
        AutoIsfLiveLine.RangeBoth(2.0, 1.25),
        AutoIsfLiveLine.IobThOff(100),
        AutoIsfLiveLine.IobTh(2.0, 40, 5.0),
        AutoIsfLiveLine.RampOff(0.5),
        AutoIsfLiveLine.Ramp(0.3, 0.9, 30.0),
        AutoIsfLiveLine.NotUsedRampOff,
        AutoIsfLiveLine.NotUsedRampOn,
        AutoIsfLiveLine.FullLoopFloor(100.0)
    )

    /** Like the Compose side: a text argument first, then the line - but with the strict JVM formatter. */
    private fun english(ref: TextRef): String = when (ref) {
        is TextRef.Named      -> {
            val template = when (ref.owner) {
                "aps"        -> ApsStringsValues.textOf(ref.name, null)
                "interfaces" -> InterfacesStringsValues.textOf(ref.name, null)
                "keys"       -> KeysStringsValues.textOf(ref.name, null)
                else         -> null
            } ?: error("no text for ${ref.owner}/${ref.name}")
            val args = ref.args.map { if (it is TextRef) english(it) else it }
            // Without arguments Android does not format at all, so a %% would show as it is.
            if (args.isEmpty()) template else String.format(Locale.US, template, *args.toTypedArray())
        }

        is TextRef.Literal    -> ref.text
        is TextRef.AndroidRes -> error("a line must be a named text")
    }

    @Test
    fun `every line formats with its arguments and leaves no placeholder`() {
        for (line in allLines) {
            val text = english(line.textRef(bg))
            assertThat(text).doesNotContainMatch("%\\d|%%|%[sdf]|%\\.")
        }
    }

    @Test
    fun `the lines read as intended`() {
        assertThat(english(AutoIsfLiveLine.IobTh(2.0, 40, 5.0).textRef(bg)))
            .isEqualTo("2.00 U (40 % of max IOB 5.00 U). Profile % and sensitivity scale it.")
        assertThat(english(AutoIsfLiveLine.IobThOff(100).textRef(bg))).isEqualTo("Off: iobTH does nothing at 100 %")
        assertThat(english(AutoIsfLiveLine.Ramp(0.3, 0.9, 30.0).textRef(bg)))
            .isEqualTo("Ramp: 0.30 at target, rising to 0.90 at target + 30 mg/dl")
        assertThat(english(AutoIsfLiveLine.NoEffectWhileOff(BooleanKey.ApsUseAutoIsfWeights).textRef(bg)))
            .isEqualTo("No effect while Enable AutoISF is off")
        assertThat(english(AutoIsfLiveLine.RangeBoth(2.0, 1.25).textRef(bg)))
            .isEqualTo("autoISF can make ISF up to 2.00 times stronger or 1.25 times weaker")
        assertThat(english(AutoIsfLiveLine.FullLoopFloor(100.0).textRef(bg)))
            .isEqualTo("With the ramp on, only used at even targets below 100 mg/dl, as the lowest ratio")
    }
}
