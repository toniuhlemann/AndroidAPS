package app.aaps.plugins.automation.states

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowSnackbar
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsFab
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.automation.AutomationStrings

/** The tab of [AutomationStatePlugin]. */
internal class AutomationStatesContent(
    private val automationState: AutomationStateInterface,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val rh: TextResolver
) : ComposablePluginContent {

    @Composable
    override fun Render(setToolbarConfig: (ToolbarConfig) -> Unit, onNavigateBack: () -> Unit, onSettings: (() -> Unit)?) {
        val title = stringResource(AutomationStrings.automation_states)
        val backDesc = stringResource(CoreUiStrings.back)
        LaunchedEffect(Unit) {
            setToolbarConfig(
                ToolbarConfig(
                    title = title,
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDesc)
                        }
                    },
                    actions = {}
                )
            )
        }

        // Watched rather than read once: an automation, the viewer's command channel or a settings import
        // changes the states while the tab is open.
        val currentJson by preferences.observe(StringNonKey.AutomationCurrentStates).collectAsStateWithLifecycle()
        val valuesJson by preferences.observe(StringNonKey.AutomationStateValues).collectAsStateWithLifecycle()
        val states = remember(currentJson, valuesJson) { readAutomationStates(automationState) }

        AutomationStatesScreen(
            states = states,
            onSetValue = { name, value ->
                // Only fails when the allowed values changed elsewhere since the tab drew them.
                runCatching { automationState.setState(name, value) }
                    .onFailure { rxBus.send(EventShowSnackbar(rh.gs(AutomationStrings.automation_state_set_failed, name, value), EventShowSnackbar.Type.Error)) }
            },
            onSaveValues = { name, values, hadValue ->
                runCatching { saveValues(automationState, name, values, hadValue) }
                    .onFailure { rxBus.send(EventShowSnackbar(rh.gs(AutomationStrings.automation_state_set_failed, name, values.first()), EventShowSnackbar.Type.Error)) }
            },
            onDeleteState = { name -> automationState.deleteState(name) }
        )
    }
}

/**
 * The list of automation states. A tap on a value sets it; the pencil edits the allowed values of a state
 * and offers to delete it; the + button adds a state.
 *
 * @param onSaveValues name, the new allowed values, and whether the state had a value when the dialog opened
 */
