package app.aaps.plugins.automation.states

import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.automation.AutomationStateSnapshot
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.json.Json

/**
 * Named automation states with a fixed list of allowed values (autoISF 3.2.x, Tobias/ga-zelle).
 *
 * Automations set them with an action and test them with a trigger; the autoISF activity monitor reads
 * the "Sleeping" state. The states and their allowed values are stored as JSON in two preferences, under
 * the same names as the automation-state plugin of the 3.4 fork, so existing states carry over.
 *
 * In 3.4 this was a plugin of its own with a tab. In v4 it is a plain service here; the UI changes the
 * values through the preferences, which a screen can observe.
 */
@ContributesBinding(AppScope::class, binding = binding<AutomationStateInterface>())
@SingleIn(AppScope::class)
@Inject
class AutomationStateService(
    private val preferences: Preferences
) : AutomationStateInterface {

    /** Was `@Synchronized` on the JVM in the fork. */
    private val lock = AapsLock()

    private var automationStates: HashMap<String, String> =
        runCatching { Json.decodeFromString<HashMap<String, String>>(preferences.get(StringNonKey.AutomationCurrentStates)) }.getOrElse { HashMap() }

    private var stateValues: HashMap<String, List<String>> =
        runCatching { Json.decodeFromString<HashMap<String, List<String>>>(preferences.get(StringNonKey.AutomationStateValues)) }.getOrElse { HashMap() }

    override fun inState(stateName: String, state: String): Boolean = lock.withLock {
        automationStates[stateName.trim()]?.let { it == state.trim() } ?: false
    }

    override fun setState(stateName: String, state: String) = lock.withLock {
        val trimmedName = stateName.trim()
        val trimmedState = state.trim()
        // Validate that the state value is in the allowed list
        require(stateValues.containsKey(trimmedName)) { "Invalid state name: $trimmedName" }
        require(stateValues[trimmedName]!!.contains(trimmedState)) { "Invalid state value: $trimmedState" }
        automationStates[trimmedName] = trimmedState
        preferences.put(StringNonKey.AutomationCurrentStates, Json.encodeToString(automationStates))
    }

    override fun getState(stateName: String): String = lock.withLock {
        automationStates[stateName.trim()] ?: ""
    }

    override fun getAllStates(): List<Pair<String, String>> = lock.withLock {
        stateValues.keys.map { stateName -> Pair(stateName, automationStates[stateName] ?: "") }
    }

    fun clearStates() = lock.withLock {
        automationStates.clear()
        preferences.put(StringNonKey.AutomationCurrentStates, "{}")
    }

    override fun getStateValues(stateName: String): List<String> = lock.withLock {
        stateValues[stateName.trim()] ?: emptyList()
    }

    override fun setStateValues(stateName: String, values: List<String>) = lock.withLock {
        val trimmedName = stateName.trim()
        val trimmedValues = values.map { it.trim() }
        // If the current value is not in the new list, the current state is cleared
        val currentState = automationStates[trimmedName]
        if (currentState != null && !trimmedValues.contains(currentState)) {
            automationStates.remove(trimmedName)
            preferences.put(StringNonKey.AutomationCurrentStates, Json.encodeToString(automationStates))
        }
        stateValues[trimmedName] = trimmedValues
        preferences.put(StringNonKey.AutomationStateValues, Json.encodeToString(stateValues))
    }

    override fun getStateSnapshot(stateName: String): AutomationStateSnapshot = lock.withLock {
        val trimmedName = stateName.trim()
        if (!stateValues.containsKey(trimmedName)) AutomationStateSnapshot(known = false, value = null)
        else automationStates[trimmedName].let { AutomationStateSnapshot(known = it != null, value = it) }
    }

    override fun hasStateValues(stateName: String): Boolean = lock.withLock {
        stateValues.containsKey(stateName.trim())
    }

    override fun deleteState(stateName: String) = lock.withLock {
        val trimmedName = stateName.trim()
        automationStates.remove(trimmedName)
        stateValues.remove(trimmedName)
        preferences.put(StringNonKey.AutomationCurrentStates, Json.encodeToString(automationStates))
        preferences.put(StringNonKey.AutomationStateValues, Json.encodeToString(stateValues))
    }
}
