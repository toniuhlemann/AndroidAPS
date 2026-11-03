package app.aaps.core.ui.compose.preference

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.interfaces.configuration.Config as AppConfig

/**
 * Fork (FUSE): a [UnitType.TIME_OF_DAY] key is shown as a clock time and set with the time picker, not with a slider
 * of minutes (Toni 03.11.2026). The stored number keeps its meaning: minutes after midnight.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TimeOfDayPreferenceTest {

    @get:Rule
    val compose = createComposeRule()

    /** An existing key turned into a time of day, without the switch it depends on and without simple mode. */
    private val nachtBeginn = object : IntPreferenceKey by IntKey.OverviewCarbsButtonIncrement1 {
        override val defaultValue = 22 * 60
        override val min = 0
        override val max = 24 * 60 - 1
        override val unitType = UnitType.TIME_OF_DAY
        override val dependency: BooleanPreferenceKey? = null
        override val defaultedBySM = false
    }

    private val prefs: Preferences = mock {
        on { get(nachtBeginn) } doReturn 22 * 60
        on { apsMode } doReturn true
    }
    private val config: AppConfig = mock { on { platform } doReturn AppPlatform.Android }

    /** "HH:mm" from the milliseconds the row asks for, so the test reads the time the row computed. */
    private val dateUtil: DateUtil = mock {
        on { secondsOfTheDayToMillisecondsOfHoursAndMinutes(any()) } doAnswer { it.getArgument<Int>(0) * 1000L }
        on { timeString(any<Long>()) } doAnswer {
            val minuten = (it.getArgument<Long>(0) / 60_000L).toInt()
            "${(minuten / 60).toString().padStart(2, '0')}:${(minuten % 60).toString().padStart(2, '0')}"
        }
    }

    /** Without PreviewTheme: it puts its own Preferences in place, which forget every write. */
    private fun render(block: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalPreferences provides prefs, LocalConfig provides config, LocalDateUtil provides dateUtil) {
            MaterialTheme { ProvidePreferenceTheme { block() } }
        }
    }

    @Test
    fun `the row shows the stored minutes as a clock time`() {
        render { AdaptiveIntPreferenceItem(intKey = nachtBeginn) }
        compose.onNodeWithText("22:00").assertIsDisplayed()
    }

    @Test
    fun `a tap opens the time picker and OK keeps the time in minutes`() {
        render { AdaptiveIntPreferenceItem(intKey = nachtBeginn) }
        compose.onNodeWithText("22:00").performClick()
        compose.onNodeWithText("OK", ignoreCase = true).performClick()
        verify(prefs).put(nachtBeginn, 22 * 60)
    }

    @Test
    fun `hour and minute of a value, a value outside the day wraps around it`() {
        assertEquals(22 to 0, hourAndMinuteOf(1320))
        assertEquals(6 to 30, hourAndMinuteOf(390))
        assertEquals(0 to 0, hourAndMinuteOf(MINUTES_PER_DAY))
        assertEquals(23 to 59, hourAndMinuteOf(-1))
    }
}