@Composable
internal fun AutomationStatesScreen(
    states: List<AutomationStateUi>,
    onSetValue: (name: String, value: String) -> Unit,
    onSaveValues: (name: String, values: List<String>, hadValue: Boolean) -> Unit,
    onDeleteState: (name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (states.isEmpty()) EmptyState()
            else LazyColumn(
                // Room under the last card, so the + button never covers its values.
                contentPadding = PaddingValues(
                    start = AapsSpacing.medium,
                    top = AapsSpacing.medium,
                    end = AapsSpacing.medium,
                    bottom = AapsSpacing.xxLarge * 4
                ),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                modifier = Modifier.fillMaxSize()
            ) {
                items(states, key = { it.name }) { state ->
                    StateCard(state = state, onSetValue = onSetValue, onEdit = { editing = state.name })
                }
            }
            AapsFab(
                onClick = { adding = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(AapsSpacing.extraLarge)
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(AutomationStrings.automation_state_add))
            }
        }
    }

    if (adding) AddStateDialog(
        onDismiss = { adding = false },
        onNext = { name ->
            adding = false
            editing = name
        }
    )

    editing?.let { name ->
        // A name that already exists opens that state, as in the fork - nothing is lost by adding it twice.
        val existing = states.find { it.name == name }
        StateValuesDialog(
            name = name,
            initialValues = existing?.values.orEmpty(),
            currentValue = existing?.current.orEmpty(),
            canDelete = existing != null,
            onDismiss = { editing = null },
            onSave = { values ->
                onSaveValues(name, values, existing?.current?.isNotEmpty() == true)
                editing = null
            },
            onDelete = {
                editing = null
                deleting = name
            }
        )
    }

    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(AutomationStrings.automation_state_delete)) },
            text = { Text(stringResource(AutomationStrings.automation_state_delete_confirmation, name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteState(name)
                    deleting = null
                }) { Text(stringResource(CoreUiStrings.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(CoreUiStrings.cancel)) }
            }
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AapsSpacing.xxLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(AutomationStrings.automation_states),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(AutomationStrings.automation_states_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AapsSpacing.medium)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StateCard(state: AutomationStateUi, onSetValue: (String, String) -> Unit, onEdit: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(horizontal = AapsSpacing.large, vertical = AapsSpacing.medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(AutomationStrings.automation_state_edit_values, state.name))
                }
            }
            if (state.current.isEmpty())
                Text(
                    text = stringResource(AutomationStrings.automation_state_no_value),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                modifier = Modifier.fillMaxWidth()
            ) {
                state.values.forEach { value ->
                    val selected = value == state.current
                    FilterChip(
                        selected = selected,
                        onClick = { onSetValue(state.name, value) },
                        label = { Text(value) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun AddStateDialog(onDismiss: () -> Unit, onNext: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var missing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun next() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) missing = true else onNext(trimmed)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AutomationStrings.automation_state_add)) },
        text = {
            Column(modifier = Modifier.clearFocusOnTap(focusManager)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        missing = false
                    },
                    label = { Text(stringResource(AutomationStrings.state_name_label)) },
                    singleLine = true,
                    isError = missing,
                    supportingText = if (missing) {
                        { Text(stringResource(AutomationStrings.automation_state_missing_name)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { next() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { next() }) { Text(stringResource(CoreUiStrings.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) } }
    )
}

/**
 * Edits the allowed values of one state. Nothing is stored until OK; Cancel leaves the state as it was, and
 * a new state is only created by OK.
 */
@Composable
private fun StateValuesDialog(
    name: String,
    initialValues: List<String>,
    currentValue: String,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onDelete: () -> Unit
) {
    var values by remember(name) { mutableStateOf(initialValues) }
    var newValue by remember(name) { mutableStateOf("") }
    var problem by remember(name) { mutableStateOf<ValuesEdit?>(null) }
    val focusManager = LocalFocusManager.current

    fun applyEdit(edit: ValuesEdit): Boolean =
        if (edit is ValuesEdit.Changed) {
            values = edit.values
            problem = null
            true
        } else {
            problem = edit
            false
        }

    fun add() {
        if (applyEdit(addValue(values, newValue))) newValue = ""
    }

    fun save() {
        // A value typed but not yet added is meant to be in the list.
        val withTyped = (addValue(values, newValue) as? ValuesEdit.Changed)?.values ?: values
        if (withTyped.isEmpty()) problem = ValuesEdit.MissingValue
        else onSave(withTyped)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AutomationStrings.automation_state_values_title, name)) },
        text = {
            Column(
                modifier = Modifier
                    .clearFocusOnTap(focusManager)
                    .verticalScroll(rememberScrollState())
            ) {
                values.forEachIndexed { index, value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isCurrent = value == currentValue
                        Text(
                            text = value,
                            fontWeight = if (isCurrent) FontWeight.Bold else null,
                            modifier = Modifier.weight(1f)
                        )
                        if (isCurrent)
                            Icon(Icons.Default.Check, contentDescription = stringResource(AutomationStrings.automation_state_current_value))
                        IconButton(onClick = { applyEdit(removeValue(values, index)) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(AutomationStrings.automation_state_delete_value, value))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = {
                            newValue = it
                            problem = null
                        },
                        label = { Text(stringResource(AutomationStrings.state_value_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { add() }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(AutomationStrings.automation_state_add_value))
                    }
                }
                val problemText = when (problem) {
                    ValuesEdit.MissingValue -> AutomationStrings.automation_state_missing_value
                    ValuesEdit.ValueExists  -> AutomationStrings.automation_state_value_exists
                    ValuesEdit.LastValue    -> AutomationStrings.automation_state_last_value
                    is ValuesEdit.Changed,
                    null                    -> null
                }
                problemText?.let {
                    Text(
                        text = stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = AapsSpacing.small)
                    )
                }
                if (canDelete)
                    TextButton(onClick = onDelete, modifier = Modifier.padding(top = AapsSpacing.medium)) {
                        Text(stringResource(AutomationStrings.automation_state_delete), color = MaterialTheme.colorScheme.error)
                    }
            }
        },
        confirmButton = { TextButton(onClick = { save() }) { Text(stringResource(CoreUiStrings.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) } }
    )
}

@Preview(showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun PreviewAutomationStatesScreen() {
    MaterialTheme {
        AutomationStatesScreen(
            states = listOf(
                AutomationStateUi("Exercise", "", listOf("on", "off")),
                AutomationStateUi("Sleeping", "false", listOf("true", "false"))
            ),
            onSetValue = { _, _ -> },
            onSaveValues = { _, _, _ -> },
            onDeleteState = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun PreviewAutomationStatesScreenEmpty() {
    MaterialTheme {
        AutomationStatesScreen(
            states = emptyList(),
            onSetValue = { _, _ -> },
            onSaveValues = { _, _, _ -> },
            onDeleteState = {}
        )
    }
}
