/*
 * Fork (FUSE): time of day preference for Jetpack Compose
 */

package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.is24HourClock
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.stringResourceOrNull

/** Minutes in a day: a [UnitType.TIME_OF_DAY] value lies in 0 until this. */
internal const val MINUTES_PER_DAY = 24 * 60

/** The hour and minute of a [UnitType.TIME_OF_DAY] value; a value outside the day wraps around it. */
internal fun hourAndMinuteOf(minutesOfDay: Int): Pair<Int, Int> {
    val m = minutesOfDay.mod(MINUTES_PER_DAY)
    return m / 60 to m % 60
}

/**
 * A time of day, stored as minutes after midnight ([UnitType.TIME_OF_DAY]).
 *
 * The row shows the time in the user's clock format ("22:00") and opens the time picker, as the fork did in v3 with
 * its TimePickerDialog. The explanation of the key stands above the picker, like the explanation in the input dialog
 * of the other values. The stored number does not change its meaning, so values set before stay as they are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeOfDayPreferenceItem(
    intKey: IntPreferenceKey,
    title: TextRef,
    enabled: Boolean,
    /** Optional live line on the row instead of the summary; the picker keeps the summary. */
    rowSummary: String? = null
) {
    val state = rememberPreferenceIntState(intKey)
    val value = state.value
    val theme = LocalPreferenceTheme.current
    val dateUtil = LocalDateUtil.current
    val summary = stringResourceOrNull(intKey.summary)
    val mode = LocalPreferenceSummaryMode.current
    // LIVE_ONLY: the explanation is above the picker
    val rowText = if (mode == PreferenceSummaryMode.LIVE_ONLY) rowSummary else rowSummary ?: summary
    val (hour, minute) = hourAndMinuteOf(value)
    val timeText = dateUtil.timeString(dateUtil.secondsOfTheDayToMillisecondsOfHoursAndMinutes((hour * 60 + minute) * 60))
    var showPicker by remember { mutableStateOf(false) }

    Preference(
        title = { PreferenceTitleWithSyncBadge(title, intKey) },
        enabled = enabled,
        summary = rowText?.let { text -> { Text(text) } },
        widgetContainer = {
            Text(
                text = timeText,
                style = theme.summaryTextStyle,
                color = theme.summaryColor.let { if (enabled) it else it.copy(alpha = theme.disabledOpacity) },
                modifier = Modifier.padding(theme.padding.copy(start = theme.horizontalSpacing))
            )
        },
        onClick = if (enabled) ({ showPicker = true }) else null
    )

    if (showPicker) {
        val pickerState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = is24HourClock())
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                    if (summary != null) Text(text = summary, style = theme.summaryTextStyle)
                    TimePicker(state = pickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    state.value = (pickerState.hour * 60 + pickerState.minute).coerceIn(intKey.min, intKey.max)
                    showPicker = false
                }) {
                    Text(stringResource(CoreUiStrings.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(CoreUiStrings.cancel))
                }
            },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
        )
    }
}
